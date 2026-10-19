package dev.pocketsubstrate

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PublisherE2eTest {
    private fun await(condition: () -> Boolean) { val deadline = System.currentTimeMillis() + 25000; while (!condition()) { if (System.currentTimeMillis() > deadline) fail("E2E condition timed out"); Thread.sleep(100) } }
    @Test fun publisherRoundTrip() {
        val args = InstrumentationRegistry.getArguments()
        val link = args.getString("pairingLink"); assumeNotNull(link)
        val first = args.getString("firstRevision")!!; val second = args.getString("secondRevision")!!; val third = args.getString("thirdRevision")!!
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val host = activity.controller
                host.exitApp(); host.unpair()
                if (host.store.app("e2e-game") != null) host.delete("e2e-game")
                host.pair(link!!)
            }
            await { var found = false; scenario.onActivity { found = it.controller.store.app("e2e-game")?.pending == first }; found }
            scenario.onActivity { it.controller.setLive("e2e-game", true); it.controller.approve("e2e-game", setOf("storage", "haptics", "motion")) }
            await { var found = false; scenario.onActivity { val app = it.controller.store.app("e2e-game"); found = app?.active == second && app.pending == third && it.controller.runtimeStatus.startsWith("Running") }; found }
            scenario.onActivity {
                val app = it.controller.store.app("e2e-game")!!
                assertFalse(app.grants.contains("location"))
                assertEquals(first, app.previous)
                it.controller.rollback(app.id)
            }
            await { var ready = false; scenario.onActivity { ready = it.controller.runtimeStatus.startsWith("Running") && it.controller.store.app("e2e-game")?.active == first }; ready }
            val result = CompletableFuture<String>()
            scenario.onActivity { it.controller.runtime!!.webView.evaluateJavascript("substrate.backend.call('state').then(value=>window.__state=value)", null) }
            await {
                val value = CompletableFuture<String>()
                scenario.onActivity { it.controller.runtime!!.webView.evaluateJavascript("JSON.stringify(window.__state || null)") { text -> value.complete(text) } }
                val raw = value.get(5, TimeUnit.SECONDS)
                if (raw != "\"null\"") { result.complete(raw); true } else false
            }
            val state = JSONObject(JSONTokener(result.get()).nextValue() as String)
            assertEquals(10, state.getInt("pointsPerTap"))
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { assertNull(it.controller.runtime) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            await { var ready = false; scenario.onActivity { ready = it.controller.runtimeStatus.startsWith("Running") && it.controller.runtime != null }; ready }
            scenario.recreate()
            scenario.onActivity { assertEquals(first, it.controller.store.app("e2e-game")?.active); assertEquals(third, it.controller.store.app("e2e-game")?.pending); it.controller.unpair() }
        }
    }
}
