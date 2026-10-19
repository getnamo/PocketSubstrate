package dev.pocketsubstrate.capabilities

import android.Manifest as AndroidManifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import dev.pocketsubstrate.bundles.*
import kotlinx.coroutines.*
import dev.pocketsubstrate.sync.NetworkBridge
import org.json.JSONObject
import kotlin.coroutines.resume

class CapabilityBroker(private val context: Context, private val store: BundleStore, private val manifest: Manifest, private val event: (String, JSONObject) -> Unit) {
    private var motion: SensorEventListener? = null
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var active = true
    private fun check(capability: String) {
        if (!active) throw SubstrateException("SESSION_CLOSED", "App is no longer active")
        Policy.check(manifest, store.app(manifest.id)?.grants ?: emptySet(), store.bans(), capability)
    }
    suspend fun call(method: String, params: JSONObject): Any {
        if (method == "capabilities") return JSONObject().apply {
            manifest.capabilities.forEach { put(it.name, runCatching { check(it.name) }.isSuccess) }
        }
        val capability = method.substringBefore('.')
        check(capability)
        val result: Any = when (method) {
            "storage.get" -> withContext(Dispatchers.IO) { store.storage(manifest.id, params.getString("key")) }
            "storage.set" -> withContext(Dispatchers.IO) { check("storage"); store.storage(manifest.id, params.getString("key"), params.opt("value"), true) }
            "haptics.pulse" -> {
                val duration = params.optLong("milliseconds", 30).coerceIn(1, 200)
                context.getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)); true
            }
            "motion.start" -> {
                if (motion == null) {
                    val sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: throw SubstrateException("UNSUPPORTED", "No motion sensor")
                    val listener = object : SensorEventListener {
                        private var last = 0L
                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                        override fun onSensorChanged(value: SensorEvent) {
                            if (value.timestamp - last < 100_000_000) return
                            last = value.timestamp
                            if (runCatching { check("motion") }.isFailure) { stopMotion(); return }
                            event("motion", JSONObject().put("x", value.values[0]).put("y", value.values[1]).put("z", value.values[2]))
                        }
                    }
                    require(sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)) { "Sensor unavailable" }
                    motion = listener
                }
                true
            }
            "motion.stop" -> { stopMotion(); true }
            "location.current" -> {
                if (ContextCompat.checkSelfPermission(context, AndroidManifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) throw SubstrateException("OS_PERMISSION_DENIED", "Enable approximate location for PocketSubstrate in the native host")
                val manager = context.getSystemService(LocationManager::class.java)
                val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { manager.isProviderEnabled(it) } ?: throw SubstrateException("UNAVAILABLE", "Location is disabled")
                @Suppress("MissingPermission", "DEPRECATION")
                val location = withTimeout(12000) {
                    suspendCancellableCoroutine<Location> { continuation ->
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) { manager.removeUpdates(this); if (continuation.isActive) continuation.resume(location) }
                            override fun onProviderEnabled(provider: String) {}
                            override fun onProviderDisabled(provider: String) {}
                            @Deprecated("Deprecated in Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        }
                        manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                        continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                    }
                }
                JSONObject().put("latitude", location.latitude).put("longitude", location.longitude).put("accuracy", location.accuracy)
            }
            "network.fetch" -> NetworkBridge.get(context).fetch(params.getString("url"), manifest.capabilities.first { it.name == "network" }.origins)
            else -> throw SubstrateException("UNKNOWN_METHOD", "Unsupported method: $method")
        }
        check(capability)
        return result
    }
    private fun stopMotion() { motion?.let { sensors.unregisterListener(it) }; motion = null }
    fun close() { active = false; stopMotion() }
}
