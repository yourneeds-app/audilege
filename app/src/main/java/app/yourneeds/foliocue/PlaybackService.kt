package app.yourneeds.foliocue

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.*

class PlaybackService : Service() {
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var artwork: Bitmap? = null
    private var artFile: String? = null
    private var artJob: Job? = null
    private var lastUpdate = 0L
    private var currentChapter = -1
    private var systemControlId: String? = null
    private var routeVolume: SpeakerVolume? = null
    var foreground = false
        private set
    private val player get() = AudilegePlayback
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && player.outputId == null) player.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        player.service = this
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,getString(R.string.audio_channel),NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "Audilege").apply {
            setSessionActivity(openApp())
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    // Android 15 grants audio focus to a background app after its media service is foreground.
                    if (!foreground && runCatching { promote() }.isFailure) return
                    player.resume()
                    if (!player.playing) refresh(force = true)
                }
                override fun onPause() = player.pause()
                override fun onStop() = player.stop()
                override fun onSkipToPrevious() = player.chapterBy(-1)
                override fun onSkipToNext() = player.chapterBy(1)
                override fun onRewind() = player.skip(-1)
                override fun onFastForward() = player.skip(1)
                override fun onSeekTo(pos: Long) = player.seekTo(pos.coerceIn(0,Int.MAX_VALUE.toLong()).toInt())
                override fun onSetPlaybackSpeed(speed: Float) = player.changeSpeed(speed)
            })
            isActive = true
        }
        if (Build.VERSION.SDK_INT >= 30) {
            AudilegeSystemRoutes.attach(this) { id ->
                systemControlId = id
                updateAudioOutput()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A pending foreground-service start must publish its notification even if the user immediately stopped.
        player.startPending = false
        if (!foreground) promote()
        when (intent?.action) {
            TOGGLE -> player.toggle()
            REWIND -> player.skip(-1)
            FORWARD -> player.skip(1)
            STOP -> player.stop()
        }
        if (player.activeBook == null) stopSession() else refresh(force = true)
        return START_NOT_STICKY
    }

    private fun promote() {
        startForeground(ID, notification())
        foreground = true
    }

    fun refresh(force: Boolean = false) {
        updateAudioOutput()
        val opened = player.activeBook ?: return
        val now = SystemClock.elapsedRealtime()
        val path = opened.file.absolutePath
        val changed = path != artFile || currentChapter != player.chapter
        if (path != artFile) {
            artFile = path
            artwork = null
            artJob?.cancel()
            artJob = scope.launch {
                val bitmap = CoverStore().get("notification:$path") {
                    playerCover(opened.publication, opened.online)
                }
                if (artFile == path && bitmap != null) {
                    val factor = minOf(1.0, 320.0 / maxOf(bitmap.width, bitmap.height))
                    artwork = Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt().coerceAtLeast(1),
                        (bitmap.height * factor).toInt().coerceAtLeast(1),true)
                    refresh(force = true)
                }
            }
        }
        if (!changed && !force && now - lastUpdate < 5000L) return
        lastUpdate = now
        currentChapter = player.chapter
        val progress = player.progress
        val duration = progress?.durationMs?.toLong() ?: 0
        val label = chapterLabel(opened)
        val data = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, label)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, opened.publication.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, opened.online?.detail?.author.orEmpty().ifBlank { opened.publication.author })
            .putLong(MediaMetadata.METADATA_KEY_DURATION,duration)
        artwork?.let { data.putBitmap(MediaMetadata.METADATA_KEY_ART,it) }
        session.setMetadata(data.build())
        val state = when {
            player.failed -> PlaybackState.STATE_ERROR
            player.loading -> PlaybackState.STATE_BUFFERING
            player.playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        var actions = PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_SEEK_TO
        if (player.chapter > 0) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (player.chapter < opened.publication.spine.lastIndex) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        session.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(state,progress?.elapsedMs?.toLong() ?: 0, if (state == PlaybackState.STATE_PLAYING) player.speed else 0f).build())
        if (!foreground && player.playing) promote()
        else updateMediaNotification()
        if (foreground && !player.playing) {
            stopForeground(STOP_FOREGROUND_DETACH)
            foreground = false
        }
    }

    private fun updateAudioOutput() {
        val speaker = player.outputId
        val current = routeVolume
        if (speaker == null) {
            if (current != null) {
                routeVolume = null
                session.setPlaybackToLocal(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            }
            return
        }
        val level = player.outputVolume
        if (current != null && current.speaker == speaker && current.controller == systemControlId) {
            if (current.currentVolume != level) current.setCurrentVolume(level)
            return
        }
        val provider = if (Build.VERSION.SDK_INT >= 30) SpeakerVolume(speaker, level, systemControlId)
            else SpeakerVolume(speaker, level)
        routeVolume = provider
        session.setPlaybackToRemote(provider)
    }

    private inner class SpeakerVolume : VolumeProvider {
        val speaker: String
        val controller: String?
        private var beforeMute = 25

        constructor(speaker: String, level: Int) : super(VOLUME_CONTROL_ABSOLUTE, 100, level) {
            this.speaker = speaker
            controller = null
        }

        @androidx.annotation.RequiresApi(30)
        constructor(speaker: String, level: Int, controller: String?) : super(VOLUME_CONTROL_ABSOLUTE, 100, level, controller) {
            this.speaker = speaker
            this.controller = controller
        }

        override fun onSetVolumeTo(volume: Int) = change { volume }
        override fun onAdjustVolume(direction: Int) = change { level ->
            when (direction) {
                AudioManager.ADJUST_RAISE -> level + 5
                AudioManager.ADJUST_LOWER -> level - 5
                AudioManager.ADJUST_MUTE -> 0
                AudioManager.ADJUST_UNMUTE -> if (level == 0) beforeMute else level
                AudioManager.ADJUST_TOGGLE_MUTE -> if (level > 0) 0 else beforeMute
                else -> level
            }
        }

        private fun change(target: (Int) -> Int) {
            scope.launch {
                if (routeVolume !== this@SpeakerVolume || player.outputId != speaker) return@launch
                val level = player.outputVolume
                val next = target(level).coerceIn(0, 100)
                if (next != level) {
                    if (next == 0 && level > 0) beforeMute = level
                    player.changeOutputVolume(next)
                }
            }
        }
    }

    private fun chapterLabel(opened: OpenBook): String {
        val item = opened.publication.spine.getOrNull(player.chapter)
        return opened.publication.contents.firstOrNull { it.path == item?.path }?.label?.takeIf { it.isNotBlank() }
            ?: getString(R.string.audio_chapter,player.chapter + 1)
    }

    // This MediaStyle notification carries the active session token. Android exempts such
    // media controls from the notification permission on API 33 and later.
    @android.annotation.SuppressLint("MissingPermission", "NotificationPermission")
    private fun updateMediaNotification() {
        getSystemService(NotificationManager::class.java).notify(ID, notification())
    }

    private fun notification(): Notification {
        val book = player.activeBook
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.audilege_mark)
            .setContentTitle(book?.publication?.title ?: getString(R.string.app))
            .setContentText(book?.let(::chapterLabel))
            .setContentIntent(openApp())
            .setOnlyAlertOnce(true)
            .setOngoing(player.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDeleteIntent(command(STOP))
            .setLargeIcon(artwork)
            .addAction(action(android.R.drawable.ic_media_rew, getString(R.string.audio_skip_back,player.skipSeconds), REWIND))
            .addAction(action(if (player.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(if (player.playing) R.string.pause else R.string.play),TOGGLE))
            .addAction(action(android.R.drawable.ic_media_ff, getString(R.string.audio_skip_forward,player.skipSeconds), FORWARD))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0,1,2))
            .build()
    }

    private fun openApp() = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).setAction(OPEN_PLAYER).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun command(action: String) = PendingIntent.getService(this, 0, Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(icon: Int, text: String, action: String) =
        Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,icon),text,command(action)).build()

    fun stopSession() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(ID)
        foreground = false
        stopSelf()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 30) AudilegeSystemRoutes.detach()
        player.service = null
        scope.cancel()
        session.release()
        unregisterReceiver(noisy)
        // Release audio if Android or the user terminates the service without an explicit stop command.
        if (player.activeBook != null && !player.startPending) player.stop()
        super.onDestroy()
    }

    companion object {
        const val OPEN_PLAYER = "app.yourneeds.foliocue.OPEN_PLAYER"
        private const val CHANNEL = "audilege_playback"
        private const val ID = 401
        private const val STOP = "stop"
        private const val TOGGLE = "toggle"
        private const val REWIND = "rewind"
        private const val FORWARD = "forward"
    }
}
