package com.fethica.playbackkit.sample

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.fethica.playbackkit.sample.ui.SampleApp
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/**
 * The one activity. It talks to [PlaybackService] through a [MediaController], the way any
 * controller (a notification, a watch, a car) would, and never touches the player itself.
 */
class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private var askForNotifications by mutableStateOf(false)
    private val mainThread = Executor { Handler(Looper.getMainLooper()).post(it) }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            SampleGraph.log.add(LogSource.PANEL, null, SampleGraph.recoveryStatus.value.generation, "notifications granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotifications = shouldAskForNotifications()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                SampleApp(
                    controller = controller,
                    askForNotifications = askForNotifications,
                    onNotificationsAnswered = ::onNotificationsAnswered,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({ controller = runCatching { future.get() }.getOrNull() }, mainThread)
    }

    override fun onStop() {
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    /** API 33+, not granted, and never asked before: the request is made once. */
    private fun shouldAskForNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
        return !getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)
    }

    private fun onNotificationsAnswered(proceed: Boolean) {
        askForNotifications = false
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
        if (proceed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val PREFS = "sample"
        const val KEY_ASKED = "notifications_asked"
    }
}
