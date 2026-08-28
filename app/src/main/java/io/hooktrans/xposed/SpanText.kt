package io.hooktrans.xposed

import android.text.Spanned
import android.text.style.ReplacementSpan

/**
 * Splits spanned text around the spans whose character range is load-bearing.
 *
 * A [ReplacementSpan] does not decorate text, it *replaces* it: the characters underneath are
 * a placeholder (usually `￼`) and the span draws an emoji, a sticker or an inline
 * thumbnail in their place. Substituting the whole string with a translation therefore deletes
 * the image, because the span's offsets no longer point at anything.
 *
 * Refusing to translate these strings at all — the module's previous behaviour — turns out to
 * be the single largest coverage hole in a real feed: social posts are exactly the text that
 * carries emoji, so the main content of a page like Coolapk's timeline was skipped while its
 * chrome was translated.
 *
 * The answer is to translate *around* the spans. This returns the string carved into ordered
 * pieces: runs of ordinary text that can be replaced freely, and the untouchable span ranges
 * between them, which are re-emitted verbatim with their spans intact. Translation quality
 * suffers where a sentence is cut by an inline emoji — a fragment is translated as a fragment —
 * and that is a deliberate trade against showing the user nothing at all.
 */
object SpanText {

    /**
     * One run of the original string. [translatable] is false for the ranges a
     * [ReplacementSpan] owns, which must be copied through unchanged.
     */
    class Piece(val start: Int, val end: Int, val translatable: Boolean)

    /** The whole string as one translatable piece: the overwhelmingly common case. */
    private val WHOLE_UNSPLIT = 1

    /**
     * [text] carved into pieces. A single translatable piece covering everything means there
     * was nothing to work around and the caller should use its ordinary whole-string path.
     */
    fun pieces(text: Spanned): List<Piece> {
        val len = text.length
        if (len <= 0) return emptyList()

        val spans = try {
            text.getSpans(0, len, ReplacementSpan::class.java)
        } catch (t: Throwable) {
            null
        }
        if (spans == null || spans.isEmpty()) return listOf(Piece(0, len, true))

        // Ranges are collected then merged: spans may be listed in any order and may overlap,
        // and an unmerged overlap would emit a piece with end < start.
        val blocked = ArrayList<IntArray>(spans.size)
        for (s in spans) {
            val start = text.getSpanStart(s)
            val end = text.getSpanEnd(s)
            if (start < 0 || end > len || end <= start) continue
            blocked += intArrayOf(start, end)
        }
        if (blocked.isEmpty()) return listOf(Piece(0, len, true))

        blocked.sortBy { it[0] }
        val merged = ArrayList<IntArray>(blocked.size)
        for (r in blocked) {
            val last = merged.lastOrNull()
            // `<=` rather than `<`: two spans that merely touch leave no text between them, so
            // emitting an empty piece for the gap would be noise.
            if (last != null && r[0] <= last[1]) last[1] = maxOf(last[1], r[1])
            else merged += intArrayOf(r[0], r[1])
        }

        val out = ArrayList<Piece>(merged.size * 2 + 1)
        var cursor = 0
        for (r in merged) {
            if (r[0] > cursor) out += Piece(cursor, r[0], true)
            out += Piece(r[0], r[1], false)
            cursor = r[1]
        }
        if (cursor < len) out += Piece(cursor, len, true)
        return out
    }

    /** True when [pieces] describes a string that actually needs the piecewise path. */
    fun isSplit(pieces: List<Piece>): Boolean =
        pieces.size > WHOLE_UNSPLIT || pieces.any { !it.translatable }

    /**
     * The translatable core of a run, without the whitespace that surrounds it.
     *
     * Trimming matters for cache identity: " 你好 " and "你好" are the same string to translate,
     * and keying them separately would double the engine traffic for no benefit. The
     * surrounding whitespace is restored when the translation is spliced back in, so the
     * spacing between a word and the emoji beside it survives.
     */
    fun core(part: CharSequence): String = part.toString().trim()
}
