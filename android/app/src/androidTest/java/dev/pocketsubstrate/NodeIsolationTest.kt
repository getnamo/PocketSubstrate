package dev.pocketsubstrate

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.pocketsubstrate.node.INodeService
import dev.pocketsubstrate.node.NodeService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class NodeIsolationTest {
    @Test fun embeddedNodeRunsInIsolatedProcessAndCanRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secret = File(context.filesDir, "isolation-secret").apply { writeText("private") }
        repeat(3) {
            val connected = CompletableFuture<INodeService>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.complete(INodeService.Stub.asInterface(binder)) }
                override fun onServiceDisconnected(name: ComponentName) {}
            }
            assertTrue(context.bindIsolatedService(Intent(context, NodeService::class.java), Context.BIND_AUTO_CREATE, "probe${UUID.randomUUID().toString().replace("-", "")}", context.mainExecutor, connection))
            val input = ParcelFileDescriptor.createPipe()
            val output = ParcelFileDescriptor.createPipe()
            try {
                val service = connected.get(15, TimeUnit.SECONDS)
                val exited = CompletableFuture<Boolean>()
                service.asBinder().linkToDeath({ exited.complete(true) }, 0)
                val script = """
                    const fs = require('fs');
                    let fileDenied = false;
                    try { fs.readFileSync(${JSONObject.quote(secret.path)}) } catch(e) { fileDenied = true }
                    const spawn = require('child_process').spawnSync('/system/bin/sh',['-c','true']);
                    const shellDenied = spawn.error?.code === 'EPERM' || spawn.error?.code === 'EACCES';
                    let addonDenied = false;
                    try { process.dlopen(module, '/missing-addon.node'); } catch(e) { addonDenied = e.code === 'ERR_DLOPEN_DISABLED'; }
                    const net = require('net');
                    const socket = net.connect({host:'1.1.1.1', port:443});
                    socket.on('error', e => {
                        console.log(JSON.stringify({unicode:'Orbit 🌍',node:process.versions.node,uid:process.getuid(),fileDenied,shellDenied,addonDenied,networkDenied:e.code === 'EACCES' || e.code === 'EPERM'}));
                        setInterval(()=>{},1000);
                    });
                """.trimIndent()
                service.start(input[0], output[1], script)
                input[0].close(); output[1].close()
                val line = CompletableFuture.supplyAsync { ParcelFileDescriptor.AutoCloseInputStream(output[0]).bufferedReader().readLine() }.get(20, TimeUnit.SECONDS)
                val result = JSONObject(line)
                assertEquals("18.20.4", result.getString("node"))
                assertEquals("Orbit 🌍", result.getString("unicode"))
                assertNotEquals(android.os.Process.myUid(), result.getInt("uid"))
                assertTrue(result.toString(), result.getBoolean("fileDenied"))
                assertTrue(result.toString(), result.getBoolean("networkDenied"))
                assertTrue(result.toString(), result.getBoolean("shellDenied"))
                assertTrue(result.toString(), result.getBoolean("addonDenied"))
                runCatching { service.stop() }
                assertTrue(exited.get(5, TimeUnit.SECONDS))
            } finally {
                input.forEach { runCatching { it.close() } }; output.forEach { runCatching { it.close() } }
                context.unbindService(connection)
            }
        }
        secret.delete()
    }
}
