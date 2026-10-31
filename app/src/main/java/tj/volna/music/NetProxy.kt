package tj.volna.music

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Forwards the page's requests to the music catalogs:
 *   /api/...      -> Audius (full tracks)
 *   /dz/...       -> Deezer (search and 30-second previews)
 *   /ext?u=<url>  -> Deezer preview audio files
 * Because the page sees everything on its own address, the equalizer can process the sound
 * and seeking works (Range requests are passed through).
 */
object NetProxy {

    fun audius(u: Uri): String =
        "https://api.audius.co/v1" + (u.encodedPath ?: "").removePrefix("/api") + (u.encodedQuery?.let { "?$it" } ?: "")

    fun deezer(u: Uri): String =
        "https://api.deezer.com" + (u.encodedPath ?: "").removePrefix("/dz") + (u.encodedQuery?.let { "?$it" } ?: "")

    /** Only Deezer's audio servers are allowed through /ext. */
    fun external(u: Uri): String? {
        val target = u.getQueryParameter("u") ?: return null
        if (!target.startsWith("https://")) return null
        val host = Uri.parse(target).host ?: return null
        return if (host.endsWith(".dzcdn.net") || host.endsWith(".deezer.com")) target else null
    }

    fun proxy(request: WebResourceRequest, target: String): WebResourceResponse {
        return try {
            val conn = URL(target).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "VolnaMusic/1.1 (Android)")
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

    fun forbidden(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", mapOf("Access-Control-Allow-Origin" to "*"), empty())

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
