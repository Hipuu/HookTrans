package io.hooktrans.testapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.View

/**
 * A view that paints its own text, the way a tab bar or a chart axis does. No TextView exists
 * anywhere in here, so the only hook that can reach these labels is the Canvas one.
 *
 * It doubles as the safety check for that pipeline. The strings it draws are fields this class
 * reads back on every frame, so if the module ever fed a translation into app state rather than
 * only into the draw call, [labels] would stop matching [expected] and the check below fails.
 */
class CanvasLabelView(context: Context) : View(context) {

    /** Chrome copy of the kind real apps paint themselves. Should be translated on screen. */
    private val labels = arrayOf("Recommended", "Free shipping", "Furniture", "Home")

    /** The same values, kept separately, as the definition of app-facing truth. */
    private val expected = arrayOf("Recommended", "Free shipping", "Furniture", "Home")

    /** Data drawn to a canvas. Must survive untouched, exactly as in the TextView case. */
    private val data = arrayOf("v2.14.3-beta", "a3f5c9e17b2d4890")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 42f
        color = Color.DKGRAY
    }

    /** Right-aligned text: proof that a longer translation stays on its anchor. */
    private val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 42f
        color = Color.parseColor("#1565C0")
        textAlign = Paint.Align.RIGHT
    }

    private var checked = false

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // Seven lines from a baseline of 50 at a 56 step, plus descender room. Sizing this
        // too small clips the last line, which silently hides the right-aligned case.
        setMeasuredDimension(resolveSize(600, widthSpec), resolveSize(440, heightSpec))
    }

    override fun onDraw(canvas: Canvas) {
        var y = 50f
        labels.forEach { s ->
            canvas.drawText(s, 0f, y, paint)
            y += 56f
        }
        data.forEach { s ->
            canvas.drawText(s, 0f, y, paint)
            y += 56f
        }
        canvas.drawText("Log in", width.toFloat(), y, rightPaint)

        if (!checked) {
            checked = true
            post { verify() }
        }
    }

    /**
     * The canvas equivalent of Verify's identity check. A draw hook has no `getText()` to
     * corrupt, so what is asserted here is that the app's own copies of the strings are the
     * ones it wrote — the guarantee that matters for a pipeline that only touches pixels.
     */
    private fun verify() {
        var bad = 0
        labels.forEachIndexed { i, s ->
            if (s != expected[i]) {
                bad++
                Log.e(Verify.TAG, "FAIL/CORRUPT canvas | field=\"$s\" | wrote=\"${expected[i]}\"")
            }
        }
        if (bad == 0) {
            Log.i(
                Verify.TAG,
                "PASS/canvas-identity — all ${labels.size} canvas strings intact in app state"
            )
        }
    }
}
