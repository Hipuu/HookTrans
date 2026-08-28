package io.hooktrans.engine

import android.content.Context
import com.google.mlkit.common.sdkinternal.MlKitContext
import io.hooktrans.core.Logs

/**
 * Brings ML Kit up in a process it did not expect to be used from.
 *
 * ML Kit bootstraps itself from `MlKitInitProvider`, a ContentProvider declared in its own
 * manifest. Android instantiates providers only in an application's *default* process, and
 * everything here runs in `:engine` — so without this every `getClient` call fails with
 * `IllegalStateException: MlKitContext has not been initialized`.
 *
 * Both ML Kit users need it (translation and text recognition), and whichever one ran first
 * used to be the one that worked, so this lives in one place rather than in each of them.
 * [ensure] is idempotent and cheap after the first call.
 */
object MlKitInit {

    @Volatile
    private var done = false

    fun ensure(context: Context): Boolean {
        if (done) return true
        return synchronized(this) {
            if (done) return@synchronized true
            try {
                MlKitContext.initializeIfNeeded(context.applicationContext)
                done = true
                true
            } catch (t: Throwable) {
                Logs.w("ML Kit could not be initialized in this process", t)
                false
            }
        }
    }
}
