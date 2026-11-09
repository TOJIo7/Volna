package tj.volna.music

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * A WebView that keeps behaving as if it were on screen while music is playing,
 * so the sound does not stop when the screen turns off or the app is minimized.
 */
@SuppressLint("ViewConstructor")
class BgWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.VISIBLE && MainActivity.playingNow) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }
}
