package com.fethica.playbackkit.sample.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.media3.session.MediaController
import com.fethica.playbackkit.sample.BuildConfig

/** The player screen, or the developer panel on top of it in debug builds. */
@Composable
fun SampleApp(
    controller: MediaController?,
    askForNotifications: Boolean,
    onNotificationsAnswered: (Boolean) -> Unit,
) {
    var showPanel by rememberSaveable { mutableStateOf(false) }
    if (showPanel && BuildConfig.DEBUG) {
        DeveloperPanel(controller = controller, onClose = { showPanel = false })
    } else {
        SampleScreen(controller = controller, onOpenPanel = { showPanel = true })
    }
    if (askForNotifications) {
        AlertDialog(
            onDismissRequest = { onNotificationsAnswered(false) },
            title = { Text("Media notification") },
            text = {
                Text(
                    "Playback runs in a media session service. Android 13 and later need your " +
                        "permission to show its notification with the play and pause controls. " +
                        "The sample asks once.",
                )
            },
            confirmButton = { TextButton(onClick = { onNotificationsAnswered(true) }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { onNotificationsAnswered(false) }) { Text("Not now") } },
        )
    }
}
