package io.hooktrans.core

/**
 * Decides whether a string is *display copy* or *data*. Everything that looks like data is
 * refused, because translating data is how a translator breaks an app: a URL that no longer
 * resolves, a format template whose %s vanished, an id used as a map key.
 *
 * The rule of thumb encoded here: translate only what a human would read as a sentence or a
 * label, and refuse anything ambiguous.
 */
object TextGuard {

    private val URLISH = Regex(
        """(?:https?://|ftp://|www\.\w|[\w.+-]+@[\w-]+\.[a-z]{2,}|\bcontent://|\bfile://|/(?:data|system|storage|sdcard|proc|dev)/)""",
        RegexOption.IGNORE_CASE
    )

    /** com.example.thing, java.lang.String, some.thing.Class$Inner */
    private val PACKAGEISH = Regex("""^[A-Za-z][\w$]*(?:\.[A-Za-z_][\w$]*){2,}$""")

    /**
     * printf/ICU/handlebars templates. Present means the string has not been formatted yet.
     *
     * Every `}` is escaped. Android's regex engine is ICU, not the JDK's: the JDK tolerates a
     * bare closing brace outside a quantifier, ICU rejects it with a PatternSyntaxException.
     * Because these patterns are `object` fields, one bad pattern fails the class initializer
     * and takes down the whole guard — and with it every translation pipeline — so this must
     * stay valid under ICU rather than merely compiling on the desktop JVM.
     */
    private val TEMPLATE = Regex("""%(?:\d+\$)?[-+ #0]*\d*(?:\.\d+)?[sdfxXeEgGoc@%]|\{\w*\}|\$\{[^}]*\}|<[a-zA-Z/][^>]*>""")

    private val HEXID = Regex("""^[0-9a-fA-F]{8,}$""")
    private val UUIDISH = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")
    private val VERSIONISH = Regex("""^[vV]?\d+(?:\.\d+){1,4}(?:[-+][\w.]+)?$""")
    private val BASE64ISH = Regex("""^[A-Za-z0-9+/]{24,}={0,2}$""")

    /** 12:30, 3:45 PM, 2024-01-31, 31/01/2024, 1.234,56 kB, -12 %, $19.99 */
    private val MOSTLY_NON_LETTER = Regex("""^[^\p{L}]*$""")

    private const val MAX_UPPER_ACRONYM = 4

    fun shouldTranslate(raw: CharSequence?, cfg: HookConfig): Boolean {
        if (raw == null) return false
        val len = raw.length
        if (len < cfg.minChars || len > cfg.maxChars) return false

        val s = raw.toString()
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return false
        if (cfg.neverTranslate.contains(trimmed)) return false

        // At least two letters, otherwise there is nothing to translate.
        var letters = 0
        for (c in trimmed) {
            if (Character.isLetter(c)) {
                letters++
                if (letters >= 2) break
            }
        }
        if (letters < 2) return false

        if (MOSTLY_NON_LETTER.matches(trimmed)) return false
        if (URLISH.containsMatchIn(trimmed)) return false
        if (TEMPLATE.containsMatchIn(trimmed)) return false
        if (PACKAGEISH.matches(trimmed)) return false
        if (UUIDISH.matches(trimmed)) return false
        if (HEXID.matches(trimmed)) return false
        if (VERSIONISH.matches(trimmed)) return false
        if (BASE64ISH.matches(trimmed)) return false

        // Short all-caps tokens are almost always acronyms, units or state flags (OK, GPS,
        // MB, ID). Translating them reads worse than leaving them.
        //
        // The test is "every letter is uppercase", not "no letter is lowercase". In a caseless
        // script the two are not the same: no CJK ideograph is lowercase, so the weaker test
        // silently discards every Chinese, Japanese and Korean label of four characters or
        // fewer — which is most of them, since 推荐 / 包邮 / 家具 are complete words there.
        // Requiring a real uppercase letter keeps the rule where case exists and disables it
        // where it has no meaning, and mixed tokens like "3C数码" stay eligible because 数 is
        // a letter that is not uppercase.
        if (trimmed.length <= MAX_UPPER_ACRONYM && trimmed.none { it.isWhitespace() } &&
            trimmed.any { it.isUpperCase() } && trimmed.all { !it.isLetter() || it.isUpperCase() }
        ) return false

        // A single token that mixes case in the middle is an identifier (camelCase,
        // snake_case with digits, resource names).
        if (!trimmed.any { it.isWhitespace() }) {
            if (trimmed.contains('_') && trimmed.none { it.isUpperCase() } && trimmed.length > 3) return false
            if (Regex("""^[a-z]+(?:[A-Z][a-z0-9]+){2,}$""").matches(trimmed)) return false
        }

        // Letters must be a meaningful share of the string; "3 x 1.5 V" is data.
        val letterCount = trimmed.count { Character.isLetter(it) }
        if (letterCount * 3 < trimmed.length) return false

        return true
    }

    /**
     * Cheap "is this already the target language?" screen based on script. It only rejects
     * confident mismatches, e.g. asking for Japanese output when the string already contains
     * kana. Anything unclear is passed to the engine, which does real detection.
     */
    fun looksLikeTargetScript(s: String, targetLang: String): Boolean {
        val script = scriptOf(s) ?: return false
        return script == expectedScript(targetLang)
    }

    private fun scriptOf(s: String): Character.UnicodeScript? {
        var found: Character.UnicodeScript? = null
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            val sc = runCatching { Character.UnicodeScript.of(cp) }.getOrNull() ?: continue
            if (sc == Character.UnicodeScript.COMMON || sc == Character.UnicodeScript.INHERITED) continue
            if (found == null) found = sc else if (found != sc) return null
        }
        return found
    }

    private fun expectedScript(lang: String): Character.UnicodeScript? = when (lang.lowercase()) {
        "ja" -> Character.UnicodeScript.HIRAGANA
        "ko" -> Character.UnicodeScript.HANGUL
        "zh", "zh-cn", "zh-tw" -> Character.UnicodeScript.HAN
        "ru", "uk", "bg", "sr" -> Character.UnicodeScript.CYRILLIC
        "ar", "fa", "ur" -> Character.UnicodeScript.ARABIC
        "he", "iw" -> Character.UnicodeScript.HEBREW
        "th" -> Character.UnicodeScript.THAI
        "hi", "mr", "ne" -> Character.UnicodeScript.DEVANAGARI
        "el" -> Character.UnicodeScript.GREEK
        else -> null
    }
}
