package com.mapmate.app.telemetry

import android.util.Log

object TelemetrySerializer {

    private const val TAG = "TelemetrySerializer"
    private const val SCHEMA_VERSION = 1

    fun encode(
        userId: String,
        latitude: Double,
        longitude: Double,
        bearing: Float,
        smoothedSpeed: Float,
        motionStatus: KMotionStatus,
        transportMode: KTransportMode,
        batteryStatus: BatteryStatus?
    ): ByteArray? {
        return try {
            val record = telemetryRecord {
                this.userId            = userId
                this.latitude          = latitude
                this.longitude         = longitude
                this.bearing           = bearing
                this.speedMps          = smoothedSpeed
                this.motionStatus      = motionStatus.toProto()
                this.transportMode     = transportMode.toProto()
                this.batteryPercentage = batteryStatus?.percentage ?: 0
                this.isCharging        = batteryStatus?.isCharging ?: false
                this.batteryLevel      = batteryStatus?.state?.toProto()
                    ?: BatteryLevel.BATTERY_UNKNOWN
                this.timestampMs       = System.currentTimeMillis()
                this.schemaVersion     = SCHEMA_VERSION
            }
            val bytes = record.toByteArray()
            Log.d(TAG, "✅ Encoded ${bytes.size} bytes (JSON ~120 bytes)")
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "Encode failed", e)
            null
        }
    }

    fun decode(bytes: ByteArray): TelemetryRecord? {
        return try {
            TelemetryRecord.parseFrom(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Decode failed", e)
            null
        }
    }
}

fun KMotionStatus.toProto(): MotionStatus = when (this) {
    KMotionStatus.STILL   -> MotionStatus.STILL
    KMotionStatus.WALKING -> MotionStatus.WALKING
    KMotionStatus.MOVING  -> MotionStatus.MOVING
}

fun KTransportMode.toProto(): TransportMode = when (this) {
    KTransportMode.UNKNOWN -> TransportMode.MODE_UNKNOWN
    KTransportMode.WALK    -> TransportMode.WALK
    KTransportMode.BIKE    -> TransportMode.BIKE
    KTransportMode.CAR     -> TransportMode.CAR
    KTransportMode.SHIP    -> TransportMode.SHIP
}

fun KBatteryState.toProto(): BatteryLevel = when (this) {
    KBatteryState.CHARGING -> BatteryLevel.CHARGING
    KBatteryState.FULL     -> BatteryLevel.FULL
    KBatteryState.LOW      -> BatteryLevel.LOW
    KBatteryState.NORMAL   -> BatteryLevel.NORMAL
}