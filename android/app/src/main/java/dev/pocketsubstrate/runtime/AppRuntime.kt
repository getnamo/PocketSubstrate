package dev.pocketsubstrate.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.pocketsubstrate.bundles.*
import dev.pocketsubstrate.capabilities.CapabilityBroker
import dev.pocketsubstrate.node.NodeSession
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("SetJavaScriptEnabled")
class AppRuntime(private val context: Context, private val store: BundleStore, val appId: String, val revision: String,
                 private val status: (String) -> Unit, private val ready: () -> Unit, private val failed: (String) -> Unit,
                 private val diagnostic: (String) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manifest = store.manifest(appId, revision)
    private val origin = "https://$appId.substrate.invalid"
    private var closed = false
    private var nodeReady = false
    private var webReady = false
    private var reportedReady = false
    private var lastHeartbeat = System.currentTimeMillis()
    private val inFlight = AtomicInteger()
    private val backendReplies = mutableMapOf<String, (JSONObject) -> Unit>()
    private var backendSequence = 0
    private var webReply: ((JSONObject) -> Unit)? = null
    private val broker = CapabilityBroker(context, store, manifest) { name, value ->
        webReply?.invoke(JSONObject().put("event", name).put("value", value))
        node.send(JSONObject().put("type", "event").put("event", name).put("value", value))
    }
    private val node = NodeSession(context, scope, ::nodeMessage, ::fail)
    val webView = WebView(context)
    init {
        check(android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) == 4096L) { "This experimental Node runtime requires a 4 KB-page Android device" }
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { "Update Android System WebView to use PocketSubstrate" }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false // Persistent data goes through the per-app storage broker.
            allowFileAccess = false; allowContentAccess = false
            blockNetworkLoads = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.setWebViewRenderProcessClient(context.mainExecutor, object : WebViewRenderProcessClient() {
            override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) { renderer?.terminate(); fail("Web renderer is not responding") }
            override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) {}
        })
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
            override fun onConsoleMessage(message: ConsoleMessage): Boolean { diagnostic(message.message().take(2000)); return true }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = asset(request.url)
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { if (url?.startsWith("$origin/") != true) fail("Unexpected navigation") }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) fail("Frontend failed: ${error.description}") }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { fail("Web renderer stopped"); return true }
        }
        WebViewCompat.addWebMessageListener(webView, "substrateHost", setOf(origin)) { _, message, source, mainFrame, reply ->
            if (!closed && mainFrame && source.toString() == origin) {
                val send: (JSONObject) -> Unit = { if (!closed) reply.postMessage(it.toString()) }
                webReply = send
                try {
                    val text = message.data ?: error("Expected JSON text")
                    require(text.length <= 32768)
                    request(JSONObject(text), false, send)
                } catch (e: Exception) { fail("Invalid frontend request: ${e.message}") }
            }
        }
    }
    fun start() {
        status("Starting Node backend…")
        val bootstrap = context.assets.open("node-bootstrap.cjs").bufferedReader().use { it.readText() }
        val backend = store.file(appId, revision, "backend/main.cjs").readText()
        node.start(bootstrap + "\n(function(module,exports,require){\n" + backend + "\n})(__substrateModule,__substrateModule.exports,require);\n__substrateSend({type:'ready',node:process.versions.node});")
        webView.loadUrl("$origin/index.html")
        scope.launch {
            delay(15000)
            if (!reportedReady) fail("App startup timed out")
            while (isActive && !closed) {
                node.send(JSONObject().put("type", "heartbeat"))
                delay(3000)
                if (System.currentTimeMillis() - lastHeartbeat > 10000) fail("Node backend is not responding")
            }
        }
    }
    private fun checkReady() {
        if (nodeReady && webReady && !reportedReady) { reportedReady = true; status("Running · Node + web"); ready() }
    }
    private fun nodeMessage(message: JSONObject) {
        when (message.optString("type")) {
            "ready" -> { nodeReady = true; lastHeartbeat = System.currentTimeMillis(); checkReady() }
            "heartbeat" -> lastHeartbeat = System.currentTimeMillis()
            "native" -> request(message, true) { node.send(it.put("type", "reply")) }
            "result" -> backendReplies.remove(message.optString("id"))?.invoke(message)
            "log" -> diagnostic(message.optString("message").take(2000))
            "fatal" -> fail(message.optString("message").take(2000))
            else -> fail("Unsupported backend message")
        }
    }
    private fun request(message: JSONObject, fromNode: Boolean, respond: (JSONObject) -> Unit) {
        if (closed) return
        if (inFlight.incrementAndGet() > 64) { inFlight.decrementAndGet(); fail("Too many concurrent requests"); return }
        val id = message.optString("id").take(100)
        val method = message.optString("method")
        scope.launch {
            try {
                val params = message.optJSONObject("params") ?: JSONObject()
                val result: Any = when (method) {
                    "frontend.ready" -> { require(!fromNode); webReady = true; checkReady(); true }
                    "frontend.failed" -> { require(!fromNode); fail("Frontend failed: ${params.optString("message").take(500)}"); true }
                    "diagnostic" -> { diagnostic(params.optString("message").take(2000)); true }
                    "backend.call" -> {
                        require(!fromNode)
                        withTimeout(15000) {
                            while (!nodeReady) delay(20)
                            suspendCancellableCoroutine { continuation ->
                                val requestId = "b${++backendSequence}"
                                backendReplies[requestId] = { reply ->
                                    if (continuation.isActive) {
                                        val error = reply.optJSONObject("error")
                                        if (error != null) continuation.resumeWith(Result.failure(SubstrateException(error.optString("code", "BACKEND_ERROR"), error.optString("message"))))
                                        else continuation.resumeWith(Result.success(reply.opt("result") ?: JSONObject.NULL))
                                    }
                                }
                                continuation.invokeOnCancellation { backendReplies.remove(requestId) }
                                node.send(JSONObject().put("type", "call").put("id", requestId).put("method", params.getString("method")).put("params", params.optJSONObject("params") ?: JSONObject()))
                            }
                        }
                    }
                    else -> broker.call(method, params)
                }
                if (!closed) respond(JSONObject().put("id", id).put("result", result))
            } catch (e: Exception) {
                if (!closed) respond(JSONObject().put("id", id).put("error", JSONObject().put("code", when (e) { is SubstrateException -> e.code; is TimeoutCancellationException -> "TIMEOUT"; is SecurityException -> "OS_PERMISSION_DENIED"; else -> "INVALID_REQUEST" }).put("message", e.message?.take(500) ?: "Request failed")))
            } finally { inFlight.decrementAndGet() }
        }
    }
    private fun asset(uri: Uri): WebResourceResponse {
        val headers = mapOf(
            "Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; media-src 'self'; font-src 'self'; connect-src 'none'; frame-src 'none'; worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'",
            "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), accelerometer=(), gyroscope=()",
            "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"
        )
        try {
            require(uri.scheme == "https" && uri.host == "$appId.substrate.invalid" && uri.port == -1 && uri.userInfo == null)
            val path = uri.path ?: error("No path")
            val bytes: ByteArray
            val mime: String
            if (path == "/__substrate.js") { bytes = context.assets.open("web-sdk.js").use { it.readBytes() }; mime = "application/javascript" }
            else {
                val file = store.file(appId, revision, "web" + path)
                bytes = file.readBytes()
                mime = when (file.extension.lowercase()) { "html" -> "text/html"; "js" -> "application/javascript"; "css" -> "text/css"; "svg" -> "image/svg+xml"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "json" -> "application/json"; "woff2" -> "font/woff2"; "mp3" -> "audio/mpeg"; else -> "application/octet-stream" }
            }
            return WebResourceResponse(mime, "UTF-8", 200, "OK", headers, ByteArrayInputStream(bytes))
        } catch (_: Exception) { return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", headers, ByteArrayInputStream(byteArrayOf())) }
    }
    private fun fail(message: String) { if (!closed) { close(); failed(message) } }
    fun close() {
        if (closed) return
        closed = true; broker.close(); node.stop(); scope.cancel(); backendReplies.clear(); webReply = null
        webView.stopLoading(); webView.destroy()
    }
}
