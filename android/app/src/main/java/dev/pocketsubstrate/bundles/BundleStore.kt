package dev.pocketsubstrate.bundles

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BundleStore(context: Context) {
    private val root = File(context.filesDir, "substrate").apply { mkdirs() }
    private val catalogFile = AtomicFile(File(root, "catalog.json"))
    private var catalog = if (catalogFile.baseFile.exists()) JSONObject(catalogFile.openRead().bufferedReader().use { it.readText() }) else JSONObject().put("apps", JSONObject()).put("bans", JSONArray())
    private fun persist() = atomicWrite(catalogFile, catalog.toString().toByteArray())
    private fun record(json: JSONObject) = AppRecord(json.getString("id"), json.getString("name"), json.getString("owner"), json.optString("active").ifEmpty { null }, json.optString("previous").ifEmpty { null }, json.optString("pending").ifEmpty { null }, json.getJSONArray("grants").strings().toSet(), json.optBoolean("liveEdit"))
    @Synchronized fun apps(): List<AppRecord> = catalog.getJSONObject("apps").let { all -> all.keys().asSequence().map { record(all.getJSONObject(it)) }.sortedBy { it.name }.toList() }
    @Synchronized fun app(id: String) = catalog.getJSONObject("apps").optJSONObject(id)?.let(::record)
    @Synchronized fun bans() = catalog.getJSONArray("bans").strings().toSet()
    @Synchronized fun setBan(name: String, blocked: Boolean) {
        require(name in capabilityNames)
        val bans = bans().toMutableSet(); if (blocked) bans.add(name) else bans.remove(name)
        catalog.put("bans", JSONArray(bans.toList())); persist()
    }
    fun revisionDir(id: String, revision: String): File {
        require(id.matches(Regex("[a-z][a-z0-9-]{2,47}")) && revision.matches(Regex("[a-f0-9]{64}")))
        return File(root, "apps/$id/revisions/$revision")
    }
    fun manifest(id: String, revision: String) = Manifest.parse(JSONObject(File(revisionDir(id, revision), "manifest.json").readText()))
    fun file(id: String, revision: String, path: String): File {
        require(validPath(path)) { "Invalid bundle path" }
        val base = revisionDir(id, revision).canonicalFile
        return File(base, path).canonicalFile.also { require(it.path.startsWith(base.path + File.separator)) }
    }
    @Synchronized fun stage(envelope: JSONObject, owner: String): AppRecord {
        require(envelope.getInt("protocol") == 1 && envelope.getString("type") == "publish") { "Unsupported delivery" }
        val encoded = envelope.getString("payload")
        require(encoded.length <= 12 * 1024 * 1024) { "Bundle too large" }
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size <= 8 * 1024 * 1024)
        val digest = sha256(payload)
        require(digest == envelope.getString("digest") && envelope.getString("deliveryId") == digest) { "Digest mismatch" }
        val body = JSONObject(payload.toString(Charsets.UTF_8))
        val manifest = Manifest.parse(body.getJSONObject("manifest"))
        val existing = app(manifest.id)
        require(existing == null || existing.owner == owner) { "This app belongs to a different publisher" }
        if (existing?.active == digest || existing?.pending == digest) return existing
        val entries = body.getJSONObject("files")
        require(entries.length() in 2..200 && entries.has("web/index.html") && entries.has("backend/main.cjs")) { "Invalid bundle files" }
        val decoded = entries.keys().asSequence().associateWith { path ->
            require(validPath(path) && (path.startsWith("web/") || path == "backend/main.cjs")) { "Invalid file path" }
            Base64.decode(entries.getString(path), Base64.NO_WRAP).also {
                require(it.size <= if (path == "backend/main.cjs") 128 * 1024 else 2 * 1024 * 1024) { "File too large" }
            }
        }
        val target = revisionDir(manifest.id, digest)
        if (!target.exists()) {
            val stage = File(target.parentFile, "$digest.staging")
            stage.deleteRecursively(); stage.mkdirs()
            try {
                decoded.forEach { (path, bytes) -> File(stage, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) } }
                File(stage, "manifest.json").writeText(manifest.json.toString())
                check(stage.renameTo(target)) { "Could not finish staging" }
            } finally { stage.deleteRecursively() }
        }
        val all = catalog.getJSONObject("apps")
        val json = all.optJSONObject(manifest.id) ?: JSONObject().put("id", manifest.id).put("owner", owner).put("grants", JSONArray())
        json.put("name", manifest.name).put("pending", digest)
        all.put(manifest.id, json); persist(); prune(manifest.id)
        return record(json)
    }
    @Synchronized fun approve(id: String, grants: Set<String>, expectedRevision: String? = null): AppRecord {
        val json = catalog.getJSONObject("apps").getJSONObject(id)
        val pending = json.getString("pending")
        require(expectedRevision == null || pending == expectedRevision) { "The pending revision changed. Inspect the new revision before launching." }
        val declared = manifest(id, pending).capabilities.map { it.scope }.toSet()
        require(declared.containsAll(grants))
        json.put("previous", json.optString("active")).put("active", pending).remove("pending")
        json.put("grants", JSONArray(grants.toList())).put("activating", true); persist(); prune(id)
        return record(json)
    }
    @Synchronized fun rollback(id: String): AppRecord {
        val json = catalog.getJSONObject("apps").getJSONObject(id)
        val previous = json.optString("previous")
        require(previous.isNotEmpty()) { "No previous revision" }
        json.put("previous", json.optString("active")).put("active", previous).put("activating", false); persist()
        return record(json)
    }
    @Synchronized fun confirm(id: String) { catalog.getJSONObject("apps").getJSONObject(id).put("activating", false); persist() }
    @Synchronized fun recoverIncomplete(): Boolean {
        var changed = false
        val all = catalog.getJSONObject("apps")
        all.keys().forEach { id ->
            val app = all.getJSONObject(id)
            if (app.optBoolean("activating")) {
                app.put("pending", app.optString("active")).put("active", app.optString("previous")).put("previous", "").put("activating", false)
                changed = true
            }
        }
        if (changed) persist()
        return changed
    }
    @Synchronized fun setGrants(id: String, grants: Set<String>) {
        val json = catalog.getJSONObject("apps").getJSONObject(id)
        val revision = json.optString("active").ifEmpty { json.getString("pending") }
        require(manifest(id, revision).capabilities.map { it.scope }.containsAll(grants))
        json.put("grants", JSONArray(grants.toList())); persist()
    }
    @Synchronized fun setLiveEdit(id: String, enabled: Boolean) { catalog.getJSONObject("apps").getJSONObject(id).put("liveEdit", enabled); persist() }
    @Synchronized fun discard(id: String) { catalog.getJSONObject("apps").getJSONObject(id).remove("pending"); persist(); prune(id) }
    @Synchronized fun delete(id: String) {
        require(app(id) != null)
        catalog.getJSONObject("apps").remove(id); persist()
        File(root, "apps/$id").deleteRecursively()
    }
    @Synchronized fun storage(id: String, key: String, value: Any? = null, write: Boolean = false): Any {
        require(app(id) != null && key.length in 1..128)
        val file = AtomicFile(File(root, "apps/$id/data.json"))
        val data = if (file.baseFile.exists()) JSONObject(file.openRead().bufferedReader().use { it.readText() }) else JSONObject()
        if (write) {
            data.put(key, value ?: JSONObject.NULL)
            val bytes = data.toString().toByteArray()
            require(bytes.size <= 128 * 1024) { "Storage quota exceeded" }
            atomicWrite(file, bytes)
        }
        return data.opt(key) ?: JSONObject.NULL
    }
    @Synchronized fun setting(key: String): String? = catalog.optString(key).ifEmpty { null }
    @Synchronized fun setSetting(key: String, value: String?) { require(key != "apps" && key != "bans"); if (value == null) catalog.remove(key) else catalog.put(key, value); persist() }
    private fun prune(id: String) {
        val app = app(id) ?: return
        val keep = setOfNotNull(app.active, app.previous, app.pending)
        File(root, "apps/$id/revisions").listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
    }
    companion object {
        fun validPath(path: String) = path.matches(Regex("[a-zA-Z0-9_./-]+")) && path.split('/').none { it.isEmpty() || it == "." || it == ".." } && !path.lowercase().startsWith("web/__substrate")
        private fun atomicWrite(file: AtomicFile, bytes: ByteArray) {
            file.baseFile.parentFile!!.mkdirs()
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
        }
    }
}
