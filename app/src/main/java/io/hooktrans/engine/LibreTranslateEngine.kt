package io.hooktrans.engine

import io.hooktrans.core.Engines
import io.hooktrans.core.Logs
import org.json.JSONArray
import org.json.JSONObject

/** Batch-capable. Point it at a self-hosted instance for unlimited, private translation. */
class LibreTranslateEngine(
    private val endpoint: String,
    private val apiKey: String,
) : TranslationEngine {

    override val id = Engines.LIBRE
    override val needsNetwork = true

    override fun translate(texts: List<String>, src: String, dst: String): List<String?> {
        if (texts.isEmpty()) return emptyList()
        val base = endpoint.trim().trimEnd('/').ifEmpty { "https://libretranslate.com" }
        val payload = JSONObject().apply {
            put("q", JSONArray(texts))
            put("source", if (src.isBlank()) "auto" else src.substringBefore('-'))
            put("target", dst.substringBefore('-'))
            put("format", "text")
            if (apiKey.isNotBlank()) put("api_key", apiKey)
        }.toString()

        val body = Http.postJson("$base/translate", payload) ?: return texts.map { null }
        return try {
            val o = JSONObject(body)
            when (val tt = o.opt("translatedText")) {
                is JSONArray -> List(texts.size) { i -> tt.optString(i, "").takeIf { it.isNotBlank() } }
                is String -> if (texts.size == 1) listOf(tt.takeIf { it.isNotBlank() }) else texts.map { null }
                else -> texts.map { null }
            }
        } catch (t: Throwable) {
            Logs.w("libretranslate parse failed", t)
            texts.map { null }
        }
    }
}
