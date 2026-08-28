package io.hooktrans.service

import android.graphics.Bitmap
import io.hooktrans.core.HookConfig
import io.hooktrans.core.Logs
import io.hooktrans.core.Lru
import io.hooktrans.core.TextGuard
import io.hooktrans.engine.OcrEngine
import io.hooktrans.ipc.TextRegion
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Recognise-then-translate, sitting on top of [TranslationRepo] so recognised lines reuse the
 * exact same translation memory as ordinary UI text. A label that was already translated as a
 * TextView somewhere costs nothing when it later turns up inside a picture.
 *
 * Results are cached by image content hash. Recognition is by far the most expensive thing
 * the module does, and the same images reappear constantly — a scrolling feed re-binds the
 * same thumbnails, and a redrawn frame submits pixel-identical bitmaps — so an image is
 * recognised once and every later sighting is a map lookup.
 */
class OcrRepo(
    context: android.content.Context,
    private val translations: TranslationRepo,
    private val configProvider: () -> HookConfig,
) {

    private val ocr = OcrEngine(context.applicationContext)

    /** Completed results, keyed by "imageHash|dstLang". */
    private val cache = Lru<String, List<TextRegion>>(400)

    /**
     * Images currently being recognised, so a bitmap that is redrawn every frame while its
     * first recognition is still running does not queue a second, third and hundredth job for
     * the same pixels.
     */
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    /**
     * Recognition is single-threaded on purpose. The models are memory-hungry and CPU-bound,
     * and running several at once on a phone makes every one of them slower while starving
     * the host app of the cores it needs to keep scrolling. A queue is the honest structure
     * here: work is bounded by how fast the device can actually recognise, not by how fast
     * requests arrive.
     *
     * The priority is set through [android.os.Process], not just `Thread.priority`. The Java
     * priority barely moves the Linux scheduler; `THREAD_PRIORITY_BACKGROUND` puts the thread in
     * the background cpuset, which is what actually stops recognition from competing with the
     * translations the user is waiting for. Measured on a Taobao feed: visible batches that
     * overlapped recognition ran at 425 ms of concurrent OCR work against 34 ms for the rest, and
     * the worst was a single string taking 1.4 s while an image was being read.
     */
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({
            runCatching {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND +
                        android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE
                )
            }
            r.run()
        }, "ht-ocr").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    private fun key(imageKey: String, dst: String) = "$imageKey|$dst"

    /** Cache-only probe. Safe on a binder thread. */
    fun cached(imageKey: String, dst: String): List<TextRegion>? = cache[key(imageKey, dst)]

    /**
     * Recognises [bitmap], translates every line it finds and hands back the regions.
     *
     * [done] is always called exactly once, including on failure, so the caller can clear its
     * pending state without a timeout.
     */
    fun submit(
        bitmap: Bitmap,
        imageKey: String,
        dst: String,
        done: (List<TextRegion>?) -> Unit,
    ) {
        val k = key(imageKey, dst)

        cache[k]?.let { done(it); return }

        if (inFlight.putIfAbsent(k, true) != null) {
            // Already being worked on. Dropping the duplicate is correct rather than merely
            // convenient: the caller re-probes the cache on its next draw and picks the result
            // up then, so nothing is lost by not queueing a second identical job.
            done(null)
            return
        }

        io.execute {
            val regions = try {
                process(bitmap, dst)
            } catch (t: Throwable) {
                Logs.e("ocr pipeline failed", t)
                null
            } finally {
                inFlight.remove(k)
                // The bitmap arrived over a binder and belongs to this process; the sender's
                // copy is independent. Releasing it here keeps a scrolling feed from holding
                // hundreds of full-size images alive until the next GC.
                runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
            }
            // An empty result is cached deliberately. "This image has no text" is exactly the
            // answer that must not be recomputed, and images without text are the common case.
            if (regions != null) cache[k] = regions
            done(regions)
        }
    }

    private fun process(bitmap: Bitmap, dst: String): List<TextRegion> {
        val cfg = configProvider()
        val started = android.os.SystemClock.elapsedRealtime()

        val lines = ocr.recognize(bitmap, cfg.sourceLang)
        if (lines.isEmpty()) return emptyList()

        // The same guard the text hooks use. An image full of prices, URLs or version numbers
        // should be left alone for the same reasons a TextView holding them is.
        val translatable = lines.filter { TextGuard.shouldTranslate(it.text, cfg) }
        if (translatable.isEmpty()) return emptyList()

        val sources = translatable.map { it.text }
        // Deferred, not direct: this runs on the OCR thread, which is neither of the repo's two
        // lanes, and recognition produces lines in bulk. Translating them straight through would
        // put a picture's worth of text into the engine beside the label the user is waiting for.
        val results = translations.translateDeferred(sources, dst)

        val out = ArrayList<TextRegion>(translatable.size)
        translatable.forEachIndexed { i, line ->
            val translated = results.getOrNull(i) ?: return@forEachIndexed
            if (translated == line.text) return@forEachIndexed
            val (bg, fg) = sampleColors(bitmap, line)
            out += TextRegion(
                left = line.left,
                top = line.top,
                right = line.right,
                bottom = line.bottom,
                source = line.text,
                translated = translated,
                angleDeg = line.angleDeg,
                bgColor = bg,
                fgColor = fg,
            )
        }

        Logs.d(
            "ocr: ${lines.size} line(s), ${out.size} translated in " +
                "${android.os.SystemClock.elapsedRealtime() - started} ms"
        )
        return out
    }

    /**
     * Guesses the background and text colour of a recognised line, so the overlay can repaint
     * it in something close to the original instead of a uniform grey box.
     *
     * The method is deliberately crude: split the line's pixels at the midpoint of their
     * luminance range and average each half. Text is a minority of the pixels inside its own
     * bounding box and contrasts with what surrounds it, which is all this needs to be true.
     * When there is no real contrast — a flat patch the recognizer misread — it falls back to
     * opaque black on white rather than painting invisible text.
     */
    private fun sampleColors(bitmap: Bitmap, line: OcrEngine.Line): Pair<Int, Int> {
        val fallback = android.graphics.Color.WHITE to android.graphics.Color.BLACK
        return try {
            val left = line.left.coerceIn(0, bitmap.width - 1)
            val top = line.top.coerceIn(0, bitmap.height - 1)
            val right = line.right.coerceIn(left + 1, bitmap.width)
            val bottom = line.bottom.coerceIn(top + 1, bitmap.height)
            val w = right - left
            val h = bottom - top
            if (w < 2 || h < 2) return fallback

            // At most ~1500 samples per line regardless of how big the box is: this runs for
            // every line of every image and the answer does not get better with more pixels.
            val step = maxOf(1, kotlin.math.sqrt((w.toDouble() * h) / 1500.0).toInt())
            val px = ArrayList<Int>(1600)
            val row = IntArray(w)
            var y = top
            while (y < bottom) {
                bitmap.getPixels(row, 0, w, left, y, w, 1)
                var x = 0
                while (x < w) {
                    px += row[x]
                    x += step
                }
                y += step
            }
            if (px.size < 4) return fallback

            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            px.forEach { p ->
                val l = luminance(p)
                if (l < lo) lo = l
                if (l > hi) hi = l
            }
            if (hi - lo < 24) return fallback
            val mid = (lo + hi) / 2

            var darkR = 0L; var darkG = 0L; var darkB = 0L; var darkN = 0
            var liteR = 0L; var liteG = 0L; var liteB = 0L; var liteN = 0
            px.forEach { p ->
                if (luminance(p) < mid) {
                    darkR += (p shr 16) and 0xFF; darkG += (p shr 8) and 0xFF; darkB += p and 0xFF; darkN++
                } else {
                    liteR += (p shr 16) and 0xFF; liteG += (p shr 8) and 0xFF; liteB += p and 0xFF; liteN++
                }
            }
            if (darkN == 0 || liteN == 0) return fallback
            val dark = android.graphics.Color.rgb(
                (darkR / darkN).toInt(), (darkG / darkN).toInt(), (darkB / darkN).toInt()
            )
            val lite = android.graphics.Color.rgb(
                (liteR / liteN).toInt(), (liteG / liteN).toInt(), (liteB / liteN).toInt()
            )
            // Whichever group has more pixels is the background; glyphs never fill their box.
            if (darkN >= liteN) dark to lite else lite to dark
        } catch (t: Throwable) {
            fallback
        }
    }

    private fun luminance(p: Int): Int =
        (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    fun clearCache() = cache.clear()

    fun close() {
        runCatching { io.shutdownNow() }
        runCatching { ocr.close() }
    }
}
