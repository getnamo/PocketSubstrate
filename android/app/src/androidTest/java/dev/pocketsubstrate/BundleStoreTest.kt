package dev.pocketsubstrate

import android.content.ContextWrapper
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import dev.pocketsubstrate.bundles.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class BundleStoreTest {
    private fun envelope(id: String, revision: Int, extraPath: String? = null): JSONObject {
        val manifest = JSONObject().put("schemaVersion", 1).put("apiVersion", 1).put("id", id).put("name", "Test").put("frontend", "web/index.html").put("backend", "backend/main.cjs").put("capabilities", JSONArray().put(JSONObject().put("name", "storage").put("reason", "Scores"))).put("revision", revision)
        val files = JSONObject().put("web/index.html", Base64.encodeToString("<html></html>".toByteArray(), Base64.NO_WRAP)).put("backend/main.cjs", Base64.encodeToString("module.exports={}".toByteArray(), Base64.NO_WRAP))
        if (extraPath != null) files.put(extraPath, "eA==")
        val bytes = JSONObject().put("manifest", manifest).put("files", files).toString().toByteArray()
        val digest = sha256(bytes)
        return JSONObject().put("protocol", 1).put("type", "publish").put("deliveryId", digest).put("digest", digest).put("payload", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }
    @Test fun stagingOwnershipRollbackRecoveryAndStorageIsolation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "store-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = folder }
        try {
            val store = BundleStore(isolated)
            val one = envelope("test-one", 1); val two = envelope("test-one", 2); val three = envelope("test-one", 3)
            store.stage(one, "publisher-a"); store.approve("test-one", setOf("storage")); store.confirm("test-one")
            store.storage("test-one", "key", "private", true)
            store.stage(envelope("test-two", 1), "publisher-b")
            assertEquals(JSONObject.NULL, store.storage("test-two", "key"))
            assertThrows(IllegalArgumentException::class.java) { store.stage(two, "publisher-b") }
            assertThrows(IllegalArgumentException::class.java) { store.stage(envelope("test-one", 2, "web/../../escape"), "publisher-a") }
            assertEquals(one.getString("digest"), store.app("test-one")!!.active)
            store.stage(two, "publisher-a")
            assertThrows(IllegalArgumentException::class.java) { store.approve("test-one", setOf("storage"), one.getString("digest")) }
            assertEquals(one.getString("digest"), store.app("test-one")!!.active)
            store.approve("test-one", setOf("storage"), two.getString("digest")); store.confirm("test-one")
            assertEquals(one.getString("digest"), store.rollback("test-one").active)
            assertEquals("private", store.storage("test-one", "key"))
            store.stage(three, "publisher-a"); store.approve("test-one", setOf("storage"))
            val reopened = BundleStore(isolated)
            assertTrue(reopened.recoverIncomplete())
            assertEquals(one.getString("digest"), reopened.app("test-one")!!.active)
            assertEquals(three.getString("digest"), reopened.app("test-one")!!.pending)
            reopened.setGrants("test-one", emptySet())
            assertTrue(reopened.app("test-one")!!.grants.isEmpty())
            assertEquals(one.getString("digest"), reopened.stage(one, "publisher-a").active)
            reopened.delete("test-one")
            assertNull(reopened.app("test-one"))
        } finally { folder.deleteRecursively() }
    }
}
