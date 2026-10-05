package com.fethica.playbackkit.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.fethica.playbackkit.ItemKind
import com.fethica.playbackkit.PlayPauseAction
import com.fethica.playbackkit.isEffectivelyPlaying
import com.fethica.playbackkit.playPauseActionFor
import com.fethica.playbackkit.sample.BuildConfig
import com.fethica.playbackkit.sample.LogEntry
import com.fethica.playbackkit.sample.PlaybackService
import com.fethica.playbackkit.sample.SampleGraph
import com.fethica.playbackkit.sample.SampleItem
import com.fethica.playbackkit.sample.SampleItems
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Selects [item] as a new playback intent: set, prepare, play. */
fun Player.select(item: SampleItem) {
    setMediaItem(MediaItem.Builder().setMediaId(item.id).build())
    prepare()
    play()
}

@Composable
fun SampleScreen(controller: Player?, onOpenPanel: () -> Unit) {
    val tick = rememberPlayerTick(controller)
    val status by SampleGraph.recoveryStatus.collectAsState()
    val entries by SampleGraph.log.entries.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("playbackkit sample", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (BuildConfig.DEBUG) TextButton(onClick = onOpenPanel) { Text("Developer") }
            }
            if (controller == null) {
                Text("Connecting to the player...", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            val currentId = controller.currentMediaItem?.mediaId
            Spacer(Modifier.height(8.dp))
            for (item in SampleItems.all) {
                ItemRow(item = item, selected = item.id == currentId, onClick = { controller.select(item) })
            }
            Spacer(Modifier.height(8.dp))
            // The tick is passed down so these re-read the controller when it moves.
            Transport(controller, tick)
            Seek(controller, currentId, tick)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "state ${PlaybackService.stateName(controller.playbackState)} · " +
                    "playWhenReady ${controller.playWhenReady} · isPlaying ${controller.isPlaying}\n" +
                    "suppression ${PlaybackService.suppressionName(controller.playbackSuppressionReason)}\n" +
                    "recovery ${status.summary} · generation ${status.generation}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Event log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { SampleGraph.log.clear() }) { Text("Clear") }
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(entries, key = { it.sequence }) { LogRow(it) }
            }
        }
    }
}

@Composable
private fun ItemRow(item: SampleItem, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.hsv(item.tileHue, PlaybackService.TILE_SATURATION, PlaybackService.TILE_VALUE)),
            contentAlignment = Alignment.Center,
        ) {
            Text(item.title.take(1), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (item.kind == ItemKind.LIVE) "live · ${item.id}" else "finite · ${item.id}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun Transport(controller: Player, tick: Int) {
    val playing = isEffectivelyPlaying(controller.playWhenReady, controller.isPlaying, controller.playbackState)
    val hasItem = controller.mediaItemCount > 0
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = hasItem,
            onClick = {
                when (playPauseActionFor(controller.playWhenReady, controller.playbackState)) {
                    PlayPauseAction.PAUSE -> controller.pause()
                    PlayPauseAction.RESTART_FROM_END -> {
                        controller.seekTo(0)
                        controller.play()
                    }
                    PlayPauseAction.PREPARE_AND_PLAY -> {
                        controller.prepare()
                        controller.play()
                    }
                    PlayPauseAction.PLAY -> controller.play()
                }
            },
        ) { Text(if (playing) "Pause" else "Play") }
        OutlinedButton(enabled = hasItem, onClick = { controller.stop() }) { Text("Stop") }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun Seek(controller: Player, currentId: String?, tick: Int) {
    if (SampleItems.kindOf(currentId) != ItemKind.FINITE || currentId == null) return
    val duration = controller.duration
    if (duration == C.TIME_UNSET || duration <= 0) return
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: (controller.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { controller.seekTo((it * duration).toLong()) }
                dragging = null
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${formatSeconds((fraction * duration).toLong())} / ${formatSeconds(duration)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
private fun LogRow(entry: LogEntry) {
    Text(
        text = "${timeFormat.format(Date(entry.wallTimeMs))} g${entry.generation} " +
            "${entry.source.name.lowercase()} ${entry.itemId ?: "-"}  ${entry.text}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}
