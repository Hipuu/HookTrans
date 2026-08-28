package io.hooktrans.xposed

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.hooktrans.core.HookConfig
import io.hooktrans.core.Logs
import io.hooktrans.ipc.TextRegion

/**
 * Text baked into images: screenshots, photographed signs, memes, the captions that make up
 * most of the text in a chat app's shared media.
 *
 * This is the one hook that cannot use the display-only trick the rest of the module is built
 * on. There is no `TransformationMethod` for a Bitmap, and rewriting the pixels of an app's own
 * bitmap would be the single most destructive thing this module could do: apps re-encode,
 * cache, upload and hash the bitmaps they hold, so an edited one propagates out of the device.
 *
 * So nothing is edited. The interception point is `Canvas.drawBitmap`, and the app's bitmap is
 * drawn exactly as the app asked; the translation is painted *over the top of it* immediately
 * afterwards, in the same canvas coordinates. `getDrawable()`, `getBitmap()` and every byte the
 * app can read are untouched by construction.
 *
 * Two consequences worth knowing:
 *
 *  - **Recognition is asynchronous, drawing is not.** A bitmap that has not been read yet draws
 *    plain this frame and the view is invalidated when its regions arrive. Text appears a beat
 *    after the image, never instead of it.
 *  - **It runs on every bitmap that reaches the screen.** Avatars, icons and nine-patches
 *    included. That is the configured behaviour and it has a real battery cost; results are
 *    memoized by pixel content so the price is paid once per distinct image, not per frame.
 */
object ImageHooks {

    private lateinit var cfg: HookConfig

    private val reported = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    private fun reportOnce(where: String, t: Throwable) {
        var root: Throwable = t
        while (root.cause != null && root.cause !== root) root = root.cause!!
        if (!reported.add("$where:${root.javaClass.name}")) return
        Logs.w("$where failed: ${t.javaClass.simpleName} caused by $root", t)
    }

