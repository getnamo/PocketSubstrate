package dev.pocketsubstrate.bundles

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun JSONArray.strings() = (0 until length()).map { getString(it) }
val capabilityNames = setOf("storage", "haptics", "motion", "location", "network")
data class Capability(val name: String, val reason: String, val origins: List<String> = emptyList()) {
    val scope: String get() = if (name == "network") "network:" + origins.sorted().joinToString(",") else name
}
data class Manifest(val id: String, val name: String, val description: String, val capabilities: List<Capability>, val json: JSONObject) {
    companion object {
        fun parse(json: JSONObject): Manifest {
            require(json.getInt("schemaVersion") == 1 && json.getInt("apiVersion") == 1) { "Unsupported manifest version" }
            val id = json.getString("id")
            require(id.matches(Regex("[a-z][a-z0-9-]{2,47}"))) { "Invalid app ID" }
            val name = json.getString("name")
            require(name.isNotBlank() && name.length <= 80) { "Invalid app name" }
            require(json.getString("frontend") == "web/index.html" && json.getString("backend") == "backend/main.cjs") { "Invalid entry points" }
            val declared = json.getJSONArray("capabilities")
            require(declared.length() <= 5)
            val caps = (0 until declared.length()).map {
                val cap = declared.getJSONObject(it)
                val capName = cap.getString("name")
                val reason = cap.getString("reason")
                require(capName in capabilityNames && reason.isNotBlank() && reason.length <= 300) { "Invalid capability" }
                val origins = cap.optJSONArray("origins")?.strings() ?: emptyList()
                if (capName == "network") {
                    require(origins.isNotEmpty() && origins.size <= 10)
                    origins.forEach { origin ->
                        val uri = URI(origin)
                        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.isNullOrEmpty()) { "Expected exact HTTPS origin" }
                    }
                }
                Capability(capName, reason, origins)
            }
            require(caps.map { it.name }.distinct().size == caps.size) { "Duplicate capability" }
            return Manifest(id, name, json.optString("description").take(1000), caps, json)
        }
    }
}
data class AppRecord(val id: String, val name: String, val owner: String, val active: String?, val previous: String?, val pending: String?, val grants: Set<String>, val liveEdit: Boolean)
class SubstrateException(val code: String, message: String) : Exception(message)
object Policy {
    fun check(manifest: Manifest, grants: Set<String>, bans: Set<String>, capability: String) {
        if (capability in bans) throw SubstrateException("GLOBALLY_BLOCKED", "$capability is globally blocked")
        val declared = manifest.capabilities.find { it.name == capability } ?: throw SubstrateException("PERMISSION_DENIED", "$capability is not declared")
        if (declared.scope !in grants) throw SubstrateException("PERMISSION_DENIED", "$capability has not been granted to this app")
    }
}
