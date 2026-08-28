package io.hooktrans.engine

import io.hooktrans.core.Engines
import io.hooktrans.core.Logs
import org.json.JSONArray
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The keyless endpoint the Google Translate web widget uses. No account, no quota
 * registration, no key.
 *
 * **Unofficial API**: this endpoint is not documented or supported by Google. It may change,
 * rate-limit, or disappear without notice. The retry and fallback logic in TranslationRepo
 * mitigates transient failures, but a breaking change here requires an app update.
 *
 * Requests go to `translate_a/t`, which takes a repeated `q` parameter and answers with one
 * entry per input, in order. That choice is the difference between a usable module and an
 * unusable one. The obvious endpoint — `translate_a/single` — accepts exactly one string, so
 * using it means one HTTPS round trip per label on screen: forty labels became forty
 * handshakes and, at any sane concurrency, a dozen sequential waves. Measured on forty real
 * labels, that is ~9.1 s against ~0.4 s for the same forty in one batched request.
 *
 * The batch is capped well below what the endpoint tolerates (verified good at 300 strings in
 * one call) so that a single failure costs one chunk rather than a whole screen.
 */
class GoogleFreeEngine : TranslationEngine {

    override val id = Engines.GOOGLE_FREE
    override val needsNetwork = true

    private val pool = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "ht-google").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    override fun translate(texts: List<String>, src: String, dst: String): List<String?> {
        if (texts.isEmpty()) return emptyList()

        val chunks = chunk(texts)
        val tasks = chunks.map { c -> Callable { batch(c, src, dst) } }
        val answered = try {
            pool.invokeAll(tasks, 45, TimeUnit.SECONDS).map { f ->
                runCatching { if (f.isCancelled) null else f.get() }.getOrNull()
            }
        } catch (t: Throwable) {
            Logs.w("google batch failed", t)
            return texts.map { null }
        }

        // Flatten back onto the original order. A chunk that failed contributes nulls, which
        // the caller reads as "keep the original" for exactly those strings.
        val out = ArrayList<String?>(texts.size)
        chunks.forEachIndexed { i, c ->
            val r = answered.getOrNull(i)
            if (r == null || r.size != c.size) repeat(c.size) { out += null } else out += r
        }
        return out
    }

    /**
     * Splits a request by count and by encoded size.
     *
     * The size bound is what stops a batch of long strings from building a URL no server will
     * accept: URL encoding costs up to three characters per byte, and a CJK code point is
     * three UTF-8 bytes, so a single 400-character Chinese description can reach ~3.6 kB on
     * its own.
     */
    private fun chunk(texts: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var cost = 0
        texts.forEach { t ->
            val c = t.toByteArray(Charsets.UTF_8).size * 3 + 3
            if (cur.isNotEmpty() && (cur.size >= MAX_PER_REQUEST || cost + c > MAX_REQUEST_CHARS)) {
                out += cur
                cur = ArrayList()
                cost = 0
            }
            cur += t
            cost += c
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun batch(texts: List<String>, src: String, dst: String): List<String?>? {
        val sl = if (src.isBlank()) "auto" else src
        val base = "https://translate.googleapis.com/translate_a/t" +
            "?client=gtx&sl=${Http.enc(sl)}&tl=${Http.enc(dst)}"
        val q = texts.joinToString("&") { "q=${Http.enc(it)}" }

        // A GET is what the web widget itself sends, so it is the better-tested path; a batch
        // of long strings outgrows any safe URL length, and those go as a form body instead.
        val body = if (q.length <= GET_LIMIT) Http.get("$base&$q") else Http.postForm(base, q)
        return parse(body ?: return null, texts.size)
    }

    /**
     * Two response shapes, chosen by `sl`. An explicit source language answers
     * `["translated", ...]`; `sl=auto` answers `[["translated","detectedLang"], ...]`. Both
     * carry exactly one entry per input, in request order — that invariant is what makes
     * mapping the answer back onto the batch safe, so a length mismatch fails the whole
     * chunk rather than risking a translation landing on the wrong string.
     */
    private fun parse(body: String, expected: Int): List<String?>? = try {
        val root = JSONArray(body)
        if (root.length() != expected) {
            Logs.w("google returned ${root.length()} entries for $expected inputs")
            null
        } else {
            List(expected) { i ->
                when (val e = root.opt(i)) {
                    is String -> e.takeIf { it.isNotBlank() }
                    is JSONArray -> e.optString(0).takeIf { it.isNotBlank() }
                    else -> null
                }
            }
        }
    } catch (t: Throwable) {
        Logs.w("google parse failed", t)
        null
    }

    override fun close() {
        runCatching { pool.shutdownNow() }
    }

    private companion object {
        /** Verified good far higher; kept low so one failure costs one chunk, not a screen. */
        const val MAX_PER_REQUEST = 100
        const val MAX_REQUEST_CHARS = 24_000
        const val GET_LIMIT = 1_800
    }
}
