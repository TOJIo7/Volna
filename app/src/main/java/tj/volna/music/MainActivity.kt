package tj.volna.music

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : ComponentActivity() {

    companion object {
        const val HOST = "appassets.androidplatform.net"
        @Volatile
        var instance: MainActivity? = null
    }

    private lateinit var web: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#121212"))
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#121212"))
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // Keep the page clear of the status bar, navigation bar and keyboard (Android 15 draws edge to edge).
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host == HOST) {
                    val path = url.path ?: ""
                    if (path.startsWith("/api/")) return NetProxy.proxy(request)
                    if (path.startsWith("/offline/")) return Storage.serve(this@MainActivity, path, request.requestHeaders)
                }
                return assetLoader.shouldInterceptRequest(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: Exception) {
                }
                return true
            }
        }
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(NativeBridge(this), "VolnaNative")
        web.loadUrl("https://$HOST/assets/www/index.html")

        onBackPressedDispatcher.addCallback(this) {
            web.evaluateJavascript("(window.handleBack && window.handleBack()) ? 'yes' : 'no'") { result ->
                if (result == null || !result.contains("yes")) moveTaskToBack(true)
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        watchNetwork()
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = js("window.__setOnline && window.__setOnline(true)")
            override fun onLost(network: Network) {
                if (cm.activeNetwork == null) js("window.__setOnline && window.__setOnline(false)")
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Exception) {
        }
    }

    /** Runs JavaScript in the page from any thread. */
    fun js(code: String) {
        runOnUiThread {
            if (!isDestroyed) web.evaluateJavascript(code, null)
        }
    }

    override fun onDestroy() {
        instance = null
        netCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        stopService(Intent(this, PlaybackService::class.java))
        web.destroy()
        super.onDestroy()
    }
}
