package com.swift.browser.audioengine.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.swift.browser.audioengine.AudioPlayerEngine
import com.swift.browser.audioengine.WebMediaPlaybackController
import com.swift.browser.notificationengine.media.MediaNotificationEngine
import com.swift.browser.notificationengine.media.MediaNotificationReceiver
import com.swift.browser.notificationengine.media.MediaNotificationBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AudioPlaybackService : Service() {

    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private lateinit var notificationEngine: MediaNotificationEngine
    private var isWebMediaMode = false

    @Synchronized
    private fun updateWakeLock(isPlaying: Boolean) {
        try {
            if (isPlaying) {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                    wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SwiftBrowser:AudioPlaybackWakeLock")
                }
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(10 * 60 * 1000L)
                }
            } else {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationEngine = MediaNotificationEngine(this)

        MediaNotificationReceiver.onMediaAction = { action ->
            if (WebMediaPlaybackController.isAnyWebMediaPlaying(applicationContext)) {
                when (action) {
                    "ACTION_PLAY_PAUSE" -> {
                        if (WebMediaPlaybackController.isMediaPlaying) {
                            WebMediaPlaybackController.pauseWebMedia()
                        } else {
                            WebMediaPlaybackController.playWebMedia()
                        }
                    }
                    "ACTION_NEXT" -> {
                        val currentPos = WebMediaPlaybackController.currentPositionMs
                        WebMediaPlaybackController.seekWebMedia(currentPos + 10000L)
                    }
                    "ACTION_PREVIOUS" -> {
                        val currentPos = WebMediaPlaybackController.currentPositionMs
                        WebMediaPlaybackController.seekWebMedia((currentPos - 10000L).coerceAtLeast(0L))
                    }
                    "ACTION_CLOSE" -> {
                        WebMediaPlaybackController.pauseWebMedia()
                        MediaNotificationBridge.clearNotification(this)
                    }
                }
            }
        }

        mediaSession = MediaSessionCompat(this, "AudioEnginePlaybackService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (isWebMediaMode || WebMediaPlaybackController.isAnyWebMediaPlaying(applicationContext)) {
                        WebMediaPlaybackController.playWebMedia()
                        updateWebPlaybackState(true)
                    } else {
                        AudioPlayerEngine.getInstance(applicationContext).resume()
                    }
                }

                override fun onPause() {
                    if (isWebMediaMode || WebMediaPlaybackController.isAnyWebMediaPlaying(applicationContext)) {
                        WebMediaPlaybackController.pauseWebMedia()
                        updateWebPlaybackState(false)
                    } else {
                        AudioPlayerEngine.getInstance(applicationContext).pause()
                    }
                }

                override fun onSkipToNext() {
                    if (isWebMediaMode || WebMediaPlaybackController.isAnyWebMediaPlaying(applicationContext)) {
                        val currentPos = WebMediaPlaybackController.currentPositionMs
                        WebMediaPlaybackController.seekWebMedia(currentPos + 10000L)
                    } else {
                        AudioPlayerEngine.getInstance(applicationContext).next()
                    }
                }

                override fun onSkipToPrevious() {
                    if (isWebMediaMode || WebMediaPlaybackController.isAnyWebMediaPlaying(applicationContext)) {
                        val currentPos = WebMediaPlaybackController.currentPositionMs
                        WebMediaPlaybackController.seekWebMedia((currentPos - 10000L).coerceAtLeast(0L))
                    } else {
                        AudioPlayerEngine.getInstance(applicationContext).previous()
                    }
                }

                override fun onSeekTo(pos: Long) {
                    if (isWebMediaMode || WebMediaPlaybackController.isAnyWebMediaPlaying(applicationContext)) {
                        WebMediaPlaybackController.seekWebMedia(pos)
                    } else {
                        AudioPlayerEngine.getInstance(applicationContext).seekTo(pos.toInt())
                    }
                }
            })
            isActive = true
        }

        val engine = AudioPlayerEngine.getInstance(applicationContext)
        serviceScope.launch {
            engine.isPlaying.collect { isPlaying ->
                if (!isWebMediaMode) {
                    updatePlaybackState(isPlaying)
                    val track = engine.currentTrack.value
                    val title = track?.title ?: engine.onlineTitle.value.ifEmpty { "Audio Playback" }
                    notificationEngine.showPlaybackNotification(title, isPlaying)
                }
            }
        }

        serviceScope.launch {
            engine.isOnlinePlaying.collect { isOnlinePlaying ->
                if (!isWebMediaMode && engine.currentTrack.value == null) {
                    updatePlaybackState(isOnlinePlaying)
                    val title = engine.onlineTitle.value.ifEmpty { "Online Music" }
                    notificationEngine.showPlaybackNotification(title, isOnlinePlaying)
                }
            }
        }

        serviceScope.launch {
            engine.onlineTitle.collect { onlineTitle ->
                if (!isWebMediaMode && engine.currentTrack.value == null) {
                    val metadataBuilder = MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, onlineTitle)
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Online Music")
                    mediaSession?.setMetadata(metadataBuilder.build())
                    notificationEngine.showPlaybackNotification(onlineTitle, engine.isOnlinePlaying.value)
                }
            }
        }

        serviceScope.launch {
            engine.currentTrack.collect { track ->
                if (!isWebMediaMode) {
                    track?.let {
                        val metadataBuilder = MediaMetadataCompat.Builder()
                            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, it.title)
                            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it.artist ?: "Unknown Artist")
                            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it.durationMs)
                        mediaSession?.setMetadata(metadataBuilder.build())
                        notificationEngine.showPlaybackNotification(it.title, engine.isPlaying.value)
                    }
                }
            }
        }
    }

    private fun updateWebPlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, WebMediaPlaybackController.currentPositionMs, 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    private fun updatePlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val engine = AudioPlayerEngine.getInstance(applicationContext)
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, engine.currentPositionMs.value, 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isWebMedia = intent?.getStringExtra("MEDIA_SOURCE") == "WEB_MEDIA" || WebMediaPlaybackController.isAnyWebMediaPlaying(applicationContext)
        if (isWebMedia) {
            isWebMediaMode = true
            val title = intent?.getStringExtra("TITLE") ?: WebMediaPlaybackController.currentTitle
            val playing = intent?.getBooleanExtra("PLAYING", WebMediaPlaybackController.isMediaPlaying) ?: WebMediaPlaybackController.isMediaPlaying
            val url = intent?.getStringExtra("URL") ?: ""
            val tabId = intent?.getStringExtra("TAB_ID") ?: ""

            WebMediaPlaybackController.updateMediaState(
                tabId = tabId,
                webView = null,
                title = title,
                playing = playing,
                url = url
            )

            when (intent?.action) {
                "ACTION_PLAY" -> WebMediaPlaybackController.playWebMedia()
                "ACTION_PAUSE" -> WebMediaPlaybackController.pauseWebMedia()
                "ACTION_STOP" -> WebMediaPlaybackController.pauseWebMedia()
            }

            val metadataBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Swift Browser Web Media")
            mediaSession?.setMetadata(metadataBuilder.build())
            updateWebPlaybackState(playing)
            showForegroundNotification(title, playing)
            return START_STICKY
        }

        isWebMediaMode = false
        val engine = AudioPlayerEngine.getInstance(applicationContext)
        when (intent?.action) {
            "ACTION_PLAY" -> engine.resume()
            "ACTION_PAUSE" -> engine.pause()
            "ACTION_NEXT" -> engine.next()
            "ACTION_PREVIOUS" -> engine.previous()
            "ACTION_STOP" -> engine.stop()
        }

        val track = engine.currentTrack.value
        val title = track?.title ?: intent?.getStringExtra("TITLE") ?: engine.onlineTitle.value.ifEmpty { "Audio Playback" }
        val playing = if (track != null) engine.isPlaying.value else engine.isOnlinePlaying.value
        showForegroundNotification(title, playing)
        return START_STICKY
    }

    private fun showForegroundNotification(title: String, isPlaying: Boolean) {
        updateWakeLock(isPlaying)
        com.swift.browser.notificationengine.NotificationChannelManager.createNotificationChannels(this)
        val channelId = "swift_browser_media"

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText("Playing in SwiftBrowser Audio Engine")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
            )
            .setOngoing(isPlaying)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(7753, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(7753, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        updateWakeLock(false)
        serviceJob.cancel()
        mediaSession?.release()
        notificationEngine.clearNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
