package io.hooktrans.core

object Langs {

    val TARGETS: List<Pair<String, String>> = listOf(
        "en" to "English",
        "ar" to "Arabic",
        "bn" to "Bengali",
        "bg" to "Bulgarian",
        "zh-CN" to "Chinese (Simplified)",
        "zh-TW" to "Chinese (Traditional)",
        "hr" to "Croatian",
        "cs" to "Czech",
        "da" to "Danish",
        "nl" to "Dutch",
        "fi" to "Finnish",
        "fr" to "French",
        "de" to "German",
        "el" to "Greek",
        "he" to "Hebrew",
        "hi" to "Hindi",
        "hu" to "Hungarian",
        "id" to "Indonesian",
        "it" to "Italian",
        "ja" to "Japanese",
        "ko" to "Korean",
        "ms" to "Malay",
        "no" to "Norwegian",
        "fa" to "Persian",
        "pl" to "Polish",
        "pt" to "Portuguese",
        "ro" to "Romanian",
        "ru" to "Russian",
        "sr" to "Serbian",
        "sk" to "Slovak",
        "es" to "Spanish",
        "sv" to "Swedish",
        "th" to "Thai",
        "tr" to "Turkish",
        "uk" to "Ukrainian",
        "ur" to "Urdu",
        "vi" to "Vietnamese",
    )

    val SOURCES: List<Pair<String, String>> = listOf("auto" to "Detect automatically") + TARGETS

    fun nameOf(code: String): String =
        SOURCES.firstOrNull { it.first.equals(code, true) }?.second ?: code

    fun indexOfTarget(code: String) =
        TARGETS.indexOfFirst { it.first.equals(code, true) }.coerceAtLeast(0)

    fun indexOfSource(code: String) =
        SOURCES.indexOfFirst { it.first.equals(code, true) }.coerceAtLeast(0)

    /** ML Kit wants bare ISO-639-1, no region. */
    fun bare(code: String): String = code.substringBefore('-').lowercase()
}
