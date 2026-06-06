package com.mapmate.app.telemetry

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Writes telemetry to Firestore.
 *
 * Keeps backward compatibility with leader's Expo code:
 * - Same collection: "locations"
 * - Same fields: user_id, latitude, longitude, battery_level, is_charging, updated_at
 * - Adds new fields: bearing, speed_mps, motion_status, transport_mode, proto_payload
 *
 * Sommerville Ch6 §5.3 Repository Pattern:
 * Firestore = central shared data store for all clients.
 */
object FirestoreWriter {

    private const val TAG        = "FirestoreWriter"
    private const val COLLECTION = "locations"

    private val db by lazy { FirebaseFirestore.getInstance() }

    fun write(record: TelemetryRecord, protoBytes: ByteArray) {
        val data = hashMapOf(
            // ── Fields leader's Expo code already reads ──
            "user_id"       to record.userId,
            "latitude"      to record.latitude,
            "longitude"     to record.longitude,
            "battery_level" to record.batteryPercentage.toInt(),
            "is_charging"   to record.isCharging,
            "updated_at"    to java.util.Date(record.timestampMs).toString(),

            // ── New fields added by YOUR module ──
            "bearing"        to record.bearing,
            "speed_mps"      to record.speedMps,
            "motion_status"  to record.motionStatus.name,
            "transport_mode" to record.transportMode.name,
            "battery_status" to record.batteryLevel.name,

            // ── Protobuf binary — your key contribution ──
            "proto_payload"  to com.google.firebase.firestore.Blob.fromBytes(protoBytes),
            "schema_version" to record.schemaVersion.toInt()
        )

        db.collection(COLLECTION)
            .document(record.userId)
            .set(data, SetOptions.merge())
            .addOnSuccessListener {
                Log.d(TAG, "✅ Firestore write OK | proto=${protoBytes.size} bytes")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Firestore write FAILED", e)
            }
    }
}