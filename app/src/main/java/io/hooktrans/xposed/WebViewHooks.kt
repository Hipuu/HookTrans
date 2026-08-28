package io.hooktrans.xposed

import android.os.Handler
import android.os.Looper
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.hooktrans.core.HookConfig
import io.hooktrans.core.Logs
import io.hooktrans.core.TextGuard
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * WebView content is a second, entirely separate text pipeline: none of it goes through
 * TextView. It is handled by injecting a small script that mirrors the native strategy —
 * translate what is on the page now, and keep watching for what arrives later.
 *
 * No `addJavascriptInterface` is used. Exposing a callable object to every page loaded by
 * the host app would be a genuine attack surface, so the script only maintains queues and
 * the module polls them from Java with `evaluateJavascript`.
 *
 * Nothing here is typed to `android.webkit.WebView`, and that is deliberate. Large apps
 * routinely bundle their own Chromium fork — UC/U4 in Alibaba's apps, Tencent X5 in WeChat,
 * JD and Meituan — whose WebView class is an independent `View` subclass that shares the
 * *shape* of the framework's API without sharing its type. A hook written against
 * `android.webkit.WebView` installs cleanly, reports success, and never sees a single page in
 * those apps. So the view is handled as a plain View and `evaluateJavascript` is located
 * reflectively, which works on any core that keeps the conventional method name.
 */
object WebViewHooks {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var cfg: HookConfig

    private const val POLL_MS = 700L
    private const val IDLE_POLL_MS = 4_000L
    private const val MAX_BATCH = 40

    /**
     * WebViews that already have a polling loop running.
     *
     * Every navigation callback wants to "start translating this WebView", and some of them
     * (`onLoadResource`) fire once per asset on the page. Without this guard each of those
     * calls would start another self-perpetuating 700 ms loop on the main thread, so a page
     * with a hundred resources would end up with a hundred loops all running
     * `evaluateJavascript` forever — enough to make the host app stutter. One loop per WebView
     * is all that is ever needed: the loop keeps polling across navigations by itself.
     *
     * Weak keys, and only ever touched from the main thread.
     */
    private class Pump {
        var running = false
        var lastTick = 0L
        var detachedTicks = 0
    }

    private val pumps = java.util.WeakHashMap<View, Pump>()

    /** A loop that has not ticked in this long is presumed dead and may be restarted. */
    private const val STALE_MS = 30_000L

    /** Give up on a detached WebView after roughly a minute of idle polling. */
    private const val MAX_DETACHED_TICKS = 15

    /**
     * Bundled browser cores, by the class an app actually instantiates. Each is an independent
     * `View` subclass with a framework-shaped API, so they are hooked by name rather than by
     * type. Absent names are skipped silently — every app has at most one of these.
     */
    private val ALT_CORES = listOf(
        "com.tencent.smtt.sdk.WebView",              // Tencent X5: WeChat, QQ, JD, Meituan
        "com.uc.webview.export.WebView",             // UC/U4 core, as exported
        "android.taobao.windvane.extra.uc.WVUCWebView", // Alibaba's WindVane wrapper over U4
        "com.alipay.mobile.nebulacore.web.H5WebView", // Alipay Nebula
    )

    private lateinit var loader: ClassLoader

    fun install(config: HookConfig, appLoader: ClassLoader) {
        cfg = config
        loader = appLoader

        val hooked = ArrayList<String>(2)
        if (hookCore(android.webkit.WebView::class.java)) hooked += "android.webkit"
        ALT_CORES.forEach { name ->
            val cls = runCatching { XposedHelpers.findClass(name, appLoader) }.getOrNull()
            if (cls != null && hookCore(cls)) hooked += name.substringAfterLast('.')
        }
        Logs.d("WebView hooks installed (${hooked.size} core(s): ${hooked.joinToString()})")
    }

