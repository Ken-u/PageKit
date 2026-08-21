package com.kenjc.pagekit.profile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ProfileWorkerClient(
    context: Context,
    val slot: Int,
) {
    private val appContext = context.applicationContext
    private val connectionMutex = Mutex()
    @Volatile private var remote: IProfileWorker? = null
    private var connection: ServiceConnection? = null

    suspend fun execute(requestJson: String): String = withContext(Dispatchers.IO) {
        var lastError: DeadObjectException? = null
        repeat(2) {
            try {
                return@withContext readResponse(connect().execute(requestJson))
            } catch (error: DeadObjectException) {
                lastError = error
                disconnect()
            }
        }
        throw requireNotNull(lastError)
    }

    suspend fun close() = connectionMutex.withLock {
        connection?.let { runCatching { appContext.unbindService(it) } }
        connection = null
        remote = null
    }

    private suspend fun disconnect() = connectionMutex.withLock {
        connection?.let { runCatching { appContext.unbindService(it) } }
        connection = null
        remote = null
    }

    private suspend fun connect(): IProfileWorker = connectionMutex.withLock {
        remote?.let { return@withLock it }
        suspendCancellableCoroutine { continuation ->
            val serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val worker = IProfileWorker.Stub.asInterface(binder)
                    remote = worker
                    if (continuation.isActive) continuation.resume(worker)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    remote = null
                }

                override fun onBindingDied(name: ComponentName) {
                    remote = null
                }

                override fun onNullBinding(name: ComponentName) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("profile worker returned a null binding"))
                    }
                }
            }
            connection = serviceConnection
            val bound = appContext.bindService(
                Intent(appContext, serviceClass(slot)),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound && continuation.isActive) {
                connection = null
                continuation.resumeWithException(IllegalStateException("failed to bind profile worker $slot"))
            }
            continuation.invokeOnCancellation {
                runCatching { appContext.unbindService(serviceConnection) }
                if (connection === serviceConnection) connection = null
            }
        }
    }

    private fun readResponse(descriptor: ParcelFileDescriptor): String = descriptor.use { fd ->
        FileInputStream(fd.fileDescriptor).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_RESPONSE_BYTES) { "profile worker response exceeds 8 MiB" }
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun serviceClass(slot: Int): Class<out ProfileWorkerService> = when (slot) {
        1 -> ProfileWorkerService1::class.java
        2 -> ProfileWorkerService2::class.java
        3 -> ProfileWorkerService3::class.java
        else -> error("invalid profile worker slot: $slot")
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    }
}
