package com.mapmate.app.telemetry

import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue

object RealtimeDbWriter {

    private const val TAG  = "RealtimeDbWriter"
    private const val ROOT = "locations"

    private val db by lazy { FirebaseDatabase.getInstance() }

    fun write(record: TelemetryRecord, protoBytes: ByteArray) {
        val data = hashMapOf(
            "user_id"        to record.userId,
            "latitude"       to record.latitude,
            "longitude"      to record.longitude,
            "battery_level"  to record.batteryPercentage.toInt(),
            "is_charging"    to record.isCharging,
            "updated_at"     to java.util.Date(record.timestampMs).toString(),
            "bearing"        to record.bearing,
            "speed_mps"      to record.speedMps,
            "motion_status"  to record.motionStatus.name,
            "transport_mode" to record.transportMode.name,
            "battery_status" to record.batteryLevel.name,
            "schema_version" to record.schemaVersion.toInt()
        )

        db.getReference("$ROOT/${record.userId}")
            .setValue(data)
            .addOnSuccessListener {
                Log.d(TAG, "✅ RTDB write OK")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ RTDB write FAILED", e)
            }
    }

    fun writeStale(userId: String, lat: Double, lng: Double, bearing: Float, silentMs: Long) {
        if (lat == 0.0 && lng == 0.0) return

        val updates = mapOf(
            "latitude"       to lat,
            "longitude"      to lng,
            "bearing"        to bearing,
            "location_stale" to true,
            "stale_since_ms" to silentMs,
            "gps_available"  to false,
            "updated_at"     to ServerValue.TIMESTAMP
        )

        db.getReference("$ROOT/$userId")
            .updateChildren(updates)
            .addOnSuccessListener {
                Log.d(TAG, "📍 Stale location written (silent ${silentMs / 1000}s)")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to write stale location", e)
            }
    }

    fun writeOffline(userId: String) {
        val updates = mapOf(
            "gps_available"  to false,
            "location_stale" to true,
            "updated_at"     to ServerValue.TIMESTAMP
        )

        db.getReference("$ROOT/$userId")
            .updateChildren(updates)
    }
}
