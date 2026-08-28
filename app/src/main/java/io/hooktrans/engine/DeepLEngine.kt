package io.hooktrans.engine

import io.hooktrans.core.Engines
import io.hooktrans.core.Logs
import org.json.JSONArray
import org.json.JSONObject

class DeepLEngine(private val apiKey: String) : TranslationEngine {

    override val id = Engines.DEEPL
    override val needsNetwork = true

    override fun translate(texts: List<String>, src: String, dst: String): List<String?> {
        if (texts.isEmpty() || apiKey.isBlank()) return texts.map { null }

        // Free keys end in ":fx" and use a different host.
        val host = if (apiKey.trim().endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        val payload = JSONObject().apply {
            put("text", JSONArray(texts))
            put("target_lang", deepLCode(dst))
            if (src.isNotBlank() && src != "auto") put("source_lang", deepLCode(src))
        }.toString()

        val body = Http.postJson(
            "$host/v2/translate", payload,
            mapOf("Authorization" to "DeepL-Auth-Key ${apiKey.trim()}")
        ) ?: return texts.map { null }

        return try {
            val arr = JSONObject(body).optJSONArray("translations") ?: return texts.map { null }
            List(texts.size) { i -> arr.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() } }
        } catch (t: Throwable) {
            Logs.w("deepl parse failed", t)
            texts.map { null }
        }
    }

    private fun deepLCode(code: String): String = when (code.lowercase()) {
        "zh-cn", "zh" -> "ZH"
        "zh-tw" -> "ZH"
        "pt" -> "PT-PT"
        "pt-br" -> "PT-BR"
        "en" -> "EN-US"
        else -> code.substringBefore('-').uppercase()
    }
}
