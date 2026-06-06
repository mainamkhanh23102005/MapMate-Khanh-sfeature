package com.mapmate.app.telemetry

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TelemetryService : Service() {

    companion object {
        private const val TAG                   = "TelemetryService"
        private const val NOTIFICATION_ID       = 1001
        private const val CHANNEL_ID            = "telemetry_tracking_channel"
        private const val UPDATE_INTERVAL_MS    = 3_000L
        private const val BATTERY_INTERVAL_MS   = 120_000L

        // GPS loss thresholds
        private const val WATCHDOG_INTERVAL_MS  = 15_000L   // check every 15s
        private const val STALE_THRESHOLD_MS    = 60_000L   // GPS silent > 60s → stale
        private const val OFFLINE_THRESHOLD_MS  = 300_000L  // GPS silent > 5min → offline
        private const val STALE_WRITE_INTERVAL  = 30_000L   // write stale location every 30s
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Pipeline components
    private val speedSmoother       = SpeedSmoother(windowSize = 5)
    private val transportClassifier = TransportClassifier()

    // Battery
    private lateinit var batteryMonitor: BatteryMonitor
    private var lastBatteryStatus: BatteryStatus? = null
    private var lastBatteryUpdateMs: Long = 0L

    // GPS loss tracking
    private var lastLocationTimestamp: Long = 0L
    private var lastKnownLat: Double = 0.0
    private var lastKnownLng: Double = 0.0
    private var lastKnownBearing: Float = -1f
    private var gpsAvailable: Boolean = true
    private var lastStaleWriteMs: Long = 0L

    // Coroutine scope tied to service lifetime
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var watchdogJob: Job? = null

    private val currentUserId: String
        get() = FirebaseAuth.getInstance().currentUser?.uid ?: "anonymous"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        batteryMonitor = BatteryMonitor(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showNotification("MapMate is tracking", "Sharing your location with friends")
        startLocationUpdates()
        startWatchdog()
        return START_STICKY
    }

    override fun onDestroy() {
        watchdogJob?.cancel()
        serviceJob.cancel()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        super.onDestroy()
        Log.d(TAG, "Service destroyed — pipeline stopped")
    }

    // ─────────────────────────────────────────────────────────────
    // WATCHDOG — runs every 15s, handles GPS loss independently
    // ─────────────────────────────────────────────────────────────
    private fun startWatchdog() {
        watchdogJob = serviceScope.launch {
            Log.d(TAG, "🐕 Watchdog started")
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                checkGpsHealth()
            }
        }
    }

    private fun checkGpsHealth() {
        // Not started yet
        if (lastLocationTimestamp == 0L) return

        val silentMs = System.currentTimeMillis() - lastLocationTimestamp

        when {
            // GPS healthy
            silentMs < STALE_THRESHOLD_MS -> {
                if (!gpsAvailable) {
                    Log.d(TAG, "✅ GPS restored after ${silentMs / 1000}s silence")
                    gpsAvailable = true
                    showNotification("MapMate is tracking", "Sharing your location with friends")
                }
            }

            // GPS lost > 60s → write stale location every 30s
            silentMs in STALE_THRESHOLD_MS until OFFLINE_THRESHOLD_MS -> {
                if (gpsAvailable) {
                    Log.w(TAG, "⚠️ GPS signal lost — using last known location")
                    gpsAvailable = false
                    showNotification("MapMate — GPS signal lost", "Using last known location")
                }

                val now = System.currentTimeMillis()
                if (now - lastStaleWriteMs >= STALE_WRITE_INTERVAL) {
                    RealtimeDbWriter.writeStale(currentUserId, lastKnownLat, lastKnownLng, lastKnownBearing, silentMs)
                    lastStaleWriteMs = now
                }
            }

            // GPS lost > 5min → write offline status
            else -> {
                if (gpsAvailable) {
                    gpsAvailable = false
                }
                Log.e(TAG, "❌ GPS offline > 5min — writing offline status")
                RealtimeDbWriter.writeOffline(currentUserId)
                showNotification("MapMate — location unavailable", "GPS offline for ${silentMs / 60_000}+ min")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // LOCATION REQUEST — fallback to network/WiFi when GPS lost
    // ─────────────────────────────────────────────────────────────
    private fun startLocationUpdates() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.e(TAG, "No location permission — stopping")
            stopSelf()
            return
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,   // GPS first
            UPDATE_INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
            .setMaxUpdateDelayMillis(10_000L)
            .setWaitForAccurateLocation(false)  // don't block pipeline waiting for perfect accuracy
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL) // fallback to WiFi/Cell
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                request, locationCallback, Looper.getMainLooper()
            )
            Log.d(TAG, "📡 Location updates started (GPS + network fallback)")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException requesting location", e)
            stopSelf()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // LOCATION CALLBACK — main pipeline + availability listener
    // ─────────────────────────────────────────────────────────────
    private val locationCallback = object : LocationCallback() {

        // Called by Android when GPS availability changes
        override fun onLocationAvailability(availability: LocationAvailability) {
            val available = availability.isLocationAvailable
            Log.d(TAG, "📡 Location availability changed: $available")

            if (!available && gpsAvailable) {
                Log.w(TAG, "⚠️ onLocationAvailability → GPS unavailable")
                // Don't act yet — let watchdog handle after STALE_THRESHOLD_MS
                // This avoids false positives (brief 1-2s dropouts)
            }
            if (available && !gpsAvailable) {
                Log.d(TAG, "✅ onLocationAvailability → GPS available again")
                gpsAvailable = true
                showNotification("MapMate is tracking", "Sharing your location with friends")
            }
        }

        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return

            // Update GPS health tracking
            lastLocationTimestamp = System.currentTimeMillis()
            lastKnownLat    = location.latitude
            lastKnownLng    = location.longitude
            lastKnownBearing = if (location.hasBearing()) location.bearing else -1f

            // Mark GPS as healthy if it was lost
            if (!gpsAvailable) {
                gpsAvailable = true
                Log.d(TAG, "✅ GPS signal restored")
                showNotification("MapMate is tracking", "Sharing your location with friends")
            }

            // ── Part 2: Direction + Movement ──
            val rawSpeed      = if (location.hasSpeed()) location.speed else 0f
            val smoothedSpeed = speedSmoother.add(rawSpeed)
            val bearing       = if (location.hasBearing()) location.bearing else -1f
            val motionStatus  = KMotionStatus.fromSpeed(smoothedSpeed)

            // ── Part 3: Transport Mode ──
            val transportMode = transportClassifier.classify(
                smoothedSpeed = smoothedSpeed,
                isOverWater   = false
            )

            // ── Part 4: Battery (every 2 minutes) ──
            val now = System.currentTimeMillis()
            if (lastBatteryStatus == null || now - lastBatteryUpdateMs >= BATTERY_INTERVAL_MS) {
                lastBatteryStatus   = batteryMonitor.getCurrentStatus()
                lastBatteryUpdateMs = now
            }
            val battery = lastBatteryStatus

            // ── Part 5: Protobuf serialize ──
            val protoBytes = TelemetrySerializer.encode(
                userId        = currentUserId,
                latitude      = location.latitude,
                longitude     = location.longitude,
                bearing       = bearing,
                smoothedSpeed = smoothedSpeed,
                motionStatus  = motionStatus,
                transportMode = transportMode,
                batteryStatus = battery
            )

            // ── Part 6: Write to Realtime Database ──
            if (protoBytes != null) {
                val record = TelemetrySerializer.decode(protoBytes)
                if (record != null) {
                    RealtimeDbWriter.write(record, protoBytes)
                }
            }

            Log.d(TAG,
                "📍 lat=${String.format("%.6f", location.latitude)}, " +
                        "lng=${String.format("%.6f", location.longitude)} | " +
                        "🧭 bearing=${String.format("%.1f", bearing)}° | " +
                        "🚶 speed=${String.format("%.2f", smoothedSpeed)} m/s | " +
                        "📊 motion=$motionStatus | " +
                        "🚗 transport=$transportMode | " +
                        "🔋 battery=${battery?.percentage}% ${battery?.state} | " +
                        "📦 proto=${protoBytes?.size ?: 0} bytes | " +
                        "📡 gps=$gpsAvailable"
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────
    private fun showNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Continuous location updates for friend sharing"
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}