package tj.volna.music

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Saves tracks to the phone so they play without internet. */
object Downloader {
    private val pool = Executors.newFixedThreadPool(3)
    private val active = ConcurrentHashMap<String, Boolean>()

    /** callback(state, percent, sizeBytes, hasArt); state is "progress", "done" or "error". */
    fun start(ctx: Context, id: String, url: String, artUrl: String, callback: (String, Int, Long, Boolean) -> Unit) {
        if (active.putIfAbsent(id, true) != null) return
        pool.execute {
            val target = Storage.musicFile(ctx, id)
            val part = File(Storage.musicDir(ctx), "$id.part")
            try {
                val conn = open(url)
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val total = conn.contentLengthLong
                var got = 0L
                var lastPct = -1
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (active[id] != true) throw InterruptedException("cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            if (total > 0) {
                                val pct = (got * 100 / total).toInt()
                                if (pct >= lastPct + 3) {
                                    lastPct = pct
                                    callback("progress", pct, got, false)
                                }
                            }
                        }
                    }
                }
                if (got < 10_000) throw IllegalStateException("file too small")
                if (!part.renameTo(target)) throw IllegalStateException("rename failed")
                var hasArt = false
                if (artUrl.isNotBlank()) {
                    try {
                        val a = open(artUrl)
                        if (a.responseCode in 200..299) {
                            a.inputStream.use { inp -> Storage.artFile(ctx, id).outputStream().use { inp.copyTo(it) } }
                            hasArt = true
                        }
                    } catch (_: Exception) {
                    }
                }
                callback("done", 100, target.length(), hasArt)
            } catch (e: Exception) {
                part.delete()
                if (active[id] == true) callback("error", 0, 0, false)
            } finally {
                active.remove(id)
            }
        }
    }

    fun cancel(id: String) {
        if (active.containsKey(id)) active[id] = false
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "VolnaMusic/1.0 (Android)")
        return c
    }
}
