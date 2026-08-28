package io.hooktrans.xposed

import android.content.res.Resources
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.hooktrans.core.HookConfig
import io.hooktrans.core.Logs
import io.hooktrans.core.Lru
import io.hooktrans.core.TextGuard

/**
 * Opt-in, and off by default.
 *
 * Hooking `Resources.getString` catches text the rendering hooks never see: menu titles,
 * notification copy, accessibility descriptions, strings the app puts straight into a
 * Bundle. It is also the single most dangerous place to translate, because an app is free to
 * keep URLs, keys, analytics event names and switch-statement labels in strings.xml.
 *
 * Two rules keep it survivable: the value is only ever swapped when the translation is
 * already cached — so nothing here can block or stall — and TextGuard's data heuristics run
 * first. It stays off unless a user turns it on for an app that needs it.
 */
object ResourceHooks {

    private lateinit var cfg: HookConfig
    private val verdicts = Lru<String, Boolean>(4_000)

    fun install(config: HookConfig) {
        cfg = config

        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    when (val res = param.result) {
                        is String -> substitute(res)?.let { param.result = it }
                        is CharSequence -> {
                            // getText() carries styled markup from <string> resources with
                            // HTML in them. A plain-String replacement would drop the spans,
                            // so leave styled text to the TextView hook, which preserves them.
                            if (res is android.text.Spanned &&
                                res.getSpans(0, res.length, Any::class.java).isNotEmpty()
                            ) return
                            substitute(res.toString())?.let { param.result = it }
                        }
                        else -> {}
                    }
                } catch (t: Throwable) {
                    Logs.d("resource hook: ${t.message}")
                }
            }
        }

        // Resources.getString/getText only. Context.getString and the AppCompat wrappers all
        // delegate here, so hooking them too would run substitute() twice over the same read
        // — and the second pass would feed the *translated* string back into the queue as if
        // it were fresh source text, doubling engine traffic for every resource string.
        listOf("getString", "getText").forEach { m ->
            runCatching { XposedBridge.hookAllMethods(Resources::class.java, m, hook) }
        }
        Logs.i("resource hooks installed (aggressive mode)")
    }

    private fun substitute(source: String): String? {
        if (!allowed(source)) return null
        val hit = HostBridge.peek(source)
        if (hit == null) {
            // Warm the cache so the next read — or the next launch — is translated.
            HostBridge.request(source) {}
            return null
        }
        return if (hit == source) null else hit
    }

    private fun allowed(source: String): Boolean {
        verdicts[source]?.let { return it }
        var ok = TextGuard.shouldTranslate(source, cfg)
        if (ok && cfg.maxCompatibility) ok = source.trim().any { it.isWhitespace() }
        if (ok && TextGuard.looksLikeTargetScript(source, HostBridge.dstLang)) ok = false
        verdicts[source] = ok
        return ok
    }
}
