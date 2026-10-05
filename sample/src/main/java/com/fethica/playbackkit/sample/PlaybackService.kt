package com.fethica.playbackkit.sample

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.fethica.playbackkit.FailureReportDeduper
import com.fethica.playbackkit.PlaybackRecovery
import com.fethica.playbackkit.RecoveryEvent
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * The sample's own media session service. **Sample-owned, not part of the library**:
 * playbackkit ships no service, no player and no notification. This is the code a host
 * app writes around [PlaybackRecovery]:
 *
 * - it owns one [ExoPlayer], built on the main looper, which is the player's application
 *   looper;
 * - it attaches [PlaybackRecovery] on that looper with a [SampleRecoveryHost], and
 *   detaches it before the player is released;
 * - it forwards network arrivals from a `ConnectivityManager` callback (any thread) and
 *   from the panel's "simulate offline" switch turning off;
 * - it logs every player callback and every [RecoveryEvent] to [SampleGraph.log].
 *
 * The session and its notification are Media3's defaults (DefaultMediaNotificationProvider).
 */
class PlaybackService : MediaSessionService(), ServiceHooks {

    private lateinit var player: ExoPlayer
    private lateinit var recovery: PlaybackRecovery
    private var session: MediaSession? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val deduper = FailureReportDeduper()
    private var focusRelease: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Quiet by default: the fixtures are beeps, not music.
        player.volume = DEFAULT_VOLUME

        val host = SampleRecoveryHost(this, SampleGraph.switches, SampleGraph.server, ::onRecoveryEvent)
        // On the main looper, which is the player's application looper.
        recovery = PlaybackRecovery.attach(player, host)
        // Registered after recovery's own listener, so each line carries the generation
        // recovery has already moved to for that callback.
        player.addListener(PlayerLogger())

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

        registerNetworkCallback()
        // A simulated arrival when "simulate offline" goes back off.
        scope.launch {
            SampleGraph.switches.simulateOffline.drop(1).collect { offline ->
                log(LogSource.HOST, if (offline) "simulate offline on" else "simulate offline off: network arrival")
                if (!offline) recovery.onNetworkArrival()
            }
        }
        SampleGraph.service = this
        log(LogSource.HOST, "service created, volume $DEFAULT_VOLUME")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        SampleGraph.service = null
        scope.cancel()
        focusRelease?.let { mainHandler.removeCallbacks(it); it.run() }
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        // Recovery first, then the player it watches.
        recovery.detach()
        session?.release()
        session = null
        player.release()
        SampleGraph.recoveryStatus.value = RecoveryStatus("detached", recovery.generation)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- panel hooks

    override fun sleepNow() {
        // The documented host stop: veto first, then cancel, then pause.
        SampleGraph.switches.sleepVeto.value = true
        recovery.cancelAutomaticWork()
        player.pause()
        log(LogSource.HOST, "sleep now: veto on, automatic work cancelled, paused")
    }

