package io.hooktrans.xposed

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.hooktrans.core.HookConfig
import io.hooktrans.core.Logs
import io.hooktrans.core.Lru
import io.hooktrans.core.TextGuard
import java.lang.ref.WeakReference

/**
 * Text that never becomes a View. Tab bars, badges, chart labels and most of the chrome in
 * large commercial apps are painted straight onto a Canvas, so `setText` is never called and
 * the TextView pipeline cannot see them.
 *
 * The interception point is `Canvas.drawText` / `drawTextRun`, which is the last stop before
 * glyphs reach the screen. That makes this hook both the most complete one in the module and
 * the most performance-sensitive: it runs inside display-list recording, so anything done
 * here is done while the app is trying to hit a frame deadline.
 *
 * Three properties keep it safe:
 *
 *  - **It cannot corrupt app state.** Unlike the resource hook, nothing here is ever returned
 *    to the app. A draw call is a one-way instruction to the renderer, so the app's own
 *    strings, comparisons and parsing are untouched by construction.
 *  - **It never blocks.** Substitution happens only from cache. A miss queues the string and
 *    draws the original, exactly as the app asked.
 *  - **It never changes the width of what it draws.** See [drawFitted].
 */
object CanvasHooks {

    private lateinit var cfg: HookConfig

    /** Memoized TextGuard verdicts: this is called for every text run of every frame. */
    private val verdicts = Lru<String, Boolean>(4_000)

    /**
     * Strings this hook has already produced. A translated run that gets redrawn must not be
     * mistaken for fresh source text and queued for a second, pointless round trip.
     */
    private val outputs = Lru<String, Boolean>(4_000)

    private val reported = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    private fun reportOnce(where: String, t: Throwable) {
        var root: Throwable = t
        while (root.cause != null && root.cause !== root) root = root.cause!!
        if (!reported.add("$where:${root.javaClass.name}")) return
        Logs.w("$where failed: ${t.javaClass.simpleName} caused by $root", t)
    }

    /**
     * How far the text may be condensed to fit the original width before the squeeze is
     * capped. Past roughly this point condensed glyphs stop being easier to read than the
     * untranslated original, so the remainder is allowed to overhang instead.
     */
    private const val MIN_SCALE = 0.55f

    fun install(config: HookConfig) {
        cfg = config
        DrawTracker.install()

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    substitute(param)
                } catch (t: Throwable) {
                    reportOnce("drawText hook", t)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                // Restore the app's Paint. It is a long-lived object the app reuses across
                // draws, so a condensed scale left behind would silently squash every later
                // string drawn with it.
                val paint = param.getObjectExtra(EXTRA_PAINT) as? Paint ?: return
                val scale = param.getObjectExtra(EXTRA_SCALE) as? Float ?: return
                runCatching { paint.textScaleX = scale }
            }
        }

        // Where the draw calls actually land.
        //
        // A hardware-accelerated view records into a RecordingCanvas, and the draw methods it
        // runs are the ones declared on the hidden `BaseRecordingCanvas`, which overrides
        // Canvas and goes straight to native without calling super. Hooking only Canvas
        // therefore hooks a method nothing ever invokes: correct-looking, and completely dead.
        // Software rendering does use Canvas, so both are hooked.
        //
        // Overriding rather than delegating is also what makes hooking both safe — one draw
        // call runs exactly one of these implementations, so a translation can never be fed
        // back in as fresh source text.
        val targets = ArrayList<Class<*>>(2)
        runCatching {
            targets += XposedHelpers.findClass(
                "android.graphics.BaseRecordingCanvas", Canvas::class.java.classLoader
            )
        }.onFailure { Logs.d("BaseRecordingCanvas absent; software canvas only") }
        targets += Canvas::class.java

        var n = 0
        targets.forEach { cls ->
            listOf("drawText", "drawTextRun").forEach { m ->
                runCatching { n += XposedBridge.hookAllMethods(cls, m, hook).size }
            }
        }
        Logs.i("Canvas hooks installed ($n draw method(s) on ${targets.size} class(es))")
    }

    // ------------------------------------------------------------------ core

    private fun substitute(param: XC_MethodHook.MethodHookParam) {
        val args = param.args
        if (args.size < 4) return

        val paintIdx = args.indexOfLast { it is Paint }
        if (paintIdx < 0) return
        val paint = args[paintIdx] as Paint

        // A TextView draws through the same Canvas calls, but its text has already been
        // translated by the transformation method. Leaving it to that pipeline avoids doing
        // the work twice and keeps `getText()` as the single definition of app-facing state.
        val view = DrawTracker.current()
        if (view is TextView) return

        val text = args[0]
        val source: String = when (text) {
            is String -> text
            // char[] is how a Layout feeds its lines through, one fragment at a time. Those
            // fragments are mid-sentence and would need a String allocation on every draw
            // just to be tested, so they are left alone.
            is CharSequence -> text.toString()
            else -> return
        }

        // Which overload this is, decided by shape rather than by signature lookup so the
        // same code works on ROMs that add or reorder drawText variants.
        val ranged = args.size > 2 && args[1] is Int && args[2] is Int
        if (ranged) {
            val start = args[1] as Int
            val end = args[2] as Int
            // A partial range is one line of a multi-line layout, or one bidi run. Replacing
            // it would translate a sentence fragment and desynchronize the surrounding runs.
            if (start != 0 || end != source.length) return
            if (args.size >= 9 && args[3] is Int && args[4] is Int) {
                val ctxStart = args[3] as Int
                val ctxEnd = args[4] as Int
                if (ctxStart != 0 || ctxEnd != source.length) return
            }
        }

        val out = lookup(source, view) ?: return

        args[0] = out
        if (ranged) {
            args[1] = 0
            args[2] = out.length
            if (args.size >= 9 && args[3] is Int && args[4] is Int) {
                args[3] = 0
                args[4] = out.length
            }
        }
        drawFitted(param, paint, source, out)
    }

    /**
     * Keeps the translation inside the space the app measured for the original.
     *
     * A canvas label sits at coordinates the app computed from its own `measureText` of the
     * source string. A longer translation drawn at that same origin would overhang whatever
     * is beside it — and for centred or right-aligned text it would drift off its anchor.
     * Condensing the glyphs to the original advance width keeps every layout decision the app
     * made still true, which is the same bargain the TextView transformation strikes.
     */
    private fun drawFitted(
        param: XC_MethodHook.MethodHookParam,
        paint: Paint,
        source: String,
        out: String,
    ) {
        val original = runCatching { paint.measureText(source) }.getOrDefault(0f)
        if (original <= 0f) return
        val translated = runCatching { paint.measureText(out) }.getOrDefault(0f)
        if (translated <= original) return

        val current = paint.textScaleX
        param.setObjectExtra(EXTRA_PAINT, paint)
        param.setObjectExtra(EXTRA_SCALE, current)
        paint.textScaleX = maxOf(current * (original / translated), current * MIN_SCALE)
    }

    private fun lookup(source: String, view: View?): String? {
        if (outputs.contains(source)) return null
        if (!allowed(source)) return null

        val hit = HostBridge.peek(source)
        if (hit != null) {
            if (hit == source) return null
            outputs[hit] = true
            return hit
        }

        // Miss: draw the original this frame and repaint the one view that needs it when the
        // translation lands. No view means the cache is still warmed for the next sighting.
        val ref = view?.let { WeakReference(it) }
        HostBridge.request(source) {
            val v = ref?.get() ?: return@request
            runCatching { v.postInvalidate() }
        }
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

    private const val EXTRA_PAINT = "ht_paint"
    private const val EXTRA_SCALE = "ht_scale"
}
