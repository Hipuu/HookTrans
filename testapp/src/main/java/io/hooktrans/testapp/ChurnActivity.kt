package io.hooktrans.testapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * A video-frame simulator for the image hook.
 *
 * One Bitmap object is redrawn on every invalidate with its pixels mutated each time, the way
 * a video surface or an animated banner presents itself to `Canvas.drawBitmap`. The visible
 * caption never changes; a corner pixel block does, so the module sees a constant object whose
 * *content* is new every frame — the exact input that makes per-content-hash memoization
 * useless and recognition cost unbounded.
 *
 * The point is measurability: without a churn guard the engine runs one OCR job per frame
 * (~10/s here, each a bitmap copy plus 50–150 ms of recognition) for results that are stale on
 * arrival. With the guard, the surface is banned after a few frames and job count collapses.
 * The logcat line to watch is "ocr:".
 */
class ChurnActivity : AppCompatActivity() {

    private lateinit var surface: Bitmap
    private lateinit var frameView: View

    private var frame = 0

    private val loop = object : Runnable {
        override fun run() {
            if (frame < MAX_FRAMES) {
                drawNextFrame()
                frameView.invalidate()
                handler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surface = Bitmap.createBitmap(FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.ARGB_8888)
        drawNextFrame()

        frameView = object : View(this) {
            private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

            override fun onDraw(canvas: Canvas) {
                canvas.drawBitmap(surface, 0f, 0f, paint)
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }
        root.addView(TextView(this).apply {
            text = "Video-frame churn target: the bitmap below mutates every " +
                "${FRAME_INTERVAL_MS} ms. Watch the engine's \"ocr:\" job count."
            setPadding(0, 0, 0, 24)
        })
        root.addView(frameView, LinearLayout.LayoutParams(FRAME_WIDTH * 2, FRAME_HEIGHT * 2))
        setContentView(root)
    }

    private fun drawNextFrame() {
        // Stable caption in the source language, changing corner: the text content is constant
        // while the pixels are not, which is what a talking-head video or a ticker looks like.
        val c = Canvas(surface)
        c.drawColor(if (frame % 2 == 0) 0xFF101010.toInt() else 0xFF181818.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 64f; color = Color.WHITE }
        c.drawText("限时特惠 每日更新", 24f, 96f, p)
        // The churn: a fresh corner block per frame. setPixel bumps generationId, which is
        // exactly the signal the module uses to tell a redrawn photo from new content.
        for (i in 0 until 8) {
            surface.setPixel(frame % (FRAME_WIDTH - 8), i, 0xFF000000L.toInt() or (frame * 37 + i * 11))
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(loop)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(loop)
    }

    private companion object {
        const val FRAME_WIDTH = 480
        const val FRAME_HEIGHT = 270
        const val FRAME_INTERVAL_MS = 100L

        /** Bounded so a forgotten screen stops churning on its own. */
        const val MAX_FRAMES = 600
    }
}