    fun install(config: HookConfig) {
        cfg = config
        DrawTracker.install()

        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    overlay(param)
                } catch (t: Throwable) {
                    reportOnce("drawBitmap hook", t)
                }
            }
        }

        // Same split as CanvasHooks: a hardware-accelerated view records into
        // BaseRecordingCanvas, whose draw methods override Canvas and never call super, so
        // hooking only Canvas would hook a method nothing invokes on a modern device.
        val targets = ArrayList<Class<*>>(2)
        runCatching {
            targets += XposedHelpers.findClass(
                "android.graphics.BaseRecordingCanvas", Canvas::class.java.classLoader
            )
        }.onFailure { Logs.d("BaseRecordingCanvas absent; software canvas only") }
        targets += Canvas::class.java

        var n = 0
        targets.forEach { cls ->
            runCatching { n += XposedBridge.hookAllMethods(cls, "drawBitmap", hook).size }
        }
        Logs.i("Image hooks installed ($n drawBitmap overload(s) on ${targets.size} class(es))")
    }

    // ------------------------------------------------------------------ core

    /**
     * Runs *after* the app's own draw, so the original image is already on the canvas and the
     * translation lands on top of it.
     */
    private fun overlay(param: XC_MethodHook.MethodHookParam) {
        val canvas = param.thisObject as? Canvas ?: return
        val args = param.args
        val bitmap = args.firstOrNull() as? Bitmap ?: return
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        // Below API 31 a hardware bitmap's pixels cannot be read back at all, so there is
        // nothing to recognise and no point fingerprinting it once per frame.
        if (android.os.Build.VERSION.SDK_INT < 31 &&
            bitmap.config == Bitmap.Config.HARDWARE
        ) return

        // Weak, because this closure is parked in HostBridge until recognition finishes and a
        // strong reference would keep a whole destroyed Activity's view tree alive for as long
        // as the queue takes to reach it.
        val ref = DrawTracker.current()?.let { java.lang.ref.WeakReference(it) }
        val found = HostBridge.regionsFor(bitmap) {
            // Late arrival: repaint the one view that showed this image.
            runCatching { ref?.get()?.postInvalidate() }
        } ?: return
        if (found.regions.isEmpty()) return

        // Where the app put the image. This is the only thing the hooked side knows that the
        // engine does not, and it is what turns bitmap pixel coordinates into canvas ones.
        val dst = destination(args, bitmap) ?: return
        val src = source(args, bitmap)
        if (dst.width() <= 0f || dst.height() <= 0f || src.width() <= 0 || src.height() <= 0) return

        val sx = dst.width() / src.width().toFloat()
        val sy = dst.height() / src.height().toFloat()
        if (!sx.isFinite() || !sy.isFinite() || sx <= 0f || sy <= 0f) return

        // The bitmap sent for recognition was downscaled, so regions are in *its* pixel space.
        // Rescaling by the submitted dimensions rather than the original's keeps the boxes on
        // the text regardless of how much the image was shrunk on the way to the engine.
        val rx = bitmap.width / found.srcWidth.toFloat()
        val ry = bitmap.height / found.srcHeight.toFloat()
        if (!rx.isFinite() || !ry.isFinite() || rx <= 0f || ry <= 0f) return

        val box = boxPaint.get() ?: return
        val text = textPaint.get() ?: return

        val saved = canvas.save()
        try {
            // Clip to where the image actually is: a translation must never spill outside the
            // bounds the app gave its own bitmap.
            canvas.clipRect(dst)
            found.regions.forEach { r ->
                drawRegion(canvas, r, dst, src, sx * rx, sy * ry, box, text)
            }
            // Painting is the only step with no other observable trace: OCR and translation
            // both log, so without this a failure to draw looks identical to a success. Kept
            // entirely inside the verbose check — this runs for every bitmap that reaches the
            // screen, and an unconditional volatile write here is a memory barrier per draw.
            if (Logs.verbose) {
                painted += found.regions.size
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastPaintLog > 1000L) {
                    lastPaintLog = now
                    Logs.d("overlay: painted $painted region(s) so far")
                }
            }
        } finally {
            runCatching { canvas.restoreToCount(saved) }
        }
    }

    @Volatile private var painted = 0
    @Volatile private var lastPaintLog = 0L

    private fun drawRegion(
        canvas: Canvas,
        r: TextRegion,
        dst: RectF,
        src: Rect,
        sx: Float,
        sy: Float,
        boxPaint: Paint,
        textPaint: Paint,
    ) {
        // Bitmap space -> canvas space, accounting for the source sub-rectangle when the app
        // drew only part of the image.
        val left = dst.left + (r.left - src.left) * sx
        val top = dst.top + (r.top - src.top) * sy
        val right = dst.left + (r.right - src.left) * sx
        val bottom = dst.top + (r.bottom - src.top) * sy
        val w = right - left
        val h = bottom - top
        if (w <= 1f || h <= 1f) return
        if (!left.isFinite() || !top.isFinite() || !w.isFinite() || !h.isFinite()) return

        val saved = canvas.save()
        try {
            // Slanted text: rotate about the centre of the box so the overlay lies along the
            // same baseline the photograph does.
            if (kotlin.math.abs(r.angleDeg) > 1f) {
                canvas.rotate(r.angleDeg, left + w / 2f, top + h / 2f)
            }

            val box = RectF(left, top, right, bottom)
            boxPaint.color = opaque(r.bgColor, fallback = 0xFF202020.toInt())

            if (cfg.overlayShowOriginal) {
                // Diagnostic mode: leave the original visible and put the translation directly
                // beneath its box, so a misread is obvious at a glance.
                box.offset(0f, h)
                boxPaint.alpha = 220
            } else {
                boxPaint.alpha = 255
            }
            canvas.drawRect(box, boxPaint)

            textPaint.color = opaque(r.fgColor, fallback = 0xFFFFFFFF.toInt())
            drawFitted(canvas, r.translated, box, textPaint)
        } finally {
            runCatching { canvas.restoreToCount(saved) }
        }
    }

    /**
     * Draws [text] as large as it can inside [box].
     *
     * The box is the size of the *original* line, and a translation is usually longer, so the
     * type is shrunk to fit rather than allowed to overflow onto the picture beside it. Below
     * [MIN_TEXT_PX] shrinking stops producing something readable, so the text is ellipsized
     * instead — an unreadable smear of glyphs is worse than a truncated phrase.
     */
    private fun drawFitted(canvas: Canvas, text: String, box: RectF, paint: Paint) {
        if (text.isEmpty()) return
        val avail = box.width() - PAD_PX * 2f
        if (avail <= 0f) return
        var size = box.height() * 0.78f
        if (size < 1f) return

        paint.textSize = size
        var width = paint.measureText(text)
        if (width > avail) {
            size = maxOf(MIN_TEXT_PX, size * (avail / width))
            paint.textSize = size
            width = paint.measureText(text)
        }

        var out = text
        if (width > avail) {
            // Still too wide at the floor size: cut it and mark the cut.
            val keep = paint.breakText(text, true, avail - paint.measureText("…"), null)
            if (keep <= 0) return
            out = text.substring(0, keep) + "…"
        }

        val fm = paint.fontMetrics
        // Centre the glyph box vertically: ascent is negative, so this puts the visual middle
        // of the line on the middle of the region rather than sitting it on the baseline.
        val baseline = box.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(out, box.left + PAD_PX, baseline, paint)
    }

    // ------------------------------------------------------------------ geometry

    /**
     * Where the app is drawing the bitmap, in canvas coordinates.
     *
     * `drawBitmap` has several overloads and they specify the destination three different ways:
     * a dst Rect/RectF, a left/top pair, or a Matrix. Deciding by argument shape rather than by
     * signature keeps this working on ROMs that add their own variants.
     */
    private fun destination(args: Array<Any?>, bitmap: Bitmap): RectF? {
        // drawBitmap(bitmap, src, dst, paint): dst is the third argument.
        if (args.size >= 3) {
            (args[2] as? RectF)?.let { return RectF(it) }
            (args[2] as? Rect)?.let { return RectF(it) }
        }

        // drawBitmap(bitmap, dst, paint) — no source rect.
        (args.getOrNull(1) as? RectF)?.let { return RectF(it) }
        (args.getOrNull(1) as? Rect)?.let { return RectF(it) }

        // drawBitmap(bitmap, left, top, paint)
        val l = args.getOrNull(1)
        val t = args.getOrNull(2)
        if (l is Float && t is Float) {
            return RectF(l, t, l + bitmap.width, t + bitmap.height)
        }

        // drawBitmap(bitmap, matrix, paint): map the bitmap's own bounds through it. A rotating
        // or skewing matrix collapses to its bounding box here, which is close enough to place
        // an overlay and far cheaper than carrying the full transform through.
        (args.getOrNull(1) as? Matrix)?.let { m ->
            val r = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            m.mapRect(r)
            return r
        }
        return null
    }

    /**
     * Which part of the bitmap the app is drawing; the whole thing unless a src Rect is given.
     * A src rect only exists in the overloads that also carry a dst, so it is a Rect at index 1
     * followed by a rectangle at index 2.
     */
    private fun source(args: Array<Any?>, bitmap: Bitmap): Rect {
        val whole = Rect(0, 0, bitmap.width, bitmap.height)
        if (args.size < 3) return whole
        val src = args[1] as? Rect ?: return whole
        val dst = args[2]
        if (dst !is Rect && dst !is RectF) return whole
        // A null src means the whole bitmap; a degenerate one is not usable.
        if (src.width() <= 0 || src.height() <= 0) return whole
        return src
    }

    private fun opaque(color: Int, fallback: Int): Int =
        if (color == 0) fallback else color or 0xFF000000.toInt()

    /**
     * Paints are per-thread, not shared. Drawing happens on the main thread for most views but
     * on a RenderThread or a dedicated thread for SurfaceView, TextureView and every app that
     * draws off the UI thread; one shared mutable Paint would be read by one frame while
     * another frame wrote to it.
     */
    private val boxPaint = ThreadLocal.withInitial {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    }

    private val textPaint = ThreadLocal.withInitial {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
    }

    private const val MIN_TEXT_PX = 9f
    private const val PAD_PX = 2f
}
