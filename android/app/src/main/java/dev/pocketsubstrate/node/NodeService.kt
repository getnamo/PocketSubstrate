package dev.pocketsubstrate.node

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

class NodeService : Service() {
    private val started = AtomicBoolean()
    external fun runNode(input: Int, output: Int, script: ByteArray): Int
    private val binder = object : INodeService.Stub() {
        override fun start(input: ParcelFileDescriptor, output: ParcelFileDescriptor, script: String) {
            check(started.compareAndSet(false, true)) { "Node already started" }
            val read = input.detachFd()
            val write = output.detachFd()
            Thread({
                try { runNode(read, write, script.toByteArray(Charsets.UTF_8)) }
                finally { Process.killProcess(Process.myPid()) }
            }, "embedded-node").start()
        }
        override fun stop() { Process.killProcess(Process.myPid()) }
    }
    override fun onBind(intent: Intent): IBinder = binder
    companion object {
        init { System.loadLibrary("node"); System.loadLibrary("substrate") }
    }
}
