package com.fethica.playbackkit.sample.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import kotlinx.coroutines.delay

/**
 * A counter that moves on every player event and twice a second (for the position).
 * Reading it in a composable makes that composable re-read the controller's state.
 */
@Composable
fun rememberPlayerTick(player: Player?): Int {
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                tick++
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (player != null) {
            delay(500)
            tick++
        }
    }
    return tick
}

fun formatSeconds(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1_000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
