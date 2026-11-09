package tj.volna.music

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
    fun pickFiles() = activity.pickAudio()

    @JavascriptInterface
    fun listLocal(): String = JSONArray(Storage.listLocal(activity)).toString()

    @JavascriptInterface
    fun deleteLocal(id: String) {
        if (Storage.validId(id)) Storage.deleteLocal(activity, id)
    }

    /** True when the phone lets the app keep running in the background. */
    @JavascriptInterface
    fun backgroundAllowed(): Boolean {
        val pm = activity.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(activity.packageName)
    }

    /** Opens the phone's prompt to let the app run in the background. */
    @SuppressLint("BatteryLife")
    @JavascriptInterface
    fun askBackground() {
        activity.runOnUiThread {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.packageName))
                )
            } catch (_: Exception) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                }
            }
        }
    }

    @JavascriptInterface
    fun storageBytes(): Long = Storage.totalBytes(activity)

    @JavascriptInterface
    fun updatePlayback(title: String, artist: String, playing: Boolean) {
        MainActivity.playingNow = playing
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
