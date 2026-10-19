package dev.pocketsubstrate.node

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.UUID
import java.util.concurrent.Executors

class NodeSession(private val context: Context, private val scope: CoroutineScope, private val message: (JSONObject) -> Unit, private val failed: (String) -> Unit) {
    private var service: INodeService? = null
    private var connection: ServiceConnection? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var startupFds = emptyList<ParcelFileDescriptor>()
    private val writes = Executors.newSingleThreadExecutor()
    @Volatile private var stopped = false
    fun start(script: String) {
        val input = ParcelFileDescriptor.createPipe()
        val output = ParcelFileDescriptor.createPipe()
        startupFds = listOf(input[0], output[1])
        writer = ParcelFileDescriptor.AutoCloseOutputStream(input[1]).bufferedWriter()
        reader = ParcelFileDescriptor.AutoCloseInputStream(output[0]).bufferedReader()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (stopped) { startupFds.forEach { runCatching { it.close() } }; return }
                service = INodeService.Stub.asInterface(binder)
                try { service!!.start(input[0], output[1], script) }
                catch (e: Exception) { failed("Node startup failed: ${e.message}") }
                finally { startupFds.forEach { runCatching { it.close() } }; startupFds = emptyList() }
            }
            override fun onServiceDisconnected(name: ComponentName) { if (!stopped) failed("Node process exited") }
            override fun onNullBinding(name: ComponentName) { if (!stopped) failed("Node service unavailable") }
        }
        connection = conn
        val name = "session" + UUID.randomUUID().toString().replace("-", "")
        check(context.bindIsolatedService(Intent(context, NodeService::class.java), Context.BIND_AUTO_CREATE, name, context.mainExecutor, conn))
        scope.launch(Dispatchers.IO) {
            try {
                var window = System.currentTimeMillis(); var messages = 0
                while (!stopped) {
                    val line = boundedLine(reader ?: break) ?: break
                    if (System.currentTimeMillis() - window > 1000) { window = System.currentTimeMillis(); messages = 0 }
                    require(++messages <= 300) { "Backend message rate exceeded" }
                    val json = JSONObject(line)
                    withContext(Dispatchers.Main) { if (!stopped) message(json) }
                }
                withContext(Dispatchers.Main) { if (!stopped) failed("Node output closed") }
            } catch (e: Exception) { withContext(Dispatchers.Main) { if (!stopped) failed("Node transport: ${e.message}") } }
        }
    }
    fun send(json: JSONObject) {
        if (stopped) return
        val line = json.toString()
        require(line.length <= 256 * 1024) { "Message too large" }
        writes.execute {
            try { if (!stopped) { writer?.write(line); writer?.newLine(); writer?.flush() } }
            catch (_: Exception) { scope.launch(Dispatchers.Main) { if (!stopped) failed("Node input closed") } }
        }
    }
    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { service?.stop() }
        startupFds.forEach { runCatching { it.close() } }; startupFds = emptyList()
        connection?.let { runCatching { context.unbindService(it) } }; connection = null
        // Service termination closes the other ends and releases blocked pipe IO.
        // Cleanup must survive cancellation of the runtime coroutine scope.
        writes.execute { runCatching { writer?.close() }; runCatching { reader?.close() } }
        writes.shutdown()
    }
    private fun boundedLine(reader: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val ch = reader.read()
            if (ch < 0) return if (line.isEmpty()) null else line.toString()
            if (ch == 10) return line.toString()
            require(line.length < 65536) { "Backend message exceeds 64 KiB" }
            line.append(ch.toChar())
        }
    }
}
