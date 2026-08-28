package io.hooktrans.engine

import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import io.hooktrans.core.Engines
import io.hooktrans.core.Langs
import io.hooktrans.core.Logs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Fully offline once the language pair has been downloaded. No key, no quota, no data
 * leaving the device. The first request for a pair blocks on a model download; callers are
 * always on a background thread and the UI has an explicit "download model" action.
 */
class MlKitEngine(private val context: android.content.Context) : TranslationEngine {

    override val id = Engines.MLKIT
    override val needsNetwork = false

    private val translators = ConcurrentHashMap<String, Translator>()
    private val ready = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var identifier: LanguageIdentifier? = null

    override fun translate(texts: List<String>, src: String, dst: String): List<String?> {
        if (texts.isEmpty()) return emptyList()
        // Nothing in ML Kit works until this has happened, and it cannot happen implicitly in
        // this process. See MlKitInit.
        if (!MlKitInit.ensure(context)) return texts.map { null }
        val target = TranslateLanguage.fromLanguageTag(Langs.bare(dst))
            ?: return texts.map { null }

        // Two passes rather than one. `tr.translate` returns a Task that ML Kit runs on its own
        // executor, so starting every text first and only then awaiting lets the batch overlap:
        // awaiting inside the loop would serialise it and make a screenful of labels cost the
        // sum of its parts. That is the difference between a batch of eleven taking ~1.9 s and
        // taking about as long as its slowest single line.
        val pending = arrayOfNulls<Task<String>>(texts.size)
        val direct = arrayOfNulls<String>(texts.size)
        val sources = resolveSources(src, texts)

        texts.forEachIndexed { i, text ->
            try {
                val source = sources[i] ?: return@forEachIndexed
                // Same language in and out: nothing to do, and no task to wait for.
                if (source == target) { direct[i] = text; return@forEachIndexed }
                val tr = translatorFor(source, target) ?: return@forEachIndexed
                pending[i] = tr.translate(text)
            } catch (t: Throwable) {
                Logs.w("mlkit translate failed to start", t)
            }
        }

        // A shared deadline, not one per text: the tasks are already running concurrently, so
        // per-text timeouts would add up to a far longer worst case than intended.
        val deadline = SystemClock.elapsedRealtime() + TRANSLATE_TIMEOUT_MS
        return texts.indices.map { i ->
            direct[i]?.let { return@map it }
            val task = pending[i] ?: return@map null
            val left = deadline - SystemClock.elapsedRealtime()
            try {
                if (left <= 0) {
                    // Out of budget, but the task may have finished while earlier ones were
                    // awaited; take a completed result rather than discarding it.
                    if (task.isSuccessful) task.result?.takeIf { it.isNotBlank() } else null
                } else {
                    Tasks.await(task, left, TimeUnit.MILLISECONDS)?.takeIf { it.isNotBlank() }
                }
            } catch (t: Throwable) {
                Logs.w("mlkit translate failed", t)
                null
            }
        }
    }

    /**
     * The source language for each text, in the same order.
     *
     * A pinned source language is the same for the whole batch and costs nothing to resolve. On
     * "auto" every text needs its own identification pass, so those are started together and
     * awaited afterwards for the same reason the translations are.
     */
    private fun resolveSources(src: String, texts: List<String>): Array<String?> {
        if (src.isNotBlank() && src != "auto") {
            val fixed = TranslateLanguage.fromLanguageTag(Langs.bare(src))
            return Array(texts.size) { fixed }
        }
        val ident = identifier ?: synchronized(this) {
            identifier ?: LanguageIdentification.getClient().also { identifier = it }
        }
        val tasks = texts.map { runCatching { ident.identifyLanguage(it) }.getOrNull() }
        val deadline = SystemClock.elapsedRealtime() + IDENTIFY_TIMEOUT_MS
        return Array(texts.size) { i ->
            val task = tasks[i] ?: return@Array null
            val left = deadline - SystemClock.elapsedRealtime()
            val tag = runCatching {
                if (left <= 0) task.takeIf { it.isSuccessful }?.result
                else Tasks.await(task, left, TimeUnit.MILLISECONDS)
            }.getOrNull() ?: return@Array null
            if (tag == "und") null else TranslateLanguage.fromLanguageTag(tag)
        }
    }

    private fun translatorFor(source: String, target: String): Translator? {
        val key = "$source>$target"
        val tr = translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            )
        }
        if (ready[key] != true) {
            val ok = runCatching {
                Tasks.await(
                    tr.downloadModelIfNeeded(DownloadConditions.Builder().build()),
                    180, TimeUnit.SECONDS
                )
                true
            }.getOrElse {
                Logs.w("mlkit model download failed for $key", it)
                false
            }
            if (!ok) return null
            ready[key] = true
        }
        return tr
    }

    /** Used by the settings screen so a user can pre-download before going offline. */
    fun ensureModel(src: String, dst: String): String {
        if (!MlKitInit.ensure(context)) return "ML Kit could not start in the engine process"
        val source = TranslateLanguage.fromLanguageTag(Langs.bare(src))
            ?: return "ML Kit has no model for source '$src'"
        val target = TranslateLanguage.fromLanguageTag(Langs.bare(dst))
            ?: return "ML Kit has no model for target '$dst'"
        return if (translatorFor(source, target) != null) "Model $source > $target ready"
        else "Model download failed (needs network the first time)"
    }

    override fun close() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
        ready.clear()
        runCatching { identifier?.close() }
        identifier = null
    }

    private companion object {
        /** Budget for a whole batch, not per text: the translations run concurrently. */
        const val TRANSLATE_TIMEOUT_MS = 20_000L
        const val IDENTIFY_TIMEOUT_MS = 5_000L
    }
}