    override fun suppressFor(seconds: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRelease?.let { mainHandler.removeCallbacks(it); it.run() }
        val listener = AudioManager.OnAudioFocusChangeListener { }
        val release: Runnable
        val granted: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener(listener)
                .build()
            granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            release = Runnable { audio.abandonAudioFocusRequest(request) }
        } else {
            @Suppress("DEPRECATION")
            granted = audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            @Suppress("DEPRECATION")
            release = Runnable { audio.abandonAudioFocus(listener) }
        }
        log(LogSource.HOST, "suppression: transient focus for $seconds s (granted=$granted)")
        val done = Runnable {
            release.run()
            focusRelease = null
            log(LogSource.HOST, "suppression: focus abandoned")
        }
        focusRelease = done
        mainHandler.postDelayed(done, seconds * 1_000L)
    }

    // ---------------------------------------------------------------- network

    private fun registerNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            // Any thread is fine for recovery; the log line is posted to main.
            override fun onAvailable(network: Network) {
                recovery.onNetworkArrival()
                mainHandler.post { log(LogSource.HOST, "network available: arrival signalled") }
            }

            override fun onLost(network: Network) {
                mainHandler.post { log(LogSource.HOST, "network lost") }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    // ---------------------------------------------------------------- events

    private fun onRecoveryEvent(event: RecoveryEvent) {
        val report = deduper.shouldReport(event)
        val text = describe(event) + if (report) "  [report once]" else ""
        SampleGraph.log.add(LogSource.RECOVERY, event.mediaId, event.generation, text)
        val summary = when (event) {
            is RecoveryEvent.RungScheduled -> "retrying ${event.kind.name.lowercase()} #${event.attempt} in ${event.delayMs} ms"
            is RecoveryEvent.AwaitingNetwork -> "waiting for network"
            is RecoveryEvent.WaitingForSuppression -> "waiting for suppression to lift"
            is RecoveryEvent.FiniteRecovered -> "recovered at ${event.positionMs / 1_000} s"
            is RecoveryEvent.GaveUp -> "gave up (${event.reason})"
            is RecoveryEvent.LadderReset -> "idle"
            is RecoveryEvent.FailureThresholdReached -> SampleGraph.recoveryStatus.value.summary
        }
        SampleGraph.recoveryStatus.value = RecoveryStatus(summary, event.generation)
        if (event is RecoveryEvent.GaveUp) {
            // Feedback on every give-up; the analytics-style report above is once per incident.
            Toast.makeText(this, getString(R.string.playback_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun describe(event: RecoveryEvent): String = when (event) {
        is RecoveryEvent.RungScheduled ->
            "rung ${event.kind.name.lowercase()} #${event.attempt} in ${event.delayMs} ms (${event.reason})" +
                if (event.positionMs > 0) " at ${event.positionMs} ms" else ""
        is RecoveryEvent.AwaitingNetwork -> "awaiting network (${event.reason})"
        is RecoveryEvent.WaitingForSuppression -> "waiting for suppression"
        is RecoveryEvent.FiniteRecovered -> "finite recovered #${event.attempt} at ${event.positionMs} ms"
        is RecoveryEvent.FailureThresholdReached ->
            "threshold (${event.reason}) retries=${event.retries} failures=${event.failures} terminal=${event.terminal}"
        is RecoveryEvent.GaveUp ->
            "gave up ${event.kind.name.lowercase()} (${event.reason}) retries=${event.retries} permanent=${event.permanent}"
        is RecoveryEvent.LadderReset -> "ladder reset: ${event.cause.name.lowercase()}"
    }

    private fun log(source: LogSource, text: String) {
        val generation = if (::recovery.isInitialized) recovery.generation else 0L
        val itemId = if (::player.isInitialized) player.currentMediaItem?.mediaId else null
        SampleGraph.log.add(source, itemId, generation, text)
    }

    private inner class PlayerLogger : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
            logPlayer("transition (${transitionReason(reason)})")

        override fun onPlaybackStateChanged(playbackState: Int) = logPlayer("state ${stateName(playbackState)}")

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
            logPlayer("playWhenReady=$playWhenReady (${playWhenReadyReason(reason)})")

        override fun onIsPlayingChanged(isPlaying: Boolean) = logPlayer("isPlaying=$isPlaying")

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
            logPlayer("suppression=${suppressionName(playbackSuppressionReason)}")

        // The HTTP status, when there is one, is in recovery's reason on the next line.
        override fun onPlayerError(error: PlaybackException) = logPlayer("error ${error.errorCodeName}")

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) logPlayer("seek to ${newPosition.positionMs} ms")
        }

        private fun logPlayer(text: String) {
            SampleGraph.log.add(LogSource.PLAYER, player.currentMediaItem?.mediaId, recovery.generation, text)
            val status = SampleGraph.recoveryStatus.value
            if (status.generation != recovery.generation) {
                SampleGraph.recoveryStatus.value = status.copy(generation = recovery.generation)
            }
        }
    }

    // ---------------------------------------------------------------- session

    /**
     * Controllers send media ids only; the session resolves them to playable items here, so
     * no URL ever leaves the service.
     */
    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map(::resolve).toMutableList())
    }

    private fun resolve(requested: MediaItem): MediaItem {
        val item = SampleItems.byId(requested.mediaId) ?: return requested
        return MediaItem.Builder()
            .setMediaId(item.id)
            .setUri(SampleGraph.server.urlFor(item.path))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(getString(R.string.app_name))
                    .setArtworkData(tilePng(item.tileHue), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()
    }

    /** The same generated colour the screen draws, as a small PNG for the notification. */
    private fun tilePng(hue: Float): ByteArray {
        val bitmap = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.HSVToColor(floatArrayOf(hue, TILE_SATURATION, TILE_VALUE)))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    internal companion object {
        const val DEFAULT_VOLUME = 0.2f
        const val TILE_SATURATION = 0.45f
        const val TILE_VALUE = 0.75f
        private const val TILE_PX = 96

        fun stateName(state: Int): String = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "?$state"
        }

        fun suppressionName(reason: Int): String = when (reason) {
            Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "none"
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "transient focus loss"
            else -> "reason $reason"
        }

        fun transitionReason(reason: Int): String = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlist changed"
            else -> "reason $reason"
        }

        fun playWhenReadyReason(reason: Int): String = when (reason) {
            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "user"
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "focus loss"
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "noisy"
            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
            Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end of item"
            else -> "reason $reason"
        }
    }
}
