package io.hooktrans.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.hooktrans.core.Const
import io.hooktrans.core.Logs
import io.hooktrans.ipc.ITranslator
import java.util.concurrent.Executors

/**
 * The settings screen's connection to the :engine process.
 *
 * Deliberately separate from the hook-side [io.hooktrans.xposed.HostBridge]: this one may
 * block, because the calls it makes (self test, model download) are explicitly slow and the
 * user asked for them. It just keeps them off the main thread.
 */
class EngineClient {

    @Volatile
    private var service: ITranslator? = null

    @Volatile
    private var bound = false

    private var onReady: (() -> Unit)? = null

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ht-ui-engine").apply { isDaemon = true }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = ITranslator.Stub.asInterface(binder)
            onReady?.let { cb -> cb() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun connect(ctx: Context, ready: () -> Unit) {
        onReady = ready
        if (bound) return
        val intent = Intent(Const.ACTION_BIND).setComponent(
            ComponentName(ctx.packageName, Const.SERVICE_CLASS)
        )
        bound = runCatching {
            ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            Logs.w("could not bind engine from UI", it); false
        }
    }

    fun disconnect(ctx: Context) {
        if (!bound) return
        runCatching { ctx.unbindService(conn) }
        bound = false
        service = null
    }

    /** Runs [block] off the main thread, binding first if necessary. */
    fun run(ctx: Context, block: (ITranslator?) -> Unit) {
        connect(ctx) {}
        io.execute {
            // The bind is asynchronous; give it a moment rather than failing the first tap.
            var waited = 0
            while (service == null && waited < 3_000) {
                Thread.sleep(50)
                waited += 50
            }
            runCatching { block(service) }.onFailure { Logs.w("engine call failed", it) }
        }
    }

    /** Cached, non-blocking. Returns -1 when the engine has not connected yet. */
    fun cacheCount(): Long = try {
        service?.cacheCount() ?: -1L
    } catch (t: Throwable) {
        -1L
    }
}
