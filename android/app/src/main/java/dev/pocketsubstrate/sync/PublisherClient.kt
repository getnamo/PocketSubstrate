package dev.pocketsubstrate.sync

import android.content.Context
import android.os.ParcelFileDescriptor
import dev.pocketsubstrate.bundles.BundleStore
import dev.pocketsubstrate.ipc.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class PublisherClient(context: Context, private val store: BundleStore, private val received: (JSONObject, String) -> Unit, private val status: (String) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bridge = NetworkBridge.get(context)
    private var service: INetworkService? = null
    @Volatile private var generation = 0
    @Volatile private var running = false
    suspend fun pair(link: String) { store.setSetting("pairing", bridge.pair(link)) }
    fun start() {
        val pairing = store.setting("pairing") ?: return
        if (running) return
        running = true
        val version = ++generation
        scope.launch {
            try {
                val remote = bridge.service()
                if (!running || version != generation) return@launch
                service = remote
                remote.connect(pairing, object : INetworkEvents.Stub() {
                    override fun status(value: String) { if (version == generation) this@PublisherClient.status(value) }
                    override fun delivery(payload: ParcelFileDescriptor, owner: String) {
                        scope.launch {
                            try {
                                val text = ParcelFileDescriptor.AutoCloseInputStream(payload).use { input ->
                                    val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                                    while (true) { val count = input.read(buffer); if (count < 0) break; require(out.size() + count <= 12 * 1024 * 1024 + 4096); out.write(buffer, 0, count) }
                                    out.toString("UTF-8")
                                }
                                if (running && version == generation) received(JSONObject(text), owner)
                            } catch (e: Exception) { if (version == generation) this@PublisherClient.status("Delivery rejected: ${e.message}") }
                        }
                    }
                })
            } catch (e: Exception) { if (version == generation) { running = false; status(e.message ?: "Network helper unavailable") } }
        }
    }
    fun report(state: String, appId: String? = null, revision: String? = null) { send(JSONObject().put("protocol", 1).put("type", "status").put("state", state).put("appId", appId).put("revision", revision)) }
    fun diagnostic(appId: String, message: String) {
        if (store.setting("shareDiagnostics") == "true") send(JSONObject().put("protocol", 1).put("type", "diagnostic").put("appId", appId).put("message", message.take(2000)))
    }
    private fun send(message: JSONObject) { runCatching { service?.send(message.toString()) } }
    fun stop() { running = false; generation++; runCatching { service?.disconnect() }; service = null }
    fun close() { stop(); scope.cancel() }
}
