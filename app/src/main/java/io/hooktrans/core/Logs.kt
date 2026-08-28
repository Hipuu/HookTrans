package io.hooktrans.core

import android.util.Log

/**
 * Logging that works identically in the module's own process and inside a hooked app.
 * XposedBridge is resolved reflectively so this file can also be used by the plain app
 * code, where the Xposed classes are absent.
 */
object Logs {

    @Volatile
    var verbose: Boolean = false

    private val xposedLog: java.lang.reflect.Method? by lazy {
        runCatching {
            Class.forName("de.robv.android.xposed.XposedBridge")
                .getDeclaredMethod("log", String::class.java)
        }.getOrNull()
    }

    fun d(msg: String) {
        if (!verbose) return
        Log.d(Const.TAG, msg)
        runCatching { xposedLog?.invoke(null, "[HookTrans] $msg") }
    }

    fun i(msg: String) {
        Log.i(Const.TAG, msg)
        runCatching { xposedLog?.invoke(null, "[HookTrans] $msg") }
    }

    fun w(msg: String, t: Throwable? = null) {
        Log.w(Const.TAG, msg, t)
        runCatching { xposedLog?.invoke(null, "[HookTrans][W] $msg ${t?.stackTraceToString() ?: ""}") }
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(Const.TAG, msg, t)
        runCatching { xposedLog?.invoke(null, "[HookTrans][E] $msg ${t?.stackTraceToString() ?: ""}") }
    }
}
