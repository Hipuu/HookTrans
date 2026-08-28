package io.hooktrans.xposed

import android.text.Editable
import android.text.NoCopySpan
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.PasswordTransformationMethod
import android.text.method.TransformationMethod
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.EditText
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
 * Classic View-system coverage. A single hook on the funnel that every `setText` overload
 * and every XML `android:text` inflation ends up calling, plus the hint funnel.
 */
object TextViewHooks {

    /** Marks a CharSequence this module produced, so we never re-process our own output. */
    private class Ours : NoCopySpan

    private val reentrantTl = ThreadLocal.withInitial { false }

    /**
     * ThreadLocal.get() is a platform type, so it has to be read through an explicit
     * null-safe accessor: treating a null as "not reentrant" is the safe default, and it
     * keeps the guard from depending on Kotlin's unchecked platform-type assumption.
     */
    private var reentrant: Boolean
        get() = reentrantTl.get() == true
        set(value) = reentrantTl.set(value)

    /** Memoized TextGuard verdicts: this runs on every list-item bind during a fling. */
    private val verdicts = Lru<String, Boolean>(4_000)

    /**
     * Hook bodies run per-frame, so a failure that repeats would flood the log. Each site
     * reports once, at warning level and with the full cause chain: `ExceptionInInitializer-
     * Error` and friends carry a null message, so logging `t.message` alone hides the fault
     * that actually needs fixing.
     */
    private val reported = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    private fun reportOnce(where: String, t: Throwable) {
        var root: Throwable = t
        while (root.cause != null && root.cause !== root) root = root.cause!!
        if (!reported.add("$where:${root.javaClass.name}")) return
        Logs.w("$where failed: ${t.javaClass.simpleName} caused by $root", t)
    }

    private lateinit var cfg: HookConfig

