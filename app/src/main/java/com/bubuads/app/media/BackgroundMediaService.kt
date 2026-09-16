package com.bubuads.app.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.bubuads.app.Config
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class BackgroundMediaService : Service() {

    companion object {
        private const val TAG = "BgMediaService"
        private const val CHANNEL_ID = "bubuads_playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PAUSE = "com.bubuads.app.ACTION_PAUSE"
        private const val ACTION_PLAY = "com.bubuads.app.ACTION_PLAY"
        private const val ACTION_NEXT = "com.bubuads.app.ACTION_NEXT"
        private const val ACTION_PREV = "com.bubuads.app.ACTION_PREV"
        private const val ACTION_STOP = "com.bubuads.app.ACTION_STOP"
    }

    private val binder = LocalBinder()
    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentTitle: String = ""
    private var currentArtist: String = ""
    var isPlaying: Boolean = false
        private set
    private var videoThumbnail: Bitmap? = null
    private var lastThumbnailUrl: String = ""
    private var currentPosition: Long = 0
    private var currentDuration: Long = 0
    /** Timestamp del ultimo update de playback con playing=true.
     *  Evita que onDestroy pare el servicio si el watchdog reporto
     *  playing=false moments antes de un buffer o cambio de track. */
    @Volatile var lastPlayingTimestamp: Long = 0L
        private set

    var onMediaAction: ((String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): BackgroundMediaService = this@BackgroundMediaService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Servicio creado")
        createNotificationChannel()
        setupMediaSession()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    /** La tarea se cierra (swipe) pero seguimos reproduciendo: el servicio no
     *  se detiene (android:stopWithTask="false") para no cortar la musica. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved: manteniendo reproduccion en segundo plano")
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> handleMediaAction("pause")
            ACTION_PLAY -> handleMediaAction("play")
            ACTION_NEXT -> handleMediaAction("next")
            ACTION_PREV -> handleMediaAction("prev")
            ACTION_STOP -> {
                handleMediaAction("pause")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Servicio destruido")
        mediaSession?.release()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reproduccion BubuADS",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Controles de reproduccion en segundo plano"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "BubuADS").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { handleMediaAction("play") }
                override fun onPause() { handleMediaAction("pause") }
                override fun onSkipToNext() { handleMediaAction("next") }
                override fun onSkipToPrevious() { handleMediaAction("prev") }
                override fun onSeekTo(pos: Long) {
                    currentPosition = pos
                    onMediaAction?.invoke("seek:$pos")
                    updateMediaSession()
                }
                override fun onStop() {
                    handleMediaAction("pause")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                handleMediaAction(if (isPlaying) "pause" else "play")
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_NEXT -> { handleMediaAction("next"); return true }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { handleMediaAction("prev"); return true }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
            isActive = true
        }
    }

    private fun handleMediaAction(action: String) {
        Log.d(TAG, "Accion media: $action")
        isPlaying = when (action) {
            "play" -> true
            "pause" -> false
            else -> isPlaying
        }
        if (isPlaying) acquireWakeLock() else releaseWakeLock()
        onMediaAction?.invoke(action)
        updateMediaSession()
        updateNotification()
    }

    private fun updateMediaSession() {
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                currentPosition,
                1f
            )
            .build()

        mediaSession?.setPlaybackState(state)

        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle.ifEmpty { "BubuADS" })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist.ifEmpty { "YouTube" })
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "BubuADS")

        if (videoThumbnail != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, videoThumbnail)
        }

        mediaSession?.setMetadata(metaBuilder.build())
    }

    private fun buildNotification(): Notification {
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val pauseIntent = buildMediaPendingIntent(ACTION_PAUSE, 1)
        val playIntent = buildMediaPendingIntent(ACTION_PLAY, 2)
        val nextIntent = buildMediaPendingIntent(ACTION_NEXT, 3)
        val prevIntent = buildMediaPendingIntent(ACTION_PREV, 4)
        val stopIntent = buildMediaPendingIntent(ACTION_STOP, 5)

        val displayTitle = currentTitle.ifEmpty { "BubuADS" }
        val displayArtist = currentArtist.ifEmpty { "YouTube" }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(displayTitle)
            .setContentText(displayArtist)
            .setSubText("BubuADS")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)

        if (videoThumbnail != null) {
            builder.setLargeIcon(videoThumbnail)
        }

        if (isPlaying) {
            builder.addAction(android.R.drawable.ic_media_pause, "Pausar", pauseIntent)
        } else {
            builder.addAction(android.R.drawable.ic_media_play, "Reproducir", playIntent)
        }

        builder.addAction(android.R.drawable.ic_media_previous, "Anterior", prevIntent)
        builder.addAction(android.R.drawable.ic_media_next, "Siguiente", nextIntent)
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cerrar", stopIntent)

        val style = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
            .setShowCancelButton(true)
            .setCancelButtonIntent(stopIntent)

        builder.setStyle(style)
        return builder.build()
    }

    private fun buildMediaPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, BackgroundMediaService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun updatePlaybackState(playing: Boolean, title: String?, artist: String?, position: Long = 0, duration: Long = 0) {
        isPlaying = playing
        if (playing) {
            lastPlayingTimestamp = System.currentTimeMillis()
            acquireWakeLock()
        } else {
            releaseWakeLock()
        }
        if (title != null) currentTitle = title
        if (artist != null) currentArtist = artist
        currentPosition = position
        currentDuration = duration
        updateMediaSession()
        updateNotification()
    }

    fun loadThumbnail(url: String) {
        if (url == lastThumbnailUrl && videoThumbnail != null) return
        lastThumbnailUrl = url
        thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.connect()
                val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                conn.disconnect()
                if (bmp != null) {
                    videoThumbnail = Bitmap.createScaledBitmap(bmp, 256, 256, true)
                    bmp.recycle()
                    Handler(Looper.getMainLooper()).post {
                        updateMediaSession()
                        updateNotification()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo cargar thumbnail: $url", e)
            }
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification())
    }

    /** WakeLock indefinido mientras suena: mantiene la CPU activa con la
     *  pantalla apagada para que el WebView siga reproduciendo audio. Se
     *  libera al pausar o al destruir el servicio. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BubuADS::PlaybackWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}
