package io.hooktrans.xposed

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.TransformationMethod
import android.view.View
import io.hooktrans.core.Logs

/**
 * Changes what a TextView *draws* without changing what `getText()` returns.
 *
 * This is the whole reason the module can translate an app without breaking it. Android
 * already has a supported concept for "displayed text differs from stored text" — it is how
 * password dots and `textAllCaps` work — and routing translations through it means the app's
 * own code keeps reading the exact string it wrote. Comparisons, parsing, view-holder
 * bookkeeping and analytics all keep working, because none of them ever see the translation.
 *
 * An existing transformation (usually all-caps on buttons) is preserved by applying it on top
 * of the translated string, so a translated button stays styled the way the app intended.
 */
class TranslationTransform(
    private val delegate: TransformationMethod?,
) : TransformationMethod {

    override fun getTransformation(source: CharSequence?, view: View?): CharSequence? {
        val original = source ?: return null
        var out: CharSequence = original
        try {
            val key = original.toString()
            val hit = HostBridge.peek(key)
            if (hit != null && hit != key) {
                out = hit
            } else if (hit == null && original is Spanned) {
                // Text built around an inline emoji or thumbnail. Whole-string lookup misses
                // because the placeholder characters are part of the key, so fall back to
                // splicing per-run translations in around the spans.
                out = piecewise(original) ?: out
            }
        } catch (t: Throwable) {
            Logs.d("transform lookup failed: ${t.message}")
        }
        return try {
            delegate?.getTransformation(out, view) ?: out
        } catch (t: Throwable) {
            out
        }
    }

    /**
     * Rebuilds [text] with each translatable run replaced by its translation and every
     * [android.text.style.ReplacementSpan] range copied through verbatim.
     *
     * Returns null when nothing was translated, so the caller keeps the original object rather
     * than an identical copy — this runs on every draw of every affected view, and a fresh
     * SpannableStringBuilder per frame for no change would be pure garbage.
     *
     * Spans are carried over by copying the source range first and then replacing the
     * characters inside it: `SpannableStringBuilder.replace` keeps the spans that cover the
     * edited range, which is exactly the behaviour needed for the emoji to survive.
     */
    private fun piecewise(text: Spanned): CharSequence? {
        val pieces = SpanText.pieces(text)
        if (pieces.isEmpty() || !SpanText.isSplit(pieces)) return null

        val out = SpannableStringBuilder(text)
        var translatedAny = false
        // Right to left: every edit shifts the offsets after it, and walking backwards means
        // the ranges still ahead of the cursor keep the positions taken from the original.
        for (i in pieces.indices.reversed()) {
            val p = pieces[i]
            if (!p.translatable) continue
            val part = text.subSequence(p.start, p.end)
            val core = SpanText.core(part)
            if (core.isEmpty()) continue
            val hit = HostBridge.peek(core) ?: continue
            if (hit == core) continue

            // Splice the translation into the same position the original run occupied, keeping
            // the whitespace that separated it from the neighbouring span.
            val raw = part.toString()
            val lead = raw.indexOf(core).coerceAtLeast(0)
            val from = p.start + lead
            val to = from + core.length
            if (from < 0 || to > out.length || to < from) continue
            out.replace(from, to, hit)
            translatedAny = true
        }
        return if (translatedAny) out else null
    }

    override fun onFocusChanged(
        view: View?,
        sourceText: CharSequence?,
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?,
    ) {
        runCatching {
            delegate?.onFocusChanged(view, sourceText, focused, direction, previouslyFocusedRect)
        }
    }

    fun unwrap(): TransformationMethod? = delegate
}
