package tj.volna.music

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps music playing with the screen off and shows controls in the notification shade,
 * on the lock screen and for headphone buttons. The sound itself is played by the web page.
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_UPDATE = "tj.volna.music.UPDATE"
        const val ACTION_TOGGLE = "tj.volna.music.TOGGLE"
        const val ACTION_NEXT = "tj.volna.music.NEXT"
        const val ACTION_PREV = "tj.volna.music.PREV"
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 7
        @Volatile
        var instance: PlaybackService? = null
    }

    private lateinit var session: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var title = "Волна"
    private var artist = ""
    private var playing = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        session = MediaSessionCompat(this, "Volna").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = send("toggle")
                override fun onPause() = send("toggle")
                override fun onSkipToNext() = send("next")
                override fun onSkipToPrevious() = send("prev")
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE -> update(
                intent.getStringExtra("title") ?: title,
                intent.getStringExtra("artist") ?: artist,
                intent.getBooleanExtra("playing", playing)
            )
            ACTION_TOGGLE -> { send("toggle"); showForeground() }
            ACTION_NEXT -> { send("next"); showForeground() }
            ACTION_PREV -> { send("prev"); showForeground() }
            else -> showForeground()
        }
        return START_NOT_STICKY
    }

    private fun send(action: String) {
        MainActivity.instance?.js("window.__mediaAction && window.__mediaAction('$action')")
    }

    fun update(t: String, a: String, isPlaying: Boolean) {
        title = t
        artist = a
        playing = isPlaying
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, t)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, a)
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f
                )
                .build()
        )
        if (isPlaying) holdLocks() else releaseLocks()
        showForeground()
    }

    private fun showForeground() {
        val n = buildNotification()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
        } catch (_: Exception) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        }
    }

    private fun pending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(R.drawable.ic_prev, "Назад", pending(ACTION_PREV, 1))
            .addAction(if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) "Пауза" else "Играть", pending(ACTION_TOGGLE, 2))
            .addAction(R.drawable.ic_next, "Далее", pending(ACTION_NEXT, 3))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    @SuppressLint("WakelockTimeout")
    private fun holdLocks() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Volna:playback")
        }
        if (wakeLock?.isHeld == false) wakeLock?.acquire()
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "Volna:stream")
        }
        if (wifiLock?.isHeld == false) wifiLock?.acquire()
    }

    private fun releaseLocks() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        instance = null
        releaseLocks()
        session.isActive = false
        session.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
