package io.hooktrans.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The single source of truth shared between the UI, the translation service and every
 * hooked process. Serialized as JSON so it can travel through XSharedPreferences and
 * across the binder without a versioned Parcelable.
 */
data class HookConfig(
    val enabled: Boolean = true,
    val sourceLang: String = "auto",
    val targetLang: String = "en",
    val engine: String = Engines.GOOGLE_FREE,
    val endpoint: String = "",
    val apiKey: String = "",

    /** When true every app in the module's LSPosed scope is translated. */
    val scopeAllApps: Boolean = false,
    val packages: Set<String> = emptySet(),
    val perAppLang: Map<String, String> = emptyMap(),

    val hookTextViews: Boolean = true,
    val hookHints: Boolean = true,
    val hookWebViews: Boolean = true,
    val hookCompose: Boolean = true,

    /**
     * Substitutes text at `Canvas.drawText`, which is the only way to reach labels an app
     * paints itself instead of putting in a View — tab bars, badges, chart axes. Nothing is
     * ever handed back to the app, so it cannot corrupt app state, but it does run inside
     * display-list recording on every frame. Opt-in because of that cost, not because of risk.
     */
    val hookCanvas: Boolean = false,

    /**
     * Translates strings at `Resources.getString`, which catches text the render hooks never
     * see. Also the riskiest hook in the module, because strings.xml legitimately holds URLs
     * and keys, so it is opt-in per install.
     */
    val hookResources: Boolean = false,

    /**
     * Never substitute text synchronously inside setText(). The original is what the app
     * sees if it reads the view back on the same call stack; the translation lands one
     * frame later. Costs a frame of flicker, removes an entire class of breakage.
     */
    val maxCompatibility: Boolean = false,

    /** Text carrying ClickableSpan/URLSpan is left alone: those spans are behaviour. */
    val skipLinkedText: Boolean = true,

    /**
     * Selectable text cannot use the display-only path, so translating it means replacing
     * the stored text and `getText()` starts returning the translation. Off by default.
     */
    val translateSelectable: Boolean = false,

    val minChars: Int = 2,
    val maxChars: Int = 400,

    /** Substrings matched against the view's resource entry name. Escape hatch. */
    val excludeViewIds: Set<String> = emptySet(),
    /** Exact strings that must never be translated. */
    val neverTranslate: Set<String> = emptySet(),

    /**
     * Translates text found *inside images* — screenshots, photographed signs, memes, the
     * baked-in captions that make up most of the text in a chat app's shared media.
     *
     * This is the only hook that cannot use the display-only trick the rest of the module is
     * built on: there is no `TransformationMethod` for a Bitmap. Instead the app's own bitmap
     * is left completely untouched and the translation is painted over the top at draw time,
     * so `getDrawable()` still returns the original image and nothing the app reads, caches,
     * re-encodes or uploads is affected.
     *
     * Off by default, and the most expensive option in the module: recognition runs on every
     * bitmap that reaches the screen, including avatars and icons.
     */
    val hookImages: Boolean = false,

    /**
     * Translates strings as the app *parses* them instead of waiting for them to be drawn.
     *
     * Hooks the JSON parsers, so a comment thread or a feed page is translated in full the
     * moment it arrives — including the replies still below the fold, which a render hook never
     * sees because the recycler only binds what fits on screen. Nothing is substituted at the
     * parse site: the app receives its own string untouched and the translation is only put in
     * cache, so scrolling hits a warm cache instead of a round trip.
     *
     * Costs memory and translation volume on text the user may never reach, which is why it is
     * a switch rather than always-on.
     */
    val prefetchParsed: Boolean = false,

    /**
     * Draw the original text underneath the translation instead of covering it completely.
     * Useful for checking what a recognizer actually read.
     */
    val overlayShowOriginal: Boolean = false,
    val logVerbose: Boolean = false,
) {

    fun isPackageEnabled(pkg: String): Boolean =
        enabled && (scopeAllApps || packages.contains(pkg))

    fun langFor(pkg: String): String = perAppLang[pkg] ?: targetLang

    fun toJson(): String {
        val o = JSONObject()
        o.put("enabled", enabled)
        o.put("sourceLang", sourceLang)
        o.put("targetLang", targetLang)
        o.put("engine", engine)
        o.put("endpoint", endpoint)
        o.put("apiKey", apiKey)
        o.put("scopeAllApps", scopeAllApps)
        o.put("packages", JSONArray(packages.toList()))
        o.put("perAppLang", JSONObject().also { m -> perAppLang.forEach { (k, v) -> m.put(k, v) } })
        o.put("hookTextViews", hookTextViews)
        o.put("hookHints", hookHints)
        o.put("hookWebViews", hookWebViews)
        o.put("hookCompose", hookCompose)
        o.put("hookCanvas", hookCanvas)
        o.put("hookResources", hookResources)
        o.put("maxCompatibility", maxCompatibility)
        o.put("skipLinkedText", skipLinkedText)
        o.put("translateSelectable", translateSelectable)
        o.put("minChars", minChars)
        o.put("maxChars", maxChars)
        o.put("excludeViewIds", JSONArray(excludeViewIds.toList()))
        o.put("neverTranslate", JSONArray(neverTranslate.toList()))
        o.put("hookImages", hookImages)
        o.put("prefetchParsed", prefetchParsed)
        o.put("overlayShowOriginal", overlayShowOriginal)
        o.put("logVerbose", logVerbose)
        return o.toString()
    }

    companion object {
        fun fromJson(json: String?): HookConfig {
            if (json.isNullOrBlank()) return HookConfig()
            return try {
                val o = JSONObject(json)
                val d = HookConfig()
                HookConfig(
                    enabled = o.optBoolean("enabled", d.enabled),
                    sourceLang = o.optString("sourceLang", d.sourceLang),
                    targetLang = o.optString("targetLang", d.targetLang),
                    engine = o.optString("engine", d.engine),
                    endpoint = o.optString("endpoint", d.endpoint),
                    apiKey = o.optString("apiKey", d.apiKey),
                    scopeAllApps = o.optBoolean("scopeAllApps", d.scopeAllApps),
                    packages = o.optStringSet("packages"),
                    perAppLang = o.optStringMap("perAppLang"),
                    hookTextViews = o.optBoolean("hookTextViews", d.hookTextViews),
                    hookHints = o.optBoolean("hookHints", d.hookHints),
                    hookWebViews = o.optBoolean("hookWebViews", d.hookWebViews),
                    hookCompose = o.optBoolean("hookCompose", d.hookCompose),
                    hookCanvas = o.optBoolean("hookCanvas", d.hookCanvas),
                    hookResources = o.optBoolean("hookResources", d.hookResources),
                    maxCompatibility = o.optBoolean("maxCompatibility", d.maxCompatibility),
                    skipLinkedText = o.optBoolean("skipLinkedText", d.skipLinkedText),
                    translateSelectable = o.optBoolean("translateSelectable", d.translateSelectable),
                    minChars = o.optInt("minChars", d.minChars),
                    maxChars = o.optInt("maxChars", d.maxChars),
                    excludeViewIds = o.optStringSet("excludeViewIds"),
                    neverTranslate = o.optStringSet("neverTranslate"),
                    hookImages = o.optBoolean("hookImages", d.hookImages),
                    prefetchParsed = o.optBoolean("prefetchParsed", d.prefetchParsed),
                    overlayShowOriginal = o.optBoolean("overlayShowOriginal", d.overlayShowOriginal),
                    logVerbose = o.optBoolean("logVerbose", d.logVerbose),
                )
            } catch (t: Throwable) {
                Logs.w("config parse failed, using defaults", t)
                HookConfig()
            }
        }

        private fun JSONObject.optStringSet(key: String): Set<String> {
            val arr = optJSONArray(key) ?: return emptySet()
            val out = LinkedHashSet<String>(arr.length())
            for (i in 0 until arr.length()) arr.optString(i)?.takeIf { it.isNotEmpty() }?.let(out::add)
            return out
        }

        private fun JSONObject.optStringMap(key: String): Map<String, String> {
            val obj = optJSONObject(key) ?: return emptyMap()
            val out = HashMap<String, String>()
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k] = obj.optString(k)
            }
            return out
        }
    }
}

object Engines {
    const val GOOGLE_FREE = "google_free"
    const val MLKIT = "mlkit"
    const val LIBRE = "libretranslate"
    const val DEEPL = "deepl"
    const val MYMEMORY = "mymemory"

    val ALL = listOf(GOOGLE_FREE, MLKIT, LIBRE, DEEPL, MYMEMORY)

    fun label(id: String): String = when (id) {
        GOOGLE_FREE -> "Google (free endpoint, no key)"
        MLKIT -> "ML Kit on-device (offline, no key)"
        LIBRE -> "LibreTranslate (self-hosted / public)"
        DEEPL -> "DeepL API"
        MYMEMORY -> "MyMemory (free, no key)"
        else -> id
    }

    fun needsEndpoint(id: String) = id == LIBRE
    fun needsKey(id: String) = id == DEEPL
}
