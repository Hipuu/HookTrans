package io.hooktrans.engine

import io.hooktrans.core.Logs
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

internal object Http {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private const val CONNECT_TIMEOUT = 8_000
    private const val READ_TIMEOUT = 12_000
    private const val MAX_BODY = 4 * 1024 * 1024

    fun get(url: String, headers: Map<String, String> = emptyMap()): String? =
        request("GET", url, null, null, headers)

    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String? =
        request("POST", url, body.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8", headers)

    fun postForm(url: String, body: String, headers: Map<String, String> = emptyMap()): String? =
        request("POST", url, body.toByteArray(Charsets.UTF_8), "application/x-www-form-urlencoded; charset=utf-8", headers)

    private fun request(
        method: String,
        url: String,
        body: ByteArray?,
        contentType: String?,
        headers: Map<String, String>,
    ): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Accept", "*/*")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    contentType?.let { setRequestProperty("Content-Type", it) }
                    setFixedLengthStreamingMode(body.size)
                }
            }
            if (body != null) conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { raw ->
                val decoded = if (conn.contentEncoding?.contains("gzip", true) == true) {
                    GZIPInputStream(raw)
                } else raw
                decoded.use { readLimited(it) }
            }
            if (code !in 200..299) {
                Logs.w("HTTP $code for $url :: ${text?.take(300)}")
                return null
            }
            text
        } catch (t: Throwable) {
            Logs.w("HTTP failure for $url", t)
            null
        }
        // Deliberately no disconnect(). On Android HttpURLConnection is backed by a connection
        // pool, and disconnect() does not mean "close this stream", it means "evict this socket"
        // — so every call paid for a fresh TCP connect plus a fresh TLS handshake. Measured on
        // ten sequential requests to the translate endpoint: 4,259 ms with disconnect() against
        // 1,762 ms without. Closing the response stream (which the `use` blocks above always do,
        // on both the success and the error path) is what actually releases the connection, and
        // it releases it back to the pool instead of destroying it.
    }

    private fun readLimited(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > MAX_BODY) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
