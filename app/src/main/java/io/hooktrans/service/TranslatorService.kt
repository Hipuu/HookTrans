package io.hooktrans.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import io.hooktrans.R
import io.hooktrans.core.Const
import io.hooktrans.core.Logs
import io.hooktrans.ipc.IOcrCallback
import io.hooktrans.ipc.ITranslateCallback
import io.hooktrans.ipc.ITranslator
import io.hooktrans.ipc.TextRegion

/**
 * The translation back end. Runs in its own process so a crash in an engine, or the memory
 * a downloaded ML Kit model needs, never touches the UI process. Hooked apps bind here
 * instead of carrying an engine of their own.
 */
class TranslatorService : Service() {

    private lateinit var repo: TranslationRepo
    private lateinit var ocrRepo: OcrRepo

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Logs.i("config changed, reloading")
            repo.reloadConfig()
        }
    }

    override fun onCreate() {
        super.onCreate()
        repo = TranslationRepo(this)
        ocrRepo = OcrRepo(this, repo) { repo.config() }
        val filter = IntentFilter(Const.ACTION_CONFIG_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(configReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(configReceiver, filter)
        }
        startForegroundSafely()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(configReceiver) }
        runCatching { ocrRepo.close() }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()
        // NOT_STICKY on purpose: the engine is an on-demand process. It exists while a hooked
        // app holds a binding and dies with the last one; if the system kills it, the next
        // bind recreates it. STICKY here would resurrect it forever, which is exactly the
        // always-in-background behaviour this build removed.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startForegroundSafely() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, getString(R.string.channel_engine), NotificationManager.IMPORTANCE_MIN)
                        .apply { setShowBadge(false) }
                )
            }
            val n: Notification = Notification.Builder(this, CHANNEL)
                .setContentTitle(getString(R.string.engine_running))
                .setContentText(getString(R.string.engine_running_sub))
                .setSmallIcon(R.drawable.ic_translate)
                .setOngoing(true)
                .build()
            startForeground(NOTIF_ID, n)
        } catch (t: Throwable) {
            Logs.w("could not enter foreground", t)
        }
    }

    private val binder = object : ITranslator.Stub() {

        override fun apiVersion(): Int = Const.API_VERSION

        /**
         * The config a hooked app is allowed to see. Credentials are stripped: a hooked app
         * needs to know *what* to translate and into which language, never how the engine
         * authenticates. The module's own UI gets the whole thing.
         */
        override fun configFor(packageName: String?): String = try {
            val cfg = repo.config()
            val caller = callerPackage(packageName)
            if (isOwnCaller()) cfg.toJson()
            else {
                requireInScope(caller)
                cfg.copy(apiKey = "", endpoint = "").toJson()
            }
        } catch (t: SecurityException) {
            throw t
        } catch (t: Throwable) {
            io.hooktrans.core.HookConfig().toJson()
        }

        override fun lookupCached(texts: Array<out String>?, dstLang: String?): Array<String?> {
            val list = texts?.toList().orEmpty()
            if (list.isEmpty()) return arrayOfNulls(0)
            if (!isOwnCaller()) requireInScope(callerPackage(null))
            return try {
                repo.cached(list, dstLang.orEmpty().ifBlank { repo.config().targetLang })
            } catch (t: Throwable) {
                Logs.w("lookupCached failed", t)
                arrayOfNulls(list.size)
            }
        }

        override fun translate(
            requestId: Int,
            texts: Array<out String>?,
            srcLang: String?,
            dstLang: String?,
            callerPackage: String?,
            speculative: Boolean,
            cb: ITranslateCallback?,
        ) {
            val sources = texts?.filterNotNull().orEmpty()
            if (sources.isEmpty() || cb == null) return
            val cfg = repo.config()
            if (!cfg.enabled) {
                runCatching { cb.onFailure(requestId, "module disabled") }
                return
            }
            // `translate` is oneway, so a thrown SecurityException would vanish; refuse
            // through the callback instead so the caller learns why nothing arrives.
            val pkg = callerPackage(callerPackage)
            if (!isOwnCaller() && !inScope(pkg)) {
                Logs.w("refusing translate from out-of-scope caller $pkg")
                runCatching { cb.onFailure(requestId, "caller not in translation scope") }
                return
            }
            val dst = dstLang.orEmpty().ifBlank { cfg.langFor(pkg.orEmpty()) }
            repo.submit(sources, dst, speculative) { results ->
                runCatching {
                    cb.onBatch(requestId, sources.toTypedArray(), results)
                }.onFailure { Logs.d("callback died for $pkg: ${it.message}") }
            }
        }

        override fun selfTest(text: String?, srcLang: String?, dstLang: String?): String {
            requireOwnCaller()
            val input = text.orEmpty().ifBlank { "Hello, how are you today?" }
            val cfg = repo.config()
            val dst = dstLang.orEmpty().ifBlank { cfg.targetLang }
            return try {
                val res = repo.translateBlocking(listOf(input), dst)
                res[0] ?: "engine returned nothing (check network / key / endpoint)"
            } catch (t: Throwable) {
                "error: ${t.message}"
            }
        }

        override fun downloadModel(srcLang: String?, dstLang: String?): String {
            requireOwnCaller()
            val cfg = repo.config()
            val src = srcLang.orEmpty().ifBlank { cfg.sourceLang }
            val dst = dstLang.orEmpty().ifBlank { cfg.targetLang }
            return try {
                (repo.engine() as? io.hooktrans.engine.MlKitEngine)?.ensureModel(src, dst)
                    ?: "Offline models only apply to the ML Kit engine"
            } catch (t: Throwable) {
                "error: ${t.message}"
            }
        }

        override fun cacheCount(): Long = try {
            repo.cacheCount()
        } catch (t: Throwable) {
            0L
        }

        override fun ocrCached(imageKey: String?, dstLang: String?): Array<TextRegion>? {
            if (imageKey.isNullOrBlank()) return null
            if (!isOwnCaller()) requireInScope(callerPackage(null))
            val dst = dstLang.orEmpty().ifBlank { repo.config().targetLang }
            return try {
                ocrRepo.cached(imageKey, dst)?.toTypedArray()
            } catch (t: Throwable) {
                Logs.w("ocrCached failed", t)
                null
            }
        }

        override fun recognize(
            requestId: Int,
            image: Bitmap?,
            imageKey: String?,
            dstLang: String?,
            callerPackage: String?,
            cb: IOcrCallback?,
        ) {
            if (cb == null) return
            if (image == null || imageKey.isNullOrBlank()) {
                runCatching { cb.onFailure(requestId, "no image") }
                return
            }
            val cfg = repo.config()
            if (!cfg.enabled || !cfg.hookImages) {
                runCatching { cb.onFailure(requestId, "image translation disabled") }
                return
            }
            // `recognize` is oneway, so a thrown SecurityException would vanish. Refuse through
            // the callback instead, exactly as translate() does.
            val pkg = callerPackage(callerPackage)
            if (!isOwnCaller() && !inScope(pkg)) {
                Logs.w("refusing recognize from out-of-scope caller $pkg")
                runCatching { cb.onFailure(requestId, "caller not in translation scope") }
                return
            }
            val dst = dstLang.orEmpty().ifBlank { cfg.langFor(pkg.orEmpty()) }
            ocrRepo.submit(image, imageKey, dst) { regions ->
                runCatching {
                    if (regions == null) cb.onFailure(requestId, "recognition failed")
                    else cb.onRegions(requestId, regions.toTypedArray())
                }.onFailure { Logs.d("ocr callback died for $pkg: ${it.message}") }
            }
        }

        override fun clearCache() {
            requireOwnCaller()
            repo.clearCache()
            ocrRepo.clearCache()
        }

        /**
         * The service is exported so hooked apps — which run under their own uid — can bind
         * it. That is required for translation, but the maintenance calls above are not
         * something an arbitrary app should be able to invoke, so they are restricted to the
         * module's own uid.
         */
        private fun requireOwnCaller() {
            if (!isOwnCaller()) {
                throw SecurityException(
                    "caller uid ${android.os.Binder.getCallingUid()} may not call module maintenance APIs"
                )
            }
        }

        private fun isOwnCaller(): Boolean {
            val caller = android.os.Binder.getCallingUid()
            return caller == android.os.Process.myUid() || caller == android.os.Process.SYSTEM_UID
        }

        /**
         * Resolves who is calling from the binder identity rather than trusting the package
         * name in the arguments. A caller can put anything in a String; it cannot fake its uid.
         * The declared name is accepted only when it is one of the packages sharing that uid.
         */
        private fun callerPackage(declared: String?): String? {
            val uid = android.os.Binder.getCallingUid()
            val names = runCatching { packageManager.getPackagesForUid(uid) }.getOrNull()
                ?: return null
            if (declared != null && names.contains(declared)) return declared
            return names.firstOrNull()
        }

        private fun inScope(pkg: String?): Boolean {
            if (pkg == null) return false
            val cfg = repo.config()
            // scopeAllApps means "every app LSPosed injected us into". A caller that is not
            // hooked cannot reach this service through the hook path, but it can still bind
            // directly, so an explicit package list is still honoured when one exists.
            return cfg.isPackageEnabled(pkg) || (cfg.scopeAllApps && cfg.enabled)
        }

        private fun requireInScope(pkg: String?) {
            if (!inScope(pkg)) {
                throw SecurityException("package $pkg is not in the translation scope")
            }
        }
    }

    companion object {
        private const val CHANNEL = "engine"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            val i = Intent(ctx, TranslatorService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }.onFailure { Logs.w("could not start engine service", it) }
        }
    }
}
