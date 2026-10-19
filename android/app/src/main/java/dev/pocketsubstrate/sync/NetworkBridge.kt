package dev.pocketsubstrate.sync

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dev.pocketsubstrate.ipc.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class NetworkBridge private constructor(private val context: Context) {
    private var ready = CompletableDeferred<INetworkService>()
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete(INetworkService.Stub.asInterface(binder)) }
        override fun onServiceDisconnected(name: ComponentName) { ready = CompletableDeferred() }
        override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(IllegalStateException("Network helper unavailable")) }
        override fun onBindingDied(name: ComponentName) { ready.completeExceptionally(IllegalStateException("Network helper stopped; reopen the host")) }
    }
    suspend fun service(): INetworkService = withContext(Dispatchers.Main) {
        if (!bound) {
            val intent = Intent().setComponent(ComponentName("dev.pocketsubstrate.network", "dev.pocketsubstrate.network.NetworkService"))
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            check(bound) { "Install the matching PocketSubstrate Network helper APK first" }
        }
        withTimeout(10000) { ready.await() }
    }
    suspend fun pair(link: String): String {
        val service = service()
        return withTimeout(20000) { suspendCancellableCoroutine { continuation ->
            service.pair(link, object : IReply.Stub() {
                override fun result(value: String, error: String) {
                    if (continuation.isActive) continuation.resumeWith(if (error.isEmpty()) Result.success(value) else Result.failure(IllegalStateException(error)))
                }
            })
        } }
    }
    suspend fun fetch(url: String, origins: List<String>): JSONObject {
        val service = service(); val id = UUID.randomUUID().toString()
        return withTimeout(15000) { suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { service.cancelFetch(id) } }
            service.fetch(id, url, JSONArray(origins).toString(), object : IReply.Stub() {
                override fun result(value: String, error: String) {
                    if (continuation.isActive) continuation.resumeWith(runCatching { check(error.isEmpty()) { error }; JSONObject(value) })
                }
            })
        } }
    }
    companion object {
        @Volatile private var instance: NetworkBridge? = null
        fun get(context: Context): NetworkBridge = instance ?: synchronized(this) { instance ?: NetworkBridge(context.applicationContext).also { instance = it } }
    }
}
