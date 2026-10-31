package tj.volna.music

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Downloaded music lives in the app's private storage: files/music/<id>.mp3 and files/art/<id>.jpg */
object Storage {
    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun validId(id: String) = ID.matches(id)
    fun musicDir(ctx: Context) = File(ctx.filesDir, "music").apply { mkdirs() }
    fun artDir(ctx: Context) = File(ctx.filesDir, "art").apply { mkdirs() }
    fun musicFile(ctx: Context, id: String) = File(musicDir(ctx), "$id.mp3")
    fun artFile(ctx: Context, id: String) = File(artDir(ctx), "$id.jpg")

    fun list(ctx: Context): List<String> =
        musicDir(ctx).listFiles()?.filter { it.name.endsWith(".mp3") }?.map { it.name.removeSuffix(".mp3") } ?: emptyList()

    fun totalBytes(ctx: Context): Long = musicDir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    fun delete(ctx: Context, id: String) {
        musicFile(ctx, id).delete()
        File(musicDir(ctx), "$id.part").delete()
        artFile(ctx, id).delete()
    }

    /** Serves /offline/music/<id> and /offline/art/<id>, with Range support so seeking works. */
    fun serve(ctx: Context, path: String, headers: Map<String, String>): WebResourceResponse {
        val parts = path.split("/")
        if (parts.size != 4 || !validId(parts[3])) return notFound()
        val id = parts[3]
        val file = when (parts[2]) {
            "music" -> musicFile(ctx, id)
            "art" -> artFile(ctx, id)
            else -> return notFound()
        }
        if (!file.exists()) return notFound()
        val mime = if (parts[2] == "art") "image/jpeg" else "audio/mpeg"
        val len = file.length()
        val out = HashMap<String, String>()
        out["Accept-Ranges"] = "bytes"
        out["Access-Control-Allow-Origin"] = "*"

        val range = headers.entries.firstOrNull { it.key.equals("Range", true) }?.value
        val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        if (m != null && len > 0) {
            var start = m.groupValues[1].toLongOrNull()
            var end = m.groupValues[2].toLongOrNull()
            if (start == null) {
                val suffix = end ?: 0L
                start = (len - suffix).coerceAtLeast(0L)
                end = len - 1
            }
            if (end == null || end >= len) end = len - 1
            if (start >= len || start > end) {
                out["Content-Range"] = "bytes */$len"
                return WebResourceResponse(mime, null, 416, "Range Not Satisfiable", out, ByteArrayInputStream(ByteArray(0)))
            }
            val count = end - start + 1
            val input = FileInputStream(file)
            skipFully(input, start)
            out["Content-Range"] = "bytes $start-$end/$len"
            out["Content-Length"] = count.toString()
            return WebResourceResponse(mime, null, 206, "Partial Content", out, Bounded(input, count))
        }
        out["Content-Length"] = len.toString()
        return WebResourceResponse(mime, null, 200, "OK", out, FileInputStream(file))
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) {
                if (input.read() == -1) return
                left -= 1
            } else left -= s
        }
    }

    private fun notFound() =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(ByteArray(0)))

    private class Bounded(private val inner: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = inner.read()
            if (b >= 0) left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }

        override fun close() = inner.close()
    }
}