    fun install(config: HookConfig) {
        cfg = config
        val tv = TextView::class.java

        val setTextHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (reentrant) return
                try {
                    onTextSet(param.thisObject as? TextView ?: return)
                } catch (t: Throwable) {
                    reportOnce("setText hook", t)
                }
            }
        }

        // The private four-argument funnel. Every public overload and the XML inflation path
        // reach it, so one hook covers the whole View system.
        val funnelHooked = runCatching {
            XposedHelpers.findAndHookMethod(
                tv, "setText",
                CharSequence::class.java, TextView.BufferType::class.java,
                java.lang.Boolean.TYPE, Integer.TYPE,
                setTextHook
            )
            true
        }.getOrElse { false }

        if (!funnelHooked) {
            Logs.w("private setText funnel missing on this ROM; hooking every overload")
            XposedBridge.hookAllMethods(tv, "setText", setTextHook)
        }

        // Safety net for views whose text arrived through a path we did not observe.
        runCatching {
            XposedHelpers.findAndHookMethod(tv, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (reentrant) return
                    runCatching { onTextSet(param.thisObject as? TextView ?: return) }
                }
            })
        }

        // Apps that install their own transformation (textAllCaps, custom ellipsizing) would
        // otherwise silently drop ours, so re-wrap whatever they set.
        runCatching {
            XposedHelpers.findAndHookMethod(
                tv, "setTransformationMethod", TransformationMethod::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val incoming = param.args[0] as? TransformationMethod
                        if (incoming is TranslationTransform) return
                        if (incoming is PasswordTransformationMethod) return
                        val view = param.thisObject as? TextView ?: return
                        if (view.transformationMethod !is TranslationTransform) return
                        param.args[0] = TranslationTransform(incoming)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        allowLengthChange(param.thisObject as? TextView ?: return)
                    }
                }
            )
        }

        if (config.hookHints) installHintHook(tv)
        Logs.d("TextView hooks installed")
    }

    private fun installHintHook(tv: Class<*>) {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (reentrant) return
                try {
                    val view = param.thisObject as? TextView ?: return
                    val hint = param.args[0] as? CharSequence ?: return
                    if (isOurs(hint)) return
                    val key = hint.toString()
                    if (!allowed(key)) return
                    val hit = HostBridge.peek(key)
                    if (hit != null && hit != key) {
                        param.args[0] = mark(hit)
                        return
                    }
                    val ref = WeakReference(view)
                    HostBridge.request(key) { translated ->
                        val v = ref.get() ?: return@request
                        guarded { if (v.hint?.toString() == key) v.hint = mark(translated) }
                    }
                } catch (t: Throwable) {
                    reportOnce("setHint hook", t)
                }
            }
        }
        runCatching { XposedHelpers.findAndHookMethod(tv, "setHint", CharSequence::class.java, hook) }
    }

    // ------------------------------------------------------------------ core

    private fun onTextSet(view: TextView) {
        val text = view.text ?: return
        if (text.isEmpty()) return

        // Never touch anything the user can type into. Editable content is data by
        // definition, and a translated form field is a corrupted form field.
        if (view is EditText) return skipped(text, "EditText")
        if (text is Editable) return skipped(text, "Editable")
        if (view.transformationMethod is PasswordTransformationMethod) return skipped(text, "password")
        if (isExcludedId(view)) return skipped(text, "excluded id")

        var replacementSpanned = false
        if (text is Spanned) {
            if (text.getSpans(0, text.length, Ours::class.java).isNotEmpty()) return
            if (cfg.skipLinkedText) {
                // Link spans are behaviour, not decoration: their offsets index the original.
                if (text.getSpans(0, text.length, ClickableSpan::class.java).isNotEmpty())
                    return skipped(text, "ClickableSpan")
                if (text.getSpans(0, text.length, URLSpan::class.java).isNotEmpty())
                    return skipped(text, "URLSpan")
            }
            // A ReplacementSpan draws an emoji or a thumbnail over placeholder characters, so
            // replacing the whole string would delete the image. It is also what almost every
            // social post contains, which made refusing outright the module's biggest coverage
            // hole. Translate the runs between the spans instead.
            if (text.getSpans(0, text.length, ReplacementSpan::class.java).isNotEmpty())
                replacementSpanned = true
            if (cfg.maxCompatibility && text.getSpans(0, text.length, Any::class.java).isNotEmpty())
                return skipped(text, "maxCompatibility span")
        }

        if (replacementSpanned) {
            requestPieces(view, text as Spanned)
            return
        }

        val source = text.toString()
        if (!allowed(source)) return skipped(text, "guard")

        if (view.isTextSelectable) {
            if (cfg.translateSelectable) substituteDirectly(view, source)
            else skipped(text, "selectable")
            return
        }

        if (view.transformationMethod !is TranslationTransform && !installTransform(view))
            return skipped(text, "transform rejected")

        if (HostBridge.peek(source) != null) {
            if (Logs.verbose) Logs.d("show [cached] $source")
            refresh(view)
            return
        }
        val ref = WeakReference(view)
        if (Logs.verbose) Logs.d("want $source")
        HostBridge.request(source) {
            val v = ref.get() ?: return@request
            if (v.text?.toString() == source) refresh(v)
        }
    }

    /**
     * Handles text carrying inline emoji or thumbnails.
     *
     * The runs between the [ReplacementSpan]s are requested individually and spliced back in by
     * [TranslationTransform], so the images stay where the app put them. Each run is guarded on
     * its own: a post that is half prose and half a price still gets its prose translated.
     */
    private fun requestPieces(view: TextView, text: Spanned) {
        val pieces = SpanText.pieces(text)
        if (pieces.isEmpty()) return

        val wanted = ArrayList<String>(pieces.size)
        for (p in pieces) {
            if (!p.translatable) continue
            val core = SpanText.core(text.subSequence(p.start, p.end))
            if (core.isEmpty()) continue
            if (!allowed(core)) continue
            wanted += core
        }
        if (wanted.isEmpty()) return skipped(text, "guard (all runs)")

        // Selectable spanned text cannot use the transformation at all: the framework refuses a
        // length change on a selectable view, so installTransform always fails and comment
        // bodies — which are both selectable and full of emoji in most social apps — were
        // silently dropped. Splice the pieces into the stored text instead, which is the same
        // trade-off substituteDirectly already makes for plain selectable text.
        if (view.isTextSelectable) {
            if (!cfg.translateSelectable) return skipped(text, "selectable")
            substitutePieces(view, text, wanted)
            return
        }
        if (view.transformationMethod !is TranslationTransform && !installTransform(view))
            return skipped(text, "transform rejected")

        val original = text.toString()
        val ref = WeakReference(view)
        for (core in wanted) {
            if (HostBridge.peek(core) != null) {
                ref.get()?.let { refresh(it) }
                continue
            }
            HostBridge.request(core) {
                val v = ref.get() ?: return@request
                // The view is recycled constantly in a list; only repaint if it still holds
                // the same text this request was made for.
                if (v.text?.toString() == original) refresh(v)
            }
        }
    }

    /**
     * Records why a visible string was left alone. Verbose-only and deduplicated, because the
     * question "the app still shows Chinese — is that a bug or a deliberate refusal?" is
     * otherwise unanswerable from outside: a skip and a cache miss look identical on screen.
     */
    private val skipLogged = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun skipped(text: CharSequence, reason: String) {
        if (!Logs.verbose) return
        val s = text.toString().trim()
        if (s.length < 2) return
        if (skipLogged.size > 2_000) return
        if (!skipLogged.add("$reason|$s")) return
        Logs.d("skip [$reason] ${s.take(40)}")
    }

    private fun allowed(source: String): Boolean {
        verdicts[source]?.let { return it }
        var ok = TextGuard.shouldTranslate(source, cfg)
        if (ok && cfg.maxCompatibility) {
            // Conservative mode: multi-word prose only. Single tokens are the strings most
            // likely to double as keys, flags or labels the app compares against.
            ok = source.trim().any { it.isWhitespace() }
        }
        if (ok && TextGuard.looksLikeTargetScript(source, HostBridge.dstLang)) ok = false
        verdicts[source] = ok
        return ok
    }

    private fun isExcludedId(view: TextView): Boolean {
        if (cfg.excludeViewIds.isEmpty()) return false
        return try {
            val id = view.id
            if (id == View.NO_ID) return false
            val name = view.resources.getResourceEntryName(id) ?: return false
            cfg.excludeViewIds.any { name.contains(it, ignoreCase = true) }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Installs the display-only transformation and confirms the framework will honour a
     * length change. If it will not, the original transformation is put back and the view is
     * left exactly as the app configured it.
     *
     * Rolling back matters: a transformation that returns a longer string than the source
     * while `mAllowTransformationLengthChange` is false makes TextView index its layout with
     * the *source* length, which is an out-of-bounds crash inside the host app. Leaving ours
     * installed on a view where the flag would not stick is the one failure mode here that
     * breaks an app instead of just failing to translate it.
     */
    private fun installTransform(view: TextView): Boolean {
        val previous = view.transformationMethod
        return try {
            guarded { view.transformationMethod = TranslationTransform(previous) }
            allowLengthChange(view)
            val ok = view.transformationMethod is TranslationTransform &&
                XposedHelpers.getBooleanField(view, "mAllowTransformationLengthChange")
            if (!ok) rollback(view, previous)
            ok
        } catch (t: Throwable) {
            Logs.d("transform install failed: ${t.message}")
            runCatching { rollback(view, previous) }
            false
        }
    }

    private fun rollback(view: TextView, previous: TransformationMethod?) {
        if (view.transformationMethod !is TranslationTransform) return
        guarded { view.transformationMethod = previous }
    }

    /**
     * TextView computes this flag itself and only trusts the hidden `TransformationMethod2`
     * interface. Setting the field directly is equivalent and avoids shipping a stub of a
     * hidden framework interface inside the module.
     */
    private fun allowLengthChange(view: TextView) {
        runCatching {
            if (view.transformationMethod is TranslationTransform && !view.isTextSelectable) {
                XposedHelpers.setBooleanField(view, "mAllowTransformationLengthChange", true)
            }
        }
    }

    /**
     * Opt-in path for selectable text. A transformation cannot change the length of
     * selectable text, so the stored text itself is replaced; `getText()` then returns the
     * translation. Off by default because that is a visible change to app-facing state.
     */
    private fun substituteDirectly(view: TextView, source: String) {
        val hit = HostBridge.peek(source)
        if (hit == null) {
            val ref = WeakReference(view)
            HostBridge.request(source) {
                val v = ref.get() ?: return@request
                if (v.text?.toString() == source) substituteDirectly(v, source)
            }
            return
        }
        if (hit == source) return
        guarded { view.text = mark(hit) }
    }

    /**
     * The selectable counterpart of [TranslationTransform.piecewise]: rewrites the stored text
     * so the runs between the emoji are translated and the emoji themselves stay put.
     *
     * `SpannableStringBuilder.replace` keeps the spans that cover the edited range, and the
     * pieces are applied back-to-front because every edit shifts the offsets after it.
     */
    private fun substitutePieces(view: TextView, text: Spanned, wanted: List<String>) {
        val original = text.toString()
        val ref = WeakReference(view)
        var missing = false
        for (core in wanted) {
            if (HostBridge.peek(core) == null) {
                missing = true
                HostBridge.request(core) {
                    val v = ref.get() ?: return@request
                    val now = v.text
                    if (now?.toString() == original && now is Spanned) {
                        substitutePieces(v, now, wanted)
                    }
                }
            }
        }
        // Rewriting once per arriving piece would fight the recycler for no benefit; wait until
        // the whole comment can be spliced in one edit.
        if (missing) return

        val out = SpannableStringBuilder(text)
        val pieces = SpanText.pieces(text)
        var changed = false
        for (i in pieces.indices.reversed()) {
            val p = pieces[i]
            if (!p.translatable) continue
            val part = text.subSequence(p.start, p.end)
            val core = SpanText.core(part)
            if (core.isEmpty()) continue
            val hit = HostBridge.peek(core) ?: continue
            if (hit == core) continue
            val lead = part.toString().indexOf(core).coerceAtLeast(0)
            val from = p.start + lead
            val to = from + core.length
            if (from < 0 || to > out.length || to < from) continue
            out.replace(from, to, hit)
            changed = true
        }
        if (!changed) return
        guarded { view.text = out.apply { setSpan(Ours(), 0, 0, Spanned.SPAN_MARK_MARK) } }
    }

    /**
     * Re-runs the transformation once a translation arrives. Poking the layout directly
     * avoids firing the app's TextWatchers for a change the app never made; `setText` is the
     * documented fallback if those internals ever move.
     */
    private fun refresh(view: TextView) {
        guarded {
            val text = view.text ?: return@guarded
            val ok = runCatching {
                val transformed = view.transformationMethod?.getTransformation(text, view) ?: text
                XposedHelpers.callMethod(view, "nullLayouts")
                XposedHelpers.setObjectField(view, "mTransformed", transformed)
                view.requestLayout()
                view.invalidate()
                true
            }.getOrElse { false }
            if (!ok) view.text = text
        }
    }

    private fun mark(s: CharSequence): CharSequence =
        SpannableString(s).apply { setSpan(Ours(), 0, 0, Spanned.SPAN_MARK_MARK) }

    private fun isOurs(s: CharSequence): Boolean =
        s is Spanned && s.getSpans(0, s.length, Ours::class.java).isNotEmpty()

    private inline fun guarded(block: () -> Unit) {
        val prev = reentrant
        reentrant = true
        try {
            block()
        } catch (t: Throwable) {
            Logs.d("guarded block: ${t.message}")
        } finally {
            reentrant = prev
        }
    }
}
