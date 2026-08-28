package io.hooktrans.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.hooktrans.core.HookConfig
import io.hooktrans.core.Logs
import io.hooktrans.core.Lru
import io.hooktrans.core.TextGuard
import org.json.JSONArray
import org.json.JSONObject

/**
 * Translates text when it is *loaded* rather than when it is displayed.
 *
 * Every other hook in this module is a render hook: it sees a string because that string is
 * about to be drawn. That is the correct place to *substitute*, but it makes the first sighting
 * of anything a cache miss, and it never sees the rest of the payload at all. A comment thread
 * arrives from the network in one response containing fifty replies; the recycler binds the
 * four that fit on screen, so only four are translated, and each new one pays a fresh round
 * trip as it scrolls into view.
 *
 * This hook watches the JSON parsers instead. Apps decode their responses through
 * `JSONObject.getString`/`optString` (or Gson's `JsonReader.nextString`), so hooking those
 * gives every string in the payload — on-screen, off-screen and not-yet-bound alike — while it
 * is still on a background thread.
 *
 * Nothing here substitutes anything. The parsed value is passed through completely untouched
 * and the string is only queued for translation, so the app's own data is never altered by
 * this hook; by the time the render hooks ask for it, the answer is already in cache and
 * appears instantly. Requests are queued at low priority so a fling still gets served first.
 */
object PrefetchHooks {

    private lateinit var cfg: HookConfig

    /**
     * Strings already offered to the queue. This is the load-bearing part of the hook: a parser
     * hook fires far more often than a render hook, and re-offering the same value costs a lock
     * and a guard evaluation every time. Sized generously — it holds keys, not translations.
     */
    private val seen = Lru<String, Boolean>(20_000)

    private val verdicts = Lru<String, Boolean>(8_000)

    @Volatile
    private var queued = 0

    @Volatile
    private var lastLog = 0L

    @Volatile
    private var installed = false

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val RETRY_DELAYS_MS = longArrayOf(400, 800, 1_500, 3_000, 4_000)

    /** Deep enough for a real API response, shallow enough to stay cheap. */
    private const val MAX_DEPTH = 12

    /**
     * Ceiling on strings taken from one payload. A feed response can hold thousands; the user
     * will reach a few dozen of them before the next request replaces the list.
     */
    private const val MAX_STRINGS_PER_PAYLOAD = 400

    fun install(config: HookConfig, loader: ClassLoader) {
        cfg = config
        if (tryInstall(loader)) return
        // Same story as ComposeHooks: a JSON library can live in a split or a secondary dex that
        // is not attached yet at load-package time, and giving up on the first miss costs the
        // whole payload. Retry before concluding the app does not parse JSON.
        retry(loader, 0)
    }

    private fun retry(loader: ClassLoader, attempt: Int) {
        if (attempt >= RETRY_DELAYS_MS.size) {
            Logs.d("no JSON parser found to prefetch from")
            return
        }
        main.postDelayed({
            if (!tryInstall(loader)) retry(loader, attempt + 1)
        }, RETRY_DELAYS_MS[attempt])
    }

    private fun tryInstall(loader: ClassLoader): Boolean {
        var sites = 0

        // org.json is in the framework, so this covers every app that parses its own responses
        // and every library that leans on it. getString throws on a missing key and optString
        // does not; both are used constantly and both hand back display copy.
        runCatching {
            val json = XposedHelpers.findClass("org.json.JSONObject", loader)
            for (name in arrayOf("getString", "optString")) {
                runCatching {
                    XposedBridge.hookAllMethods(json, name, reader)
                    sites++
                }
            }
        }

        // The whole-payload hook, and the reason this feature can claim "everything loaded":
        // reading fields one at a time only ever sees what the app asks for, but the string
        // handed to this constructor contains the entire response — every reply in a thread,
        // including the ones no adapter has touched yet.
        for (cls in arrayOf("org.json.JSONObject", "org.json.JSONArray")) {
            runCatching {
                val c = XposedHelpers.findClass(cls, loader)
                XposedBridge.hookAllConstructors(c, wholePayload)
                sites++
            }
        }

        // Gson streams: nextString is the funnel every @SerializedName String field passes
        // through, including inside arrays the app has not looked at yet.
        for (name in arrayOf(
            "com.google.gson.stream.JsonReader",
            "com.alibaba.fastjson.parser.JSONLexerBase",
        )) {
            runCatching {
                val cls = XposedHelpers.findClass(name, loader)
                runCatching {
                    XposedBridge.hookAllMethods(cls, "nextString", reader)
                    sites++
                }
            }
        }

        if (sites == 0) return false
        if (!installed) {
            installed = true
            Logs.i("prefetch hooks installed ($sites parser entry point(s))")
        }
        return true
    }

