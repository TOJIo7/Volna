package tj.volna.music

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Forwards /api/... requests from the page to the Audius catalog.
 * Because the page sees everything on its own address, the equalizer can process the sound
 * and seeking works (Range requests are passed through).
 */
object NetProxy {
    private const val BASE = "https://api.audius.co/v1"

    fun proxy(request: WebResourceRequest): WebResourceResponse {
        val u = request.url
        val path = (u.encodedPath ?: "").removePrefix("/api")
        val query = u.encodedQuery?.let { "?$it" } ?: ""
        return try {
            val conn = URL(BASE + path + query).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "VolnaMusic/1.0 (Android)")
            for ((k, v) in request.requestHeaders) {
                if (k.equals("Range", true) || k.equals("Accept", true)) conn.setRequestProperty(k, v)
            }
            val code = conn.responseCode
            val body = if (code >= 400) (conn.errorStream ?: empty()) else conn.inputStream
            val type = conn.contentType ?: "application/octet-stream"
            val mime = type.substringBefore(";").trim()
            val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)?.trim()
            val headers = HashMap<String, String>()
            for (h in listOf("Content-Length", "Content-Range", "Accept-Ranges", "Cache-Control")) {
                conn.getHeaderField(h)?.let { headers[h] = it }
            }
            headers["Access-Control-Allow-Origin"] = "*"
            val status = if (code in 300..399 || code < 100) 502 else code
            WebResourceResponse(mime, charset, status, reason(status), headers, body)
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", mapOf("Access-Control-Allow-Origin" to "*"), empty())
        }
    }

    fun reason(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        404 -> "Not Found"
        416 -> "Range Not Satisfiable"
        429 -> "Too Many Requests"
        in 200..299 -> "OK"
        in 400..499 -> "Client Error"
        else -> "Server Error"
    }

    private fun empty() = ByteArrayInputStream(ByteArray(0))
}
