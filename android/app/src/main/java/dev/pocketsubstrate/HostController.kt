package dev.pocketsubstrate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.*
import dev.pocketsubstrate.bundles.*
import dev.pocketsubstrate.runtime.AppRuntime
import dev.pocketsubstrate.sync.PublisherClient
import kotlinx.coroutines.*
import org.json.JSONObject

class HostController(private val activity: MainActivity) {
    val store = BundleStore(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var apps by mutableStateOf(store.apps()); private set
    var bans by mutableStateOf(store.bans()); private set
    var notice by mutableStateOf(""); private set
    var connection by mutableStateOf("No publisher paired"); private set
    var runtimeStatus by mutableStateOf(""); private set
    var runtime by mutableStateOf<AppRuntime?>(null); private set
    var runningId by mutableStateOf<String?>(null); private set
    var logs by mutableStateOf(listOf<String>()); private set
    var paired by mutableStateOf(store.setting("pairing") != null); private set
    var shareDiagnostics by mutableStateOf(store.setting("shareDiagnostics") == "true"); private set
    var foreground = false; private set
    private var runtimeGeneration = 0
    private var liveSession = false
    private val publisher = PublisherClient(activity, store, { envelope, owner ->
        val app = store.stage(envelope, owner)
        scope.launch { onStaged(app) }
    }, { text -> scope.launch { connection = text } })
    init {
        if (store.recoverIncomplete()) notice = "An interrupted activation was returned to review."
        refresh()
        activity.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("ready", "Apps ready for review", NotificationManager.IMPORTANCE_DEFAULT))
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (name in listOf("touch-game", "field-marker")) if (store.app("sample-$name") == null) {
                        val json = activity.assets.open("examples/$name.psub").bufferedReader().use { JSONObject(it.readText()) }
                        store.stage(json, "bundled-examples")
                    }
                }
                refresh()
            } catch (e: Exception) { notice = "Example import: ${e.message}" }
        }
    }
    fun refresh() { apps = store.apps(); bans = store.bans() }
    private fun onStaged(staged: AppRecord) {
        val app = store.app(staged.id) ?: return
        if (app.pending != staged.pending) return
        val previousPending = apps.find { it.id == app.id }?.pending
        refresh()
        val pending = app.pending ?: return
        if (previousPending == pending) return
        val capabilities = store.manifest(app.id, pending).capabilities
        val mayReload = foreground && liveSession && runningId == app.id && app.liveEdit && capabilities.all { it.scope in app.grants && it.name !in store.bans() }
        if (mayReload) approve(app.id, app.grants.intersect(capabilities.map { it.scope }.toSet()), pending)
        else { notice = "${app.name} is ready to inspect."; publisher.report("review-required", app.id, pending); notifyReady(app) }
    }
    fun pair(link: String) { scope.launch { try { publisher.pair(link); publisher.stop(); paired = true; if (foreground) publisher.start(); notice = "Publisher paired." } catch (e: Exception) { notice = e.message ?: "Pairing failed" } } }
    fun unpair() { publisher.stop(); store.setSetting("pairing", null); paired = false; liveSession = false; connection = "No publisher paired" }
    fun diagnostics(enabled: Boolean) { shareDiagnostics = enabled; store.setSetting("shareDiagnostics", enabled.toString()) }
    fun importBundle(text: String) { scope.launch { try { val app = withContext(Dispatchers.IO) { require(text.length <= 12 * 1024 * 1024 + 4096); store.stage(JSONObject(text), "local-import") }; onStaged(app) } catch (e: Exception) { notice = "Import rejected: ${e.message}" } } }
    fun approve(id: String, grants: Set<String>, expectedRevision: String? = null) {
        try { store.approve(id, grants, expectedRevision); stopRuntime(); refresh(); launch(id, true) } catch (e: Exception) { notice = e.message ?: "Activation failed" }
    }
    fun discard(id: String) { store.discard(id); refresh() }
    fun launch(id: String, allowLive: Boolean = true) {
        stopRuntime()
        val app = store.app(id) ?: return
        val revision = app.active ?: return
        runningId = id; liveSession = allowLive && app.liveEdit
        if (!foreground) return
        val version = ++runtimeGeneration
        runtimeStatus = "Starting…"
        try {
            val instance = AppRuntime(activity, store, id, revision,
                { if (version == runtimeGeneration) runtimeStatus = it },
                { if (version == runtimeGeneration) { store.confirm(id); publisher.report("running", id, revision); refresh() } },
                { error -> if (version == runtimeGeneration) {
                    runtime = null; runningId = null; liveSession = false; notice = "${app.name}: $error"
                    store.recoverIncomplete(); refresh(); publisher.report("failed", id, revision)
                } },
                { line -> if (version == runtimeGeneration) { logs = (logs + line).takeLast(40); publisher.diagnostic(id, line) } })
            runtime = instance; instance.start()
        } catch (e: Exception) { stopRuntime(); store.recoverIncomplete(); runningId = null; notice = e.message ?: "Runtime unavailable"; refresh() }
    }
    fun exitApp() { stopRuntime(); runningId = null; liveSession = false; store.recoverIncomplete(); refresh() }
    fun rollback(id: String) { try { stopRuntime(); store.rollback(id); refresh(); launch(id) } catch (e: Exception) { notice = e.message ?: "Rollback unavailable" } }
    fun setGrants(id: String, grants: Set<String>) { val wasRunning = runningId == id; if (wasRunning) stopRuntime(); store.setGrants(id, grants); refresh(); if (wasRunning) launch(id) }
    fun setBan(name: String, blocked: Boolean) { val resume = runningId; stopRuntime(); store.setBan(name, blocked); refresh(); if (resume != null) launch(resume) }
    fun setLive(id: String, enabled: Boolean) { store.setLiveEdit(id, enabled); if (runningId == id) liveSession = enabled; refresh() }
    fun delete(id: String) { if (runningId == id) exitApp(); android.webkit.WebStorage.getInstance().deleteOrigin("https://$id.substrate.invalid"); store.delete(id); refresh() }
    fun resume() { foreground = true; publisher.start(); val id = runningId; if (id != null) launch(id, false) }
    fun pause() { foreground = false; stopRuntime(); liveSession = false; publisher.stop(); store.recoverIncomplete(); refresh() }
    fun close() { pause(); publisher.close(); scope.cancel() }
    private fun stopRuntime() { runtimeGeneration++; val old = runtime; runtime = null; old?.close() }
    private fun notifyReady(app: AppRecord) {
        if (!NotificationManagerCompat.from(activity).areNotificationsEnabled()) return
        val intent = Intent(activity, MainActivity::class.java).putExtra("review", app.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(activity, app.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(activity, "ready").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("${app.name} is ready").setContentText("Inspect the app and choose permissions before launch.").setContentIntent(pending).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(activity).notify(app.id.hashCode(), notification) }
    }
}
