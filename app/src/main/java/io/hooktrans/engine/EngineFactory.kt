package io.hooktrans.engine

import android.content.Context
import io.hooktrans.core.Engines
import io.hooktrans.core.HookConfig
import io.hooktrans.core.Logs

object EngineFactory {

    fun create(cfg: HookConfig, context: Context): TranslationEngine = try {
        when (cfg.engine) {
            // ML Kit needs a Context: it runs in :engine, where its own init provider never
            // fires, so it has to be started explicitly.
            Engines.MLKIT -> MlKitEngine(context.applicationContext)
            Engines.LIBRE -> LibreTranslateEngine(cfg.endpoint, cfg.apiKey)
            Engines.DEEPL -> DeepLEngine(cfg.apiKey)
            Engines.MYMEMORY -> MyMemoryEngine()
            else -> GoogleFreeEngine()
        }
    } catch (t: Throwable) {
        Logs.e("engine '${cfg.engine}' unavailable, falling back to the free Google endpoint", t)
        GoogleFreeEngine()
    }

    /** Signature that decides whether a live engine must be rebuilt after a config change. */
    fun signature(cfg: HookConfig): String = "${cfg.engine}|${cfg.endpoint}|${cfg.apiKey.hashCode()}"
}
