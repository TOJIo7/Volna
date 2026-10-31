package tj.volna.music

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** Methods the web page calls as window.VolnaNative.* */
class NativeBridge(private val activity: MainActivity) {

    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun download(id: String, url: String, artUrl: String) {
        if (!Storage.validId(id)) return
        Downloader.start(activity.applicationContext, id, url, artUrl) { state, pct, size, hasArt ->
            val q = JSONObject.quote(id)
            MainActivity.instance?.js("window.__nativeDownload && window.__nativeDownload($q, $pct, '$state', $size, $hasArt)")
        }
    }

    @JavascriptInterface
    fun deleteDownload(id: String) {
        if (!Storage.validId(id)) return
        Downloader.cancel(id)
        Storage.delete(activity, id)
    }

    @JavascriptInterface
    fun listDownloads(): String = JSONArray(Storage.list(activity)).toString()

    @JavascriptInterface
    fun storageBytes(): Long = Storage.totalBytes(activity)

    @JavascriptInterface
    fun updatePlayback(title: String, artist: String, playing: Boolean) {
        main.post {
            val svc = PlaybackService.instance
            if (svc != null) {
                svc.update(title, artist, playing)
            } else if (playing) {
                val i = Intent(activity, PlaybackService::class.java)
                    .setAction(PlaybackService.ACTION_UPDATE)
                    .putExtra("title", title)
                    .putExtra("artist", artist)
                    .putExtra("playing", true)
                try {
                    ContextCompat.startForegroundService(activity, i)
                } catch (_: Exception) {
                }
            }
        }
    }
}
