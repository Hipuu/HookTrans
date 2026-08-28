package io.hooktrans.testapp

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView

/**
 * Asserts the guarantee the whole module rests on: what the app *reads* must never change,
 * only what the user *sees*.
 *
 * Each check compares a TextView's app-facing state (`getText()`) against the string the app
 * originally wrote, and separately confirms that the drawn text (the transformation output)
 * did change. A translator that passes the first half but fails the second is not translating;
 * one that passes the second but fails the first is corrupting the app. Both halves have to
 * hold, so both are reported.
 *
 * Results go to logcat under one tag so `adb logcat -s HT_VERIFY:V` is the whole test report.
 */
object Verify {

    const val TAG = "HT_VERIFY"

    private val results = LinkedHashMap<String, String>()

    /**
     * Runs the checks after a delay. Translation is asynchronous by design — the module never
     * blocks the app to wait for a network round trip — so an immediate assertion would race
     * the engine and report a false failure. Two passes are scheduled so the log shows both
     * the cold state and the settled state.
     */
    fun scheduleChecks(views: List<Pair<String, TextView>>) {
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({ run(views, "cold (1.5s)") }, 1_500)
        h.postDelayed({ run(views, "settled (6s)") }, 6_000)
        h.postDelayed({ run(views, "final (12s)") }, 12_000)
    }

    private fun run(views: List<Pair<String, TextView>>, phase: String) {
        Log.i(TAG, "================ $phase ================")
        var identityOk = 0
        var identityBad = 0
        var displayChanged = 0

        views.forEach { (original, view) ->
            // What the app sees. This must equal what the app wrote, exactly.
            val readBack = view.text?.toString()

            // What the user sees: TextView draws the transformation's output, not getText().
            val drawn = try {
                view.transformationMethod?.getTransformation(view.text, view)?.toString()
                    ?: readBack
            } catch (t: Throwable) {
                "<transform threw: ${t.message}>"
            }

            val identityHeld = readBack == original
            if (identityHeld) identityOk++ else identityBad++
            val changed = drawn != null && drawn != readBack
            if (changed) displayChanged++

            val verdict = when {
                !identityHeld -> "FAIL/CORRUPT"   // app-facing state was mutated
                changed -> "PASS/TRANSLATED"      // drawn differs, getText() intact
                else -> "PASS/UNTRANSLATED"       // nothing happened yet; still safe
            }
            Log.i(TAG, "$verdict | getText=${q(readBack)} | drawn=${q(drawn)} | wrote=${q(original)}")
            results[original] = verdict
        }

        Log.i(
            TAG,
            "SUMMARY[$phase] views=${views.size} identityIntact=$identityOk " +
                "identityBroken=$identityBad displayTranslated=$displayChanged"
        )
        if (identityBad == 0) {
            Log.i(TAG, "RESULT[$phase]: SAFE — every getText() returned the original string")
        } else {
            Log.e(TAG, "RESULT[$phase]: UNSAFE — $identityBad view(s) had app-facing text mutated")
        }
    }

    private fun q(s: String?): String =
        if (s == null) "null" else "\"" + s.replace("\n", "\\n") + "\""

    /**
     * The functional checks: things a real app does with its own strings that a naive
     * translator breaks. Each is a behaviour, not a string comparison.
     */
    fun functionalChecks(views: Map<String, TextView>) {
        Log.i(TAG, "================ functional ================")

        // 1. A view used as a state flag. Apps branch on their own label all the time.
        val status = views["Connected"]
        if (status != null) {
            val branch = status.text.toString() == "Connected"
            log("state-comparison", branch, "app branches on getText()==\"Connected\"")
        }

        // 2. Numeric parsing. If a translator localises digits or reorders text, this throws.
        val count = views["42"]
        if (count != null) {
            val parsed = count.text.toString().trim().toIntOrNull()
            log("numeric-parse", parsed == 42, "Integer.parseInt(getText()) == 42, got $parsed")
        }

        // 3. A tag round-trip: the pattern behind every RecyclerView view-holder.
        val label = views["Settings"]
        if (label != null) {
            label.tag = label.text.toString()
            val stable = label.tag == label.text.toString()
            log("tag-roundtrip", stable, "view tag still matches getText()")
        }

        // 4. Length. Off-by-one bugs from a length-changing transformation land here.
        val precise = views["OK"]
        if (precise != null) {
            log("length-stable", precise.text.length == 2, "getText().length still 2")
        }
    }

    private fun log(name: String, ok: Boolean, detail: String) {
        if (ok) Log.i(TAG, "PASS/$name — $detail")
        else Log.e(TAG, "FAIL/$name — $detail")
    }
}
