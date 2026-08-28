package io.hooktrans.engine

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.hooktrans.core.Logs
import java.util.concurrent.TimeUnit

/**
 * Optical character recognition for the image-translation path.
 *
 * Runs only in the `:engine` process. The models are bundled rather than delivered by Play
 * Services, so this works on de-Googled ROMs — a meaningful share of the LSPosed user base —
 * at the cost of APK size.
 *
 * ML Kit has no single recognizer that reads every script: the Latin, Chinese, Japanese,
 * Korean and Devanagari models are separate, and each one only understands its own. Since the
 * script of an arbitrary screenshot is unknown, [recognize] picks a recognizer from the
 * configured source language when one is set, and otherwise races the scripts in a fixed
 * order and keeps the most convincing answer.
 */
class OcrEngine(private val context: Context) {

    /** Built lazily and kept: constructing a recognizer loads its model off disk. */
    private val recognizers = HashMap<String, TextRecognizer>()

    /**
     * ML Kit normally bootstraps itself from `MlKitInitProvider`, a ContentProvider declared in
     * its manifest. Android only instantiates providers in an application's *default* process,
     * and this class runs in `:engine` — so nothing ever initializes it here and every
     * `getClient` call fails with "MlKitContext has not been initialized". [MlKitInit] does it
     * explicitly; it is shared with the translation engine, which has the same problem.
     */
    @Synchronized
    private fun recognizer(script: String): TextRecognizer? = try {
        if (!MlKitInit.ensure(context)) null else
        recognizers.getOrPut(script) {
            when (script) {
                CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
    } catch (t: Throwable) {
        Logs.w("could not build the $script recognizer", t)
        null
    }

    /**
     * One recognised line: the text, where it sits in the submitted bitmap, and its rotation.
     */
    data class Line(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val angleDeg: Float,
    )

    /**
     * Reads [bitmap] and returns its text lines.
     *
     * [srcLang] narrows the work to a single recognizer when the user has pinned a source
     * language. On "auto" every script is tried and the best result wins, which costs more but
     * is the only correct answer when the image could hold anything.
     */
    fun recognize(bitmap: Bitmap, srcLang: String): List<Line> {
        val scripts = scriptsFor(srcLang)
        var best: List<Line> = emptyList()
        var bestScore = 0

        for (script in scripts) {
            val lines = runScript(script, bitmap)
            val score = score(lines)
            if (score > bestScore) {
                best = lines
                bestScore = score
            }
            // A confident read makes trying the remaining scripts pointless. The threshold is
            // deliberately low: a wrong-script recognizer typically returns nothing at all
            // rather than a few characters, so any substantial read is the right one.
            if (bestScore >= EARLY_ACCEPT) break
        }
        return best
    }

    private fun runScript(script: String, bitmap: Bitmap): List<Line> {
        val rec = recognizer(script) ?: return emptyList()
        return try {
            val input = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(rec.process(input), OCR_TIMEOUT_S, TimeUnit.SECONDS)
            result.textBlocks
                .asSequence()
                .flatMap { it.lines.asSequence() }
                .mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    val text = line.text.trim()
                    if (text.isEmpty()) return@mapNotNull null
                    Line(
                        text = text,
                        left = box.left,
                        top = box.top,
                        right = box.right,
                        bottom = box.bottom,
                        angleDeg = line.angle,
                    )
                }
                .toList()
        } catch (t: Throwable) {
            Logs.d("ocr[$script] failed: ${t.message}")
            emptyList()
        }
    }

    /**
     * How much real text a recognizer found. Used to choose between scripts when the source
     * language is "auto".
     *
     * Letters are counted rather than lines, because a recognizer pointed at the wrong script
     * tends to emit a scatter of one- and two-character noise: that would win on line count
     * and lose badly on letter count.
     */
    private fun score(lines: List<Line>): Int =
        lines.sumOf { l -> l.text.count { Character.isLetterOrDigit(it) } }

    /**
     * Which recognizers to try, most likely first.
     *
     * A pinned source language maps to exactly one script. Latin leads the "auto" order
     * because it also reads the digits and punctuation that appear in every other script's
     * images, so it is the most useful single fallback.
     */
    private fun scriptsFor(srcLang: String): List<String> {
        val bare = srcLang.substringBefore('-').lowercase()
        return when (bare) {
            "zh" -> listOf(CHINESE)
            "ja" -> listOf(JAPANESE)
            "ko" -> listOf(KOREAN)
            "hi", "mr", "ne" -> listOf(DEVANAGARI)
            "", "auto" -> listOf(LATIN, CHINESE, JAPANESE, KOREAN, DEVANAGARI)
            else -> listOf(LATIN)
        }
    }

    @Synchronized
    fun close() {
        recognizers.values.forEach { runCatching { it.close() } }
        recognizers.clear()
    }

    private companion object {
        const val LATIN = "latin"
        const val CHINESE = "zh"
        const val JAPANESE = "ja"
        const val KOREAN = "ko"
        const val DEVANAGARI = "deva"

        const val OCR_TIMEOUT_S = 20L

        /** Letters found that make a script confident enough to stop probing the others. */
        const val EARLY_ACCEPT = 8
    }
}
