package dev.pocketsubstrate

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.pocketsubstrate.bundles.*
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    lateinit var controller: HostController; private set
    private var reviewRequest by mutableStateOf<String?>(null)
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val scanner = registerForActivityResult(ScanContract()) { result -> result.contents?.let { controller.pair(it) } }
    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) uiScope.launch {
            val text = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    val buffer = CharArray(8192); val result = StringBuilder()
                    while (true) { val count = reader.read(buffer); if (count < 0) break; if (result.length + count > 12 * 1024 * 1024 + 4096) return@use "{}"; result.append(buffer, 0, count) }
                    result.toString()
                }
            }
            text?.let(controller::importBundle)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = HostController(this)
        reviewRequest = intent.getStringExtra("review")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xffbde99b), background = Color(0xff10231f), surface = Color(0xff19342d), secondary = Color(0xff9bcdb7))) { HostScreen(controller) }
        }
    }
    override fun onStart() { super.onStart(); controller.resume() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller.exitApp()
        reviewRequest = intent.getStringExtra("review")
    }
    override fun onStop() { controller.pause(); super.onStop() }
    override fun onDestroy() { controller.close(); uiScope.cancel(); super.onDestroy() }
    @Composable private fun HostScreen(host: HostController) {
        var tab by remember { mutableStateOf(if (intent.hasExtra("review")) "Ready" else "Apps") }
        var review by remember { mutableStateOf<String?>(intent.getStringExtra("review")) }
        var permissionsFor by remember { mutableStateOf<String?>(null) }
        var detail by remember { mutableStateOf<String?>(null) }
        var pairing by remember { mutableStateOf("") }
        var deleting by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(reviewRequest) {
            reviewRequest?.let { tab = "Ready"; review = it; reviewRequest = null }
        }
        BackHandler(host.runningId != null) { host.exitApp() }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text("POCKET SUBSTRATE", style = MaterialTheme.typography.labelSmall); Text(if (host.runningId == null) "Your ideas, running." else host.apps.find { it.id == host.runningId }?.name ?: "App", style = MaterialTheme.typography.titleLarge) }
                    if (host.runningId != null) TextButton(onClick = { host.exitApp() }) { Text("Close app") }
                }
                if (host.runningId != null) {
                    Text(host.runtimeStatus, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(onClick = { host.runningId?.let(host::rollback) }) { Text("Rollback") }
                        TextButton(onClick = { permissionsFor = host.runningId; host.exitApp() }) { Text("Permissions") }
                    }
                    val runtime = host.runtime
                    if (runtime != null) key(runtime) { AndroidView(factory = { runtime.webView }, modifier = Modifier.weight(1f).fillMaxWidth()) }
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf("Apps", "Ready", "Connect", "Settings").forEach { name -> FilterChip(selected = tab == name, onClick = { tab = name }, label = { Text(if (name == "Ready") "Ready ${host.apps.count { it.pending != null }}" else name) }) }
                    }
                    if (host.notice.isNotEmpty()) Text(host.notice, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        when (tab) {
                            "Apps", "Ready" -> {
                                if (tab == "Apps") OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import bundle") }
                                val shown = host.apps.filter { if (tab == "Ready") it.pending != null else it.active != null }
                                if (shown.isEmpty()) Text(if (tab == "Ready") "New apps will appear here for review." else "Start in Ready to inspect the included examples, or connect a desktop publisher.")
                                shown.forEach { app -> Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(app.name, style = MaterialTheme.typography.titleLarge)
                                        Text(if (app.pending != null) "Revision ready for review" else "Installed · works offline", style = MaterialTheme.typography.bodySmall)
                                        Row { if (tab == "Ready") Button(onClick = { review = app.id }) { Text("Inspect") } else Button(onClick = { host.launch(app.id) }) { Text("Launch") }; TextButton(onClick = { detail = app.id }) { Text("Manage") } }
                                    }
                                } }
                            }
                            "Connect" -> {
                                Text("Build on your desktop. Try it here.", style = MaterialTheme.typography.headlineSmall)
                                Text(host.connection)
                                Text("Run the publisher on the same Wi-Fi network, then scan its code or paste its pairing link. The code expires after five minutes.")
                                Button(onClick = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan your desktop publisher").setBeepEnabled(false)) }) { Text("Scan pairing QR") }
                                OutlinedTextField(pairing, { pairing = it }, label = { Text("Pairing link") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                                Row { Button(onClick = { host.pair(pairing); pairing = "" }, enabled = pairing.isNotBlank()) { Text("Pair") }; if (host.paired) TextButton(onClick = { host.unpair() }) { Text("Forget publisher") } }
                                Text("Keep the host open to receive edits. New capabilities always return to review.", style = MaterialTheme.typography.bodySmall)
                            }
                            "Settings" -> {
                                Text("Capability bans", style = MaterialTheme.typography.headlineSmall)
                                Text("A ban applies to every app, even when that app has a grant.")
                                capabilityNames.forEach { name -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Block $name", Modifier.padding(top = 12.dp)); Switch(name in host.bans, { host.setBan(name, it) }) } }
                                HorizontalDivider()
                                Row { Text("Share runtime logs with publisher", Modifier.weight(1f).padding(top = 12.dp)); Switch(host.shareDiagnostics, host::diagnostics) }
                                Text("Logs may include app data. Sharing is off by default.", style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) { Text("Allow host location") }
                                if (Build.VERSION.SDK_INT >= 33) Button(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Enable ready notifications") }
                                Text("Experimental runtime · Node 18.20.4 · 4 KB devices. Not a production security release.", style = MaterialTheme.typography.bodySmall)
                                if (host.logs.isNotEmpty()) { Text("Recent runtime output"); Text(host.logs.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
        review?.let { id -> host.apps.find { it.id == id && it.pending != null }?.let { ReviewDialog(host, it, true) { review = null } } }
        permissionsFor?.let { id -> host.apps.find { it.id == id && it.active != null }?.let { ReviewDialog(host, it, false) { permissionsFor = null } } }
        detail?.let { id -> host.apps.find { it.id == id }?.let { app ->
            AlertDialog(onDismissRequest = { detail = null }, title = { Text(app.name) }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Source: ${if (app.owner == "bundled-examples") "Included example" else if (app.owner == "local-import") "Local import" else app.owner.take(16)}")
                    Row { Text("Allow live edits", Modifier.weight(1f)); Switch(app.liveEdit, { host.setLive(id, it) }) }
                    Text("Enable only for a publisher you trust. Updates can change behavior within existing grants. Live editing pauses when the host leaves the foreground.", style = MaterialTheme.typography.bodySmall)
                    if (app.active != null) TextButton(onClick = { detail = null; permissionsFor = id }) { Text("Edit permissions") }
                    if (app.previous != null) TextButton(onClick = { detail = null; host.rollback(id) }) { Text("Restore previous revision") }
                    TextButton(onClick = { detail = null; deleting = id }) { Text("Delete app and data") }
                }
            }, confirmButton = { TextButton(onClick = { detail = null }) { Text("Done") } })
        } }
        deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this app?") }, text = { Text("Its revisions, saved data, and permission grants will be removed.") }, confirmButton = { TextButton(onClick = { host.delete(id); deleting = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
    }
    @Composable private fun ReviewDialog(host: HostController, app: AppRecord, pending: Boolean, close: () -> Unit) {
        val revision = (if (pending) app.pending else app.active) ?: return
        val manifest = remember(revision) { host.store.manifest(app.id, revision) }
        var grants by remember(revision) { mutableStateOf(app.grants.intersect(manifest.capabilities.map { it.scope }.toSet())) }
        var inspected by remember(revision) { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = close, title = { Text(if (pending) "Inspect ${app.name}" else "Permissions") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(manifest.description)
                Text("Revision ${revision.take(12)}", style = MaterialTheme.typography.labelSmall)
                if (manifest.capabilities.isEmpty()) Text("No native capabilities requested.")
                manifest.capabilities.forEach { cap -> Row {
                    Checkbox(cap.scope in grants, { grants = if (it) grants + cap.scope else grants - cap.scope }, enabled = cap.name !in host.bans)
                    Column { Text(cap.name + if (cap.name in host.bans) " · globally blocked" else ""); Text(cap.reason, style = MaterialTheme.typography.bodySmall); if (cap.origins.isNotEmpty()) Text(cap.origins.joinToString("\n"), style = MaterialTheme.typography.bodySmall) }
                } }
                if (manifest.capabilities.any { it.name == "location" }) TextButton(onClick = { permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) { Text("Allow host location") }
                Text("Inspect source files", style = MaterialTheme.typography.titleSmall)
                val base = host.store.revisionDir(app.id, revision)
                base.walkTopDown().filter { it.isFile }.take(200).forEach { file ->
                    TextButton(onClick = { inspected = file.inputStream().bufferedReader().use { reader -> val chars = CharArray(12000); val count = reader.read(chars); if (count < 0) "" else String(chars, 0, count) } }) { Text(file.relativeTo(base).invariantSeparatorsPath) }
                }
                if (pending && app.active != null) Text("The previous revision is kept for rollback.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { if (pending) host.approve(app.id, grants.filter { scope -> manifest.capabilities.none { it.scope == scope && it.name in host.bans } }.toSet(), revision) else host.setGrants(app.id, grants); close() }) { Text(if (pending) "Grant selected & launch" else "Save grants") } }, dismissButton = { Row { if (pending) TextButton(onClick = { host.discard(app.id); close() }) { Text("Discard") }; TextButton(onClick = close) { Text("Later") } } })
        inspected?.let { source -> AlertDialog(onDismissRequest = { inspected = null }, title = { Text("Source preview · first 12,000 characters") }, text = { Text(source, Modifier.verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }, confirmButton = { TextButton(onClick = { inspected = null }) { Text("Close") } }) }
    }
}
