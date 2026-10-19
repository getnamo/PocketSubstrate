package dev.pocketsubstrate.network

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.pocketsubstrate.ipc.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class NetworkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var retry: Job? = null
    @Volatile private var generation = 0
    private val requests = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, Call>()
    private val fetchClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(12, TimeUnit.SECONDS).build()
    private val binder = object : INetworkService.Stub() {
        override fun pair(link: String, reply: IReply) {
            authorize()
            scope.launch {
                try {
                    val uri = Uri.parse(link.trim())
                    require(uri.scheme == "pocketsubstrate" && uri.host == "pair") { "Expected a PocketSubstrate pairing link" }
                    val endpoint = uri.getQueryParameter("endpoint") ?: error("Missing endpoint")
                    val fingerprint = uri.getQueryParameter("fingerprint") ?: error("Missing fingerprint")
                    val secret = uri.getQueryParameter("secret") ?: error("Missing secret")
                    require(fingerprint.matches(Regex("[a-f0-9]{64}")) && secret.matches(Regex("[a-f0-9]{64}")))
                    val url = Uri.parse(endpoint)
                    require(url.scheme == "https" && !url.host.isNullOrEmpty() && url.userInfo == null && url.query == null && url.fragment == null && url.path.isNullOrEmpty())
                    val http = pinned(fingerprint)
                    try {
                        http.newCall(Request.Builder().url("$endpoint/pair").header("Authorization", "Bearer $secret").post(byteArrayOf().toRequestBody()).build()).execute().use { response ->
                            require(response.isSuccessful) { "Pairing expired or rejected. Restart the publisher for a new code." }
                            val json = JSONObject(response.body!!.string())
                            require(json.getInt("protocol") == 1)
                            val token = json.getString("token")
                            require(token.matches(Regex("[a-f0-9]{64}")))
                            reply.result(JSONObject().put("endpoint", endpoint).put("fingerprint", fingerprint).put("token", token).toString(), "")
                        }
                    } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
                } catch (e: Exception) { runCatching { reply.result("", e.message ?: "Pairing failed") } }
            }
        }
        override fun connect(pairing: String, events: INetworkEvents) { authorize(); disconnectInternal(); connectSocket(JSONObject(pairing), events, generation, 1000) }
        override fun disconnect() { authorize(); disconnectInternal() }
        override fun send(message: String) { authorize(); require(message.length <= 8192); socket?.send(message) }
        override fun fetch(requestId: String, url: String, allowedOrigins: String, reply: IReply) {
            authorize()
            require(requestId.length <= 100 && requests.size < 64)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try { reply.result(fetchUrl(requestId, url, JSONArray(allowedOrigins)), "") }
                catch (e: Exception) { runCatching { reply.result("", e.message ?: "Request failed") } }
                finally { calls.remove(requestId); requests.remove(requestId) }
            }
            requests[requestId] = job
            job.start()
        }
        override fun cancelFetch(requestId: String) { authorize(); calls.remove(requestId)?.cancel(); requests.remove(requestId)?.cancel() }
    }
    private fun authorize() { enforceCallingPermission("dev.pocketsubstrate.permission.NETWORK_BROKER", "Only the signed substrate host may use the network broker") }
    override fun onBind(intent: Intent) = binder
    override fun onUnbind(intent: Intent): Boolean { disconnectInternal(); calls.values.forEach { it.cancel() }; requests.values.forEach { it.cancel() }; return false }
    override fun onDestroy() { disconnectInternal(); scope.cancel(); fetchClient.dispatcher.cancelAll(); fetchClient.connectionPool.evictAll(); super.onDestroy() }
    private fun disconnectInternal() { generation++; retry?.cancel(); socket?.cancel(); socket = null; client?.dispatcher?.cancelAll(); client?.connectionPool?.evictAll(); client?.dispatcher?.executorService?.shutdown(); client = null }
    private fun connectSocket(pairing: JSONObject, events: INetworkEvents, version: Int, backoff: Long) {
        if (version != generation) return
        runCatching { events.status("Connecting to publisher…") }
        val http = pinned(pairing.getString("fingerprint")); client = http
        socket = http.newWebSocket(Request.Builder().url(pairing.getString("endpoint") + "/session").header("Authorization", "Bearer ${pairing.getString("token")}").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { if (version == generation) runCatching { events.status("Publisher connected") } }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (version != generation) return
                try {
                    require(text.length <= 12 * 1024 * 1024 + 4096)
                    val json = JSONObject(text); require(json.getInt("protocol") == 1)
                    if (json.getString("type") == "publish") {
                        val pipe = ParcelFileDescriptor.createPipe()
                        try { events.delivery(pipe[0], pairing.getString("fingerprint")) } finally { pipe[0].close() }
                        scope.launch { runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(text.toByteArray()) } } }
                    }
                } catch (e: Exception) { runCatching { events.status("Delivery rejected: ${e.message}") } }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (version != generation) return
                if (response?.code == 401) { runCatching { events.status("Pairing revoked. Pair again.") }; return }
                reconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { reconnect() }
            private fun reconnect() {
                if (version != generation) return
                runCatching { events.status("Publisher disconnected · reconnecting") }
                retry?.cancel()
                retry = scope.launch { delay(backoff); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown(); connectSocket(pairing, events, version, (backoff * 2).coerceAtMost(30000)) }
            }
        })
    }
    private fun fetchUrl(id: String, initial: String, origins: JSONArray): String {
        require(origins.length() in 1..10)
        val allowed = (0 until origins.length()).map { origins.getString(it) }.toSet()
        var url = initial
        repeat(4) {
            if (requests[id]?.isActive != true) error("Cancelled")
            val request = Request.Builder().url(url).get().build()
            val origin = "${request.url.scheme}://${request.url.host}" + if (request.url.port == 443) "" else ":${request.url.port}"
            require(request.url.scheme == "https" && origin in allowed && request.url.username.isEmpty() && request.url.password.isEmpty()) { "Destination is outside granted HTTPS origins" }
            val call = fetchClient.newCall(request); calls[id] = call
            if (requests[id]?.isActive != true) { call.cancel(); error("Cancelled") }
            call.execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) url = request.url.resolve(response.header("Location") ?: error("Missing redirect"))?.toString() ?: error("Invalid redirect")
                else {
                    val bytes = response.body?.byteStream()?.use { input ->
                        val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                        while (out.size() <= 16384) { val count = input.read(buffer, 0, minOf(buffer.size, 16385 - out.size())); if (count <= 0) break; out.write(buffer, 0, count) }
                        out.toByteArray()
                    } ?: byteArrayOf()
                    require(bytes.size <= 16384) { "Response exceeds 16 KiB" }
                    return JSONObject().put("status", response.code).put("body", bytes.toString(Charsets.UTF_8)).toString()
                }
            }
        }
        error("Redirect limit exceeded")
    }
    private fun pinned(fingerprint: String): OkHttpClient {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) { throw java.security.cert.CertificateException("Not a server") }
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                if (chain.isEmpty() || hash(chain[0].encoded) != fingerprint) throw java.security.cert.CertificateException("Publisher certificate changed")
                chain[0].checkValidity()
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        return OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust).hostnameVerifier { _, session -> runCatching { hash(session.peerCertificates[0].encoded) == fingerprint }.getOrDefault(false) }
            .followRedirects(false).followSslRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
