package tj.volna.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Copies audio files the person picked into the app ("Мои файлы") and reads title, artist, length and cover. */
object Importer {
    private val pool = Executors.newSingleThreadExecutor()

    fun import(ctx: Context, uris: List<Uri>) {
        MainActivity.instance?.js("window.__localImporting && window.__localImporting(${uris.size})")
        pool.execute {
            val out = JSONArray()
            uris.forEachIndexed { i, uri ->
                try {
                    val name = displayName(ctx, uri) ?: "track.mp3"
                    var ext = name.substringAfterLast('.', "").lowercase()
                    if (ext.isEmpty() || ext.length > 5 || !ext.all { it.isLetterOrDigit() }) {
                        ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(ctx.contentResolver.getType(uri)) ?: "mp3"
                    }
                    val id = "loc" + System.currentTimeMillis().toString(36) + i
                    val file = File(Storage.localDir(ctx), "$id.$ext")
                    val copied = ctx.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    } ?: 0L
                    if (copied <= 0L) {
                        file.delete()
                        return@forEachIndexed
                    }

                    var title: String? = null
                    var artist: String? = null
                    var duration = 0L
                    var hasArt = false
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(file.absolutePath)
                        title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val pic = mmr.embeddedPicture
                        if (pic != null) {
                            Storage.artFile(ctx, id).writeBytes(pic)
                            hasArt = true
                        }
                    } catch (_: Exception) {
                    } finally {
                        try {
                            mmr.release()
                        } catch (_: Exception) {
                        }
                    }

                    // "Artist - Title.mp3" is how most downloaded files are named.
                    val base = name.substringBeforeLast('.')
                    if (title.isNullOrBlank()) {
                        if (base.contains(" - ")) {
                            if (artist.isNullOrBlank()) artist = base.substringBefore(" - ").trim()
                            title = base.substringAfter(" - ").trim()
                        } else {
                            title = base
                        }
                    }

                    val item = JSONObject()
                    item.put("id", id)
                    item.put("title", title ?: base)
                    item.put("artist", artist ?: "")
                    item.put("duration", duration)
                    item.put("hasArt", hasArt)
                    item.put("size", file.length())
                    out.put(item)
                } catch (_: Exception) {
                }
            }
            MainActivity.instance?.js("window.__localAdded && window.__localAdded($out)")
        }
    }

    private fun displayName(ctx: Context, uri: Uri): String? {
        return try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
