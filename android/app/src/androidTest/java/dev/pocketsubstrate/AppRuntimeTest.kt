package dev.pocketsubstrate

import androidx.test.core.app.ActivityScenario
import dev.pocketsubstrate.bundles.*
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramSocket
import java.net.SocketException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class AppRuntimeTest {
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        while (!condition()) { if (System.currentTimeMillis() > deadline) fail("Condition timed out"); Thread.sleep(100) }
    }
    private fun eval(scenario: ActivityScenario<MainActivity>, script: String): String {
        val result = CompletableFuture<String>()
        scenario.onActivity { activity -> activity.controller.runtime!!.webView.evaluateJavascript(script) { result.complete(it) } }
        return result.get(5, TimeUnit.SECONDS)
    }
    private fun openGame(scenario: ActivityScenario<MainActivity>) {
        waitUntil { var exists = false; scenario.onActivity { exists = it.controller.store.app("sample-touch-game") != null }; exists }
        scenario.onActivity {
            val host = it.controller
            host.store.setBan("storage", false)
            val app = host.store.app("sample-touch-game")!!
            if (app.pending != null) host.approve(app.id, setOf("storage", "haptics"))
            else { host.store.setGrants(app.id, setOf("storage", "haptics")); host.launch(app.id) }
        }
        waitUntil { var ready = false; var error = ""; scenario.onActivity { ready = it.controller.runtimeStatus.startsWith("Running"); error = it.controller.notice }; if (error.contains("failed", true)) fail(error); ready }
    }
    @Test fun webCallsRealNodeAndBrokerAndDeniesUndeclaredCapabilities() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openGame(scenario)
            eval(scenario, """(async()=>{let denied;try{await substrate.location.current()}catch(e){denied=e.code}window.__probe={state:await substrate.backend.call('state'),tap:await substrate.backend.call('tap'),denied};})().catch(e=>window.__probe={error:e.message,code:e.code})""")
            waitUntil { eval(scenario, "window.__probe !== undefined") == "true" }
            val json = JSONObject(JSONTokener(eval(scenario, "JSON.stringify(window.__probe)")).nextValue() as String)
            assertFalse(json.toString(), json.has("error"))
            assertTrue(json.getJSONObject("state").getString("runtime").startsWith("Node 18.20.4"))
            assertEquals(10, json.getJSONObject("tap").getInt("score"))
            assertEquals("PERMISSION_DENIED", json.getString("denied"))
            scenario.onActivity { it.controller.exitApp() }
        }
    }
    @Test fun webNetworkRoutesCannotBypassOfflinePolicy() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openGame(scenario)
            assertThrows(SocketException::class.java) { DatagramSocket() }
            try {
                eval(scenario, """(async()=>{
                    const r={};
                    try{await fetch('https://example.com');r.fetch=false}catch{r.fetch=true}
                    r.worker=await new Promise(resolve=>{try{const w=new Worker('/app.js');w.onerror=()=>{w.terminate();resolve(true)};setTimeout(()=>{w.terminate();resolve(false)},1000)}catch{resolve(true)}});
                    r.socket=await new Promise(resolve=>{try{const s=new WebSocket('wss://example.com');s.onerror=()=>resolve(true);s.onopen=()=>{s.close();resolve(false)};setTimeout(()=>{s.close();resolve(true)},1000)}catch{resolve(true)}});
                    r.candidates=0;
                    try { const pc=new RTCPeerConnection({iceServers:[{urls:'stun:127.0.0.1:3478'}]});pc.onicecandidate=e=>{if(e.candidate)r.candidates++};pc.createDataChannel('probe');await pc.setLocalDescription(await pc.createOffer());await new Promise(resolve=>setTimeout(resolve,3000));pc.close(); } catch {}
                    window.__network=r;
                })()""")
                waitUntil { eval(scenario, "window.__network !== undefined") == "true" }
                val json = JSONObject(JSONTokener(eval(scenario, "JSON.stringify(window.__network)")).nextValue() as String)
                assertTrue(json.toString(), json.getBoolean("fetch")); assertTrue(json.toString(), json.getBoolean("worker")); assertTrue(json.toString(), json.getBoolean("socket"))
                assertEquals("WebRTC must not obtain network candidates", 0, json.getInt("candidates"))
            } finally { scenario.onActivity { it.controller.exitApp() } }
        }
    }
    @Test fun revocationAndGlobalBanAffectFrontendAndNode() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openGame(scenario)
            scenario.onActivity { host -> host.controller.store.storage("sample-touch-game", "best", 99, true); host.controller.setBan("storage", true) }
            waitUntil { var ready = false; scenario.onActivity { ready = it.controller.runtimeStatus.startsWith("Running") }; ready }
            eval(scenario, """(async()=>{let denied;try{await substrate.storage.get('best')}catch(e){denied=e.code}window.__ban={denied,state:await substrate.backend.call('state')};})()""")
            waitUntil { eval(scenario, "window.__ban !== undefined") == "true" }
            val result = JSONObject(JSONTokener(eval(scenario, "JSON.stringify(window.__ban)")).nextValue() as String)
            assertEquals("GLOBALLY_BLOCKED", result.getString("denied"))
            assertEquals(0, result.getJSONObject("state").getInt("best"))
            scenario.onActivity { it.controller.setBan("storage", false); it.controller.setGrants("sample-touch-game", emptySet()) }
            waitUntil { var ready = false; scenario.onActivity { ready = it.controller.runtimeStatus.startsWith("Running") }; ready }
            eval(scenario, "substrate.storage.get('best').catch(e=>window.__denied=e.code)")
            waitUntil { eval(scenario, "window.__denied !== undefined") == "true" }
            assertEquals("\"PERMISSION_DENIED\"", eval(scenario, "window.__denied"))
            scenario.onActivity { it.controller.exitApp() }
        }
    }
    @Test fun failedBackendsAndFrontendReturnCandidateToReview() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openGame(scenario)
            var active = ""
            scenario.onActivity { active = it.controller.store.app("sample-touch-game")!!.active!!; it.controller.exitApp() }
            for (failure in listOf("crash", "hang", "frontend")) {
                scenario.onActivity { activity ->
                    val envelope = JSONObject(activity.assets.open("examples/touch-game.psub").bufferedReader().use { it.readText() })
                    val body = JSONObject(String(android.util.Base64.decode(envelope.getString("payload"), android.util.Base64.DEFAULT)))
                    val files = body.getJSONObject("files")
                    if (failure == "frontend") files.put("web/app.js", android.util.Base64.encodeToString("throw new Error('broken frontend');".toByteArray(), android.util.Base64.NO_WRAP))
                    else files.put("backend/main.cjs", android.util.Base64.encodeToString((if (failure == "hang") "while(true){}" else "throw new Error('broken backend');").toByteArray(), android.util.Base64.NO_WRAP))
                    val bytes = body.toString().toByteArray(); val digest = sha256(bytes)
                    envelope.put("digest", digest).put("deliveryId", digest).put("payload", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    activity.controller.store.stage(envelope, "bundled-examples")
                    activity.controller.approve("sample-touch-game", setOf("storage", "haptics"))
                }
                waitUntil { var failed = false; scenario.onActivity { failed = it.controller.runningId == null && it.controller.store.app("sample-touch-game")!!.pending != null }; failed }
                scenario.onActivity {
                    assertEquals("Previous revision must survive $failure", active, it.controller.store.app("sample-touch-game")!!.active)
                    it.controller.discard("sample-touch-game")
                    it.controller.launch("sample-touch-game")
                }
                waitUntil { var running = false; scenario.onActivity { running = it.controller.runtimeStatus.startsWith("Running") }; running }
                scenario.onActivity { it.controller.exitApp() }
            }
        }
    }
}
