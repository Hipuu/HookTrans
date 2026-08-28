package io.hooktrans.xposed

import android.os.Handler
import android.os.Looper
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.hooktrans.core.HookConfig
import io.hooktrans.core.Logs
import io.hooktrans.core.Lru
import io.hooktrans.core.TextGuard
import java.lang.ref.WeakReference
import java.util.Collections

/**
 * Jetpack Compose draws text without ever creating a TextView, so it needs its own hook.
 *
 * The interception point is the paragraph intrinsics constructor: every piece of Compose
 * text — `Text`, `BasicText`, text inside Material components — is measured through it, and
 * it receives the plain string. Substituting there is both complete and cheap.
 *
 * Compose has no equivalent of `TextView.setText` to call back into, so a late-arriving
 * translation cannot push itself onto the screen. Instead the known Compose roots are asked
 * to re-measure. In practice the first sighting of a string may render untranslated for a
 * moment, and every sighting after that is served from cache instantly, including across
 * app restarts.
 */
object ComposeHooks {

    private lateinit var cfg: HookConfig
    private val main = Handler(Looper.getMainLooper())
    private val verdicts = Lru<String, Boolean>(4_000)
    private val roots = Collections.synchronizedList(ArrayList<WeakReference<View>>())

    @Volatile
    private var invalidationQueued = false

    @Volatile
    private var installed = false

    /** Spread over roughly ten seconds: long enough for a split to attach, short enough to stop. */
    private val RETRY_DELAYS_MS = longArrayOf(400, 800, 1_500, 3_000, 4_000)

    fun install(config: HookConfig, loader: ClassLoader) {
        cfg = config
        if (tryInstall(loader)) return

        // Compose's classes often are not resolvable yet at load-package time: they can live in
        // a dynamic feature split or a secondary dex the app only installs once its Application
        // has run. Coolapk is one of these — the class is in the APK but findClass fails this
        // early, which used to be reported as "Compose not present" and cost the whole Compose
        // UI. Retry a few times before believing it.
        retry(loader, 0)
    }

    private fun retry(loader: ClassLoader, attempt: Int) {
        if (attempt >= RETRY_DELAYS_MS.size) {
            Logs.d("Compose not present in this app")
            return
        }
        main.postDelayed({
            if (!tryInstall(loader)) retry(loader, attempt + 1)
        }, RETRY_DELAYS_MS[attempt])
    }

    /** True once the text funnel is hooked; false while the Compose classes are still absent. */
    private fun tryInstall(loader: ClassLoader): Boolean {
        if (installed) return true

        val intrinsics = listOf(
            "androidx.compose.ui.text.platform.AndroidParagraphIntrinsics",
            "androidx.compose.ui.text.AndroidParagraphIntrinsics",
        ).firstNotNullOfOrNull { name ->
            runCatching { XposedHelpers.findClass(name, loader) }.getOrNull()
        } ?: return false

        var hooked = 0
        intrinsics.declaredConstructors.forEach { ctor ->
            val idx = ctor.parameterTypes.indexOfFirst { it == String::class.java }
            if (idx < 0) return@forEach
            runCatching {
                XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val src = param.args[idx] as? String ?: return
                        val out = lookup(src) ?: return
                        param.args[idx] = out
                    }
                })
                hooked++
            }
        }
        if (hooked == 0) return false

        // Track the Compose roots so a late translation can trigger a re-measure.
        runCatching {
            val ownerClass = XposedHelpers.findClass("androidx.compose.ui.platform.AndroidComposeView", loader)
            XposedBridge.hookAllConstructors(ownerClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    synchronized(roots) {
                        roots.removeAll { it.get() == null }
                        if (roots.size < 32) roots.add(WeakReference(v))
                    }
                }
            })
        }

        installed = true
        Logs.i("Compose hooks installed ($hooked constructor(s))")
        // Views composed before the hook landed already hold untranslated text, so ask the
        // roots that exist by now to re-measure.
        scheduleInvalidate()
        return true
    }

    private fun lookup(src: String): String? {
        if (!allowed(src)) return null
        val hit = HostBridge.peek(src)
        if (hit != null) return if (hit == src) null else hit
        HostBridge.request(src) { scheduleInvalidate() }
        return null
    }

    private fun allowed(source: String): Boolean {
        verdicts[source]?.let { return it }
        var ok = TextGuard.shouldTranslate(source, cfg)
        if (ok && cfg.maxCompatibility) ok = source.trim().any { it.isWhitespace() }
        if (ok && TextGuard.looksLikeTargetScript(source, HostBridge.dstLang)) ok = false
        verdicts[source] = ok
        return ok
    }

    /** Coalesced: a batch of arriving translations causes one re-measure, not one each. */
    private fun scheduleInvalidate() {
        if (invalidationQueued) return
        invalidationQueued = true
        main.postDelayed({
            invalidationQueued = false
            val live = synchronized(roots) { roots.mapNotNull { it.get() } }
            live.forEach { v ->
                runCatching {
                    // Compose skips re-measure unless the node is marked dirty, so ask the
                    // delegate directly and fall back to the plain View path.
                    val delegate = XposedHelpers.getObjectField(v, "measureAndLayoutDelegate")
                    val root = XposedHelpers.getObjectField(v, "root")
                    XposedHelpers.callMethod(
                        delegate, "requestRemeasure",
                        arrayOf(root.javaClass, java.lang.Boolean.TYPE), root, true
                    )
                }
                runCatching { v.requestLayout(); v.invalidate() }
            }
        }, 120L)
    }
}