    /**
     * Reads a freshly constructed JSON container and offers every string inside it.
     *
     * Runs on whichever background thread the app parses on, and only for a container built from
     * text: `JSONObject(String)` is the top of a parse, while the `Map`/`JSONTokener` forms are
     * the inner nodes it creates on the way down, which the walk reaches anyway.
     *
     * The re-entrancy guard is not optional. Parsing a nested object constructs more
     * JSONObjects, each of which re-enters this hook, so an unguarded walk would re-traverse
     * every subtree once per level — quadratic on exactly the large payloads this exists for.
     */
    private val walking = ThreadLocal.withInitial { false }

    private val wholePayload = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (walking.get() == true) return
            try {
                if (param.args.size != 1 || param.args[0] !is String) return
                walking.set(true)
                walk(param.thisObject, 0, intArrayOf(0))
            } catch (t: Throwable) {
                // Never let a walk break the app's own parse.
            } finally {
                walking.set(false)
            }
        }
    }

    /**
     * Depth-first walk over a parsed container. [budget] is shared across the whole walk and
     * capped: a feed response can be enormous, and the point is to be ahead of the user, not to
     * translate a megabyte of it on the parse thread.
     */
    private fun walk(node: Any?, depth: Int, budget: IntArray) {
        if (depth > MAX_DEPTH || budget[0] >= MAX_STRINGS_PER_PAYLOAD) return
        when (node) {
            is String -> {
                budget[0]++
                offer(node)
            }
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    if (budget[0] >= MAX_STRINGS_PER_PAYLOAD) return
                    val k = keys.next()
                    walk(runCatching { node.get(k) }.getOrNull(), depth + 1, budget)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (budget[0] >= MAX_STRINGS_PER_PAYLOAD) return
                    walk(runCatching { node.get(i) }.getOrNull(), depth + 1, budget)
                }
            }
        }
    }

    /**
     * Reads the returned value and queues it. `afterHookedMethod` without touching
     * `param.result` is deliberate: this hook must be invisible to the app.
     */
    private val reader = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            // The walk reads fields through the same accessors this hook is on; offering them
            // twice would double the guard work on every payload.
            if (walking.get() == true) return
            try {
                offer(param.result as? String ?: return)
            } catch (t: Throwable) {
                // A parser hook that logged per failure would flood; silence is correct here.
            }
        }
    }

    private fun offer(raw: String) {
        // Length is checked before anything else: payloads are full of ids and blobs, and the
        // guard is more expensive than a comparison.
        if (raw.length < cfg.minChars || raw.length > cfg.maxChars) return
        if (seen.get(raw) != null) return
        seen[raw] = true

        if (!allowed(raw)) return
        if (HostBridge.peek(raw) != null) return

        // No callback: nothing is on screen to refresh. The value lands in cache, and the
        // render hook that eventually asks for it gets an instant hit.
        HostBridge.prefetch(raw)

        if (Logs.verbose) {
            queued++
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastLog > 2_000L) {
                lastLog = now
                Logs.d("prefetch: $queued string(s) queued from parsed data")
            }
        }
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
