package io.hooktrans.engine

import io.hooktrans.core.Engines
import io.hooktrans.core.Logs
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Keyless fallback. Requires an explicit source language, so "auto" resolves to English. */
class MyMemoryEngine : TranslationEngine {

    override val id = Engines.MYMEMORY
    override val needsNetwork = true

    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "ht-mymemory").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    override fun translate(texts: List<String>, src: String, dst: String): List<String?> {
        if (texts.isEmpty()) return emptyList()
        val sl = if (src.isBlank() || src == "auto") "en" else src
        val tasks = texts.map { t -> Callable { one(t, sl, dst) } }
        return try {
            pool.invokeAll(tasks, 30, TimeUnit.SECONDS).map { f ->
                runCatching { if (f.isCancelled) null else f.get() }.getOrNull()
            }
        } catch (t: Throwable) {
            Logs.w("mymemory batch failed", t)
            texts.map { null }
        }
    }

    private fun one(text: String, src: String, dst: String): String? {
        if (text.length > 500) return null
        val url = "https://api.mymemory.translated.net/get?q=${Http.enc(text)}" +
            "&langpair=${Http.enc(src)}|${Http.enc(dst)}"
        val body = Http.get(url) ?: return null
        return try {
            JSONObject(body).optJSONObject("responseData")
                ?.optString("translatedText")
                ?.takeIf { it.isNotBlank() && !it.startsWith("PLEASE SELECT TWO DISTINCT") }
        } catch (t: Throwable) {
            null
        }
    }

    override fun close() {
        runCatching { pool.shutdownNow() }
    }
}