    private val hookedCores = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Instruments one WebView implementation. Returns false when the class turns out not to be
     * a View, which is how a name collision with an unrelated class stays harmless.
     */
    private fun hookCore(cls: Class<*>): Boolean {
        if (!View::class.java.isAssignableFrom(cls)) return false
        if (!hookedCores.add(cls.name)) return true

        val kick = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                schedule(view)
            }
        }
        listOf("loadUrl", "loadData", "loadDataWithBaseURL", "postUrl", "reload").forEach { m ->
            runCatching { XposedBridge.hookAllMethods(cls, m, kick) }
        }

        // Single-page apps navigate without another loadUrl, so follow the client too. The
        // client's type differs per core (WebViewClient, com.tencent.smtt.sdk.WebViewClient,
        // ...), so the argument is taken by position rather than by declared type.
        listOf("setWebViewClient", "setWebChromeClient").forEach { setter ->
            runCatching {
                XposedBridge.hookAllMethods(cls, setter, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val client = param.args.firstOrNull() ?: return
                        val view = param.thisObject as? View ?: return
                        hookClient(client.javaClass, WeakReference(view))
                    }
                })
            }
        }
        return true
    }

    private val hookedClients = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun hookClient(clazz: Class<*>, viewRef: WeakReference<View>) {
        if (!hookedClients.add(clazz.name)) return
        listOf("onPageFinished", "doUpdateVisitedHistory", "onLoadResource", "onProgressChanged").forEach { m ->
            runCatching {
                XposedBridge.hookAllMethods(clazz, m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.args.firstOrNull() as? View ?: viewRef.get() ?: return
                        schedule(view)
                    }
                })
            }
        }
    }

    /**
     * Starts the polling loop for [view] if it does not already have one.
     *
     * A navigation only needs to re-arm the collector, not spawn a second loop: the script's
     * MutationObserver marks the DOM dirty and the existing loop picks the new content up on
     * its next tick. `__htReady` makes re-injecting the bootstrap a no-op, so the page that
     * replaced the old one still gets instrumented.
     */
    private fun schedule(view: View) {
        val ref = WeakReference(view)
        main.post {
            val v = ref.get() ?: return@post
            val now = android.os.SystemClock.uptimeMillis()
            val pump = pumps.getOrPut(v) { Pump() }
            // A loop can die if evaluateJavascript never calls back (destroyed WebView), so a
            // stale entry is allowed to be replaced rather than blocking the view forever.
            if (pump.running && now - pump.lastTick < STALE_MS) {
                pump.detachedTicks = 0
                return@post
            }
            pump.running = true
            pump.lastTick = now
            pump.detachedTicks = 0
            Logs.d("webview pump start on ${v.javaClass.name}")
            tick(ref, bootstrap = true)
        }
    }

    /**
     * Calls `view.evaluateJavascript(script, callback)` on whatever core [view] belongs to.
     *
     * The second parameter is a `ValueCallback`, but *which* `ValueCallback` depends on the
     * core: `android.webkit`, `com.tencent.smtt.sdk` and `com.uc.webview.export` each declare
     * their own. Rather than guess, the method is found by name and arity and its own declared
     * parameter type is implemented on the spot with a dynamic proxy — so the callback is
     * always exactly the type that core expects.
     *
     * Returns false if this view has no such method, which is how a non-WebView that happened
     * to match a class name stays harmless.
     */
    private fun evaluateJs(view: View, script: String, onResult: ((String?) -> Unit)?): Boolean {
        val m = evalMethod(view.javaClass) ?: return false
        val cbType = m.parameterTypes[1]
        val cb: Any? = if (onResult == null) null else runCatching {
            Proxy.newProxyInstance(cbType.classLoader ?: loader, arrayOf(cbType)) { _, method, a ->
                if (method.name == "onReceiveValue") {
                    runCatching { onResult(a?.firstOrNull() as? String) }
                    null
                } else when (method.name) {
                    "hashCode" -> System.identityHashCode(onResult)
                    "equals" -> false
                    "toString" -> "HookTransValueCallback"
                    else -> null
                }
            }
        }.getOrNull()
        return runCatching { m.invoke(view, script, cb); true }.getOrElse { false }
    }

    /** Memoized per WebView class: reflection lookup is far too slow for a 700 ms loop. */
    private val evalMethods = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val NO_METHOD = Any()

    private fun evalMethod(cls: Class<*>): Method? {
        val cached = evalMethods[cls.name]
        if (cached != null) return if (cached === NO_METHOD) null else cached as Method
        val found = generateSequence<Class<*>>(cls) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull {
                it.name == "evaluateJavascript" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java
            }
            ?.apply { isAccessible = true }
        evalMethods[cls.name] = found ?: NO_METHOD
        return found
    }

    /** `settings.javaScriptEnabled`, again without naming any core's settings type. */
    private fun jsEnabled(view: View): Boolean = runCatching {
        val settings = XposedHelpers.callMethod(view, "getSettings") ?: return true
        XposedHelpers.callMethod(settings, "getJavaScriptEnabled") as? Boolean ?: true
    }.getOrDefault(true)

    private fun tick(ref: WeakReference<View>, bootstrap: Boolean) {
        val view = ref.get() ?: return
        val pump = pumps[view] ?: return
        pump.lastTick = android.os.SystemClock.uptimeMillis()

        // Polling a WebView nobody is looking at is pure battery cost. Back off instead of
        // stopping outright, because a detached view is often re-attached (ViewPager, tabs),
        // and give up entirely once it stays gone.
        if (!view.isAttachedToWindow && !bootstrap) {
            pump.detachedTicks++
            if (pump.detachedTicks > MAX_DETACHED_TICKS) {
                pump.running = false
                return
            }
            main.postDelayed({ tick(ref, false) }, IDLE_POLL_MS)
            return
        }
        pump.detachedTicks = 0

        try {
            if (!jsEnabled(view)) {
                // JS may be switched on later, so keep a slow watch rather than giving up.
                main.postDelayed({ tick(ref, true) }, IDLE_POLL_MS)
                return
            }

            val js = if (bootstrap) BOOTSTRAP + "\n__htCollect($MAX_BATCH);" else "__htCollect($MAX_BATCH);"
            val ok = evaluateJs(view, js) { raw ->
                try {
                    val pending = decode(raw)
                    if (pending.isNotEmpty()) translate(ref, pending)
                } catch (t: Throwable) {
                    Logs.d("webview collect: ${t.message}")
                }
                // Re-inject the bootstrap whenever the page has lost it: a navigation replaces
                // the whole JS context, and `null` is what a missing __htCollect returns.
                val gone = raw == null || raw == "null"
                main.postDelayed({ tick(ref, gone) }, POLL_MS)
            }
            if (!ok) pump.running = false
        } catch (t: Throwable) {
            // evaluateJavascript throws on a destroyed WebView; that loop is over.
            Logs.d("webview pump: ${t.message}")
            pump.running = false
        }
    }

    /** evaluateJavascript hands back a JSON-encoded JSON string, hence the double decode. */
    private fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        val inner = try {
            JSONArray("[$raw]").getString(0)
        } catch (t: Throwable) {
            return emptyList()
        }
        val arr = try {
            JSONArray(inner)
        } catch (t: Throwable) {
            return emptyList()
        }
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i) ?: continue
            if (s.isNotBlank() && TextGuard.shouldTranslate(s, cfg)) out += s
        }
        return out
    }

    private fun translate(ref: WeakReference<View>, texts: List<String>) {
        val ready = HashMap<String, String>()
        val misses = ArrayList<String>()
        texts.forEach { src ->
            val hit = HostBridge.peek(src)
            when {
                hit == null -> misses += src
                hit != src -> ready[src] = hit
            }
        }
        apply(ref, ready)

        // Misses come back one at a time; each arrival applies on its own, which keeps the
        // page updating progressively instead of waiting for the slowest string.
        misses.forEach { src ->
            HostBridge.request(src) { translated -> apply(ref, mapOf(src to translated)) }
        }
    }

    private fun apply(ref: WeakReference<View>, results: Map<String, String>) {
        if (results.isEmpty()) return
        val payload = JSONObject().also { o -> results.forEach { (k, v) -> o.put(k, v) } }.toString()
        main.post {
            val view = ref.get() ?: return@post
            runCatching { evaluateJs(view, "__htApply(${JSONObject.quote(payload)});", null) }
        }
    }

    /**
     * The injected script. It records every text node it rewrites so the original is never
     * re-collected, ignores editable regions and form values, and re-arms on DOM mutation so
     * content that loads later is picked up.
     */
    private val BOOTSTRAP = """
(function(){
  if (window.__htReady) return; window.__htReady = 1;
  var SKIP = {SCRIPT:1, STYLE:1, NOSCRIPT:1, TEXTAREA:1, CODE:1, PRE:1, SVG:1, CANVAS:1};
  var done = new WeakSet();      // text nodes already replaced
  var seen = Object.create(null); // strings already sent
  var dirty = true;

  function usable(node){
    if (done.has(node)) return false;
    var v = node.nodeValue;
    if (!v) return false;
    v = v.trim();
    if (v.length < 2 || v.length > 400) return false;
    if (!/[A-Za-z\u00C0-\u024F\u0370-\u1CFF\u3040-\uD7FF]/.test(v)) return false;   // has at least one letter
    var p = node.parentNode;
    while (p && p.nodeType === 1) {
      if (SKIP[p.tagName]) return false;
      if (p.isContentEditable) return false;
      if (p.getAttribute && p.getAttribute('translate') === 'no') return false;
      p = p.parentNode;
    }
    return true;
  }

  function walk(limit){
    var out = [], w = document.createTreeWalker(document.body || document.documentElement,
      NodeFilter.SHOW_TEXT, null, false), n;
    while ((n = w.nextNode()) && out.length < limit) {
      if (!usable(n)) continue;
      var k = n.nodeValue.trim();
      if (seen[k]) continue;
      seen[k] = 1;
      out.push(k);
    }
    return out;
  }

  window.__htCollect = function(limit){
    try {
      if (!dirty) return '[]';
      var r = walk(limit);
      if (r.length < limit) dirty = false;
      return JSON.stringify(r);
    } catch (e) { return '[]'; }
  };

  window.__htApply = function(json){
    try {
      var map = JSON.parse(json);
      var w = document.createTreeWalker(document.body || document.documentElement,
        NodeFilter.SHOW_TEXT, null, false), n;
      while ((n = w.nextNode())) {
        if (done.has(n)) continue;
        var v = n.nodeValue; if (!v) continue;
        var k = v.trim(); if (!k || !map[k]) continue;
        n.nodeValue = v.replace(k, map[k]);
        done.add(n);
      }
      // Attributes that are shown to the user rather than used as data.
      ['placeholder','title','alt','aria-label'].forEach(function(a){
        var els = document.querySelectorAll('['+a+']');
        for (var i=0;i<els.length;i++){
          var val = els[i].getAttribute(a); if (!val) continue;
          var t = map[val.trim()]; if (t) els[i].setAttribute(a, t);
        }
      });
    } catch (e) {}
  };

  try {
    new MutationObserver(function(){ dirty = true; })
      .observe(document.documentElement, {childList:true, subtree:true, characterData:true});
  } catch (e) {}
})();
""".trimIndent()
}
