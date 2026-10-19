package dev.pocketsubstrate

import dev.pocketsubstrate.bundles.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    private val manifest = Manifest.parse(JSONObject("""{"schemaVersion":1,"apiVersion":1,"id":"test-app","name":"Test","frontend":"web/index.html","backend":"backend/main.cjs","capabilities":[{"name":"storage","reason":"Save scores"},{"name":"network","reason":"Read API","origins":["https://example.com"]}]}"""))
    @Test fun permissionIsIntersectionOfDeclarationGrantAndGlobalPolicy() {
        Policy.check(manifest, setOf("storage"), emptySet(), "storage")
        assertEquals("PERMISSION_DENIED", assertThrows(SubstrateException::class.java) { Policy.check(manifest, emptySet(), emptySet(), "storage") }.code)
        assertEquals("GLOBALLY_BLOCKED", assertThrows(SubstrateException::class.java) { Policy.check(manifest, setOf("storage"), setOf("storage"), "storage") }.code)
        assertThrows(SubstrateException::class.java) { Policy.check(manifest, setOf("location"), emptySet(), "location") }
    }
    @Test fun broaderNetworkScopeDoesNotReuseOldGrant() {
        val grant = manifest.capabilities.first { it.name == "network" }.scope
        Policy.check(manifest, setOf(grant), emptySet(), "network")
        val expanded = manifest.copy(capabilities = listOf(Capability("network", "API", listOf("https://example.com", "https://other.example"))))
        assertThrows(SubstrateException::class.java) { Policy.check(expanded, setOf(grant), emptySet(), "network") }
    }
    @Test fun traversalAndReservedSdkNamesAreRejected() {
        listOf("../x", "web/../x", "/web/a", "web//a", "web\\a", "web/__substrate.js").forEach { assertFalse(it, BundleStore.validPath(it)) }
        assertTrue(BundleStore.validPath("web/app.js"))
    }
}
