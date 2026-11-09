package tj.volna.music

import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Song search on YouTube. Returns a short list: [{id, title, channel, duration}].
 * Only titles and video ids come back here; the songs themselves play in YouTube's own player.
 */
object YouTube {
    private const val CLIENT_VERSION = "2.20251101.01.00"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    fun search(query: String): WebResourceResponse {
        if (query.isBlank()) return json("[]", 200)
        return try {
            val client = JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", CLIENT_VERSION)
                .put("hl", "ru")
                .put("gl", "RU")
            val body = JSONObject()
                .put("context", JSONObject().put("client", client))
                .put("query", query)
                .put("params", "EgIQAQ%3D%3D") // videos only

            val conn = URL("https://www.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("X-YouTube-Client-Name", "1")
            conn.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION)
            conn.setRequestProperty("Origin", "https://www.youtube.com")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode !in 200..299) return json("[]", 502)
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val out = JSONArray()
            walk(JSONObject(text), out, HashSet())
            json(out.toString(), 200)
        } catch (e: Exception) {
            json("[]", 502)
        }
    }

    private fun walk(node: Any?, out: JSONArray, seen: MutableSet<String>) {
        if (out.length() >= 40) return
        if (node is JSONObject) {
            val vr = node.optJSONObject("videoRenderer")
            if (vr != null) {
                val item = parse(vr)
                if (item != null && seen.add(item.getString("id"))) out.put(item)
                return
            }
            val keys = node.keys()
            while (keys.hasNext()) walk(node.opt(keys.next()), out, seen)
        } else if (node is JSONArray) {
            for (i in 0 until node.length()) walk(node.opt(i), out, seen)
        }
    }

    private fun text(o: JSONObject?): String {
        if (o == null) return ""
        val simple = o.optString("simpleText")
        if (simple.isNotEmpty()) return simple
        val runs = o.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text") ?: "")
        return sb.toString()
    }

    private fun parse(vr: JSONObject): JSONObject? {
        val id = vr.optString("videoId")
        if (id.isEmpty()) return null
        val length = text(vr.optJSONObject("lengthText"))
        if (length.isEmpty()) return null // live streams have no length
        var secs = 0
        for (part in length.split(":")) secs = secs * 60 + (part.trim().toIntOrNull() ?: 0)
        var channel = text(vr.optJSONObject("ownerText"))
        if (channel.isEmpty()) channel = text(vr.optJSONObject("longBylineText"))
        return JSONObject()
            .put("id", id)
            .put("title", text(vr.optJSONObject("title")))
            .put("channel", channel)
            .put("duration", secs)
    }

    private fun json(s: String, code: Int) = WebResourceResponse(
        "application/json", "utf-8", code, if (code == 200) "OK" else "Bad Gateway",
        mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
        ByteArrayInputStream(s.toByteArray(Charsets.UTF_8))
    )
}
