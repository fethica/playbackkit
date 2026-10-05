package com.fethica.playbackkit.sample.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.fethica.playbackkit.ItemKind
import com.fethica.playbackkit.sample.BuildConfig
import com.fethica.playbackkit.sample.LogSource
import com.fethica.playbackkit.sample.SampleGraph
import com.fethica.playbackkit.sample.SampleItems
import com.fethica.playbackkit.sample.fixtures.FixtureCatalog
import com.fethica.playbackkit.sample.fixtures.Scenario
import kotlinx.coroutines.delay

/**
 * Debug builds only (the caller checks BuildConfig.DEBUG). Server switches change what the
 * loopback server does on the next request; host switches change what the recovery host
 * answers. Every action is written to the event log.
 */
@Composable
fun DeveloperPanel(controller: Player?, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val server = SampleGraph.server
    val switches = SampleGraph.switches
    val offline by switches.simulateOffline.collectAsState()
    val veto by switches.sleepVeto.collectAsState()
    val replace by switches.replaceSource.collectAsState()

    // Which path the server switches apply to: the playing item's, unless chosen here.
    var targetId by rememberSaveable { mutableStateOf(controller?.currentMediaItem?.mediaId ?: SampleItems.finite.id) }
    val target = SampleItems.byId(targetId) ?: SampleItems.finite
    var once by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            refresh++
        }
    }

    fun panelLog(text: String) =
        SampleGraph.log.add(LogSource.PANEL, target.id, SampleGraph.recoveryStatus.value.generation, text)

    fun applyScenario(label: String, scenario: Scenario) {
        val applied = scenario.copy(once = once)
        server.setScenario(target.path, applied)
        refresh++
        panelLog("server ${target.path}: $label" + if (once) " (once, then serve again)" else "")
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Developer panel", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Done") }
            }

            Section("Server")
            for (item in SampleItems.all) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = item.id == targetId, onClick = { targetId = item.id }, role = Role.RadioButton),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = item.id == targetId, onClick = null)
                    Text(item.title, modifier = Modifier.padding(start = 8.dp))
                }
            }
            SwitchRow("Fail once, then serve again (reconnect)", once) { once = it }
            ButtonRow(
                "Delay first byte 5 s" to { applyScenario("delay first byte 5 s", Scenario(delayFirstByteMs = 5_000)) },
                "Delay first byte 20 s" to { applyScenario("delay first byte 20 s", Scenario(delayFirstByteMs = 20_000)) },
            )
            ButtonRow(
                "Stall 20 s after 3 s" to { applyScenario("stall 20 s after 3 s", Scenario(stallAfterMs = 3_000, stallForMs = 20_000)) },
                "Stall 60 s after 3 s" to { applyScenario("stall 60 s after 3 s", Scenario(stallAfterMs = 3_000, stallForMs = 60_000)) },
            )
            ButtonRow(
                "Disconnect after 3 s" to { applyScenario("disconnect after 3 s", Scenario(disconnectAfterMs = 3_000)) },
                "End stream after 5 s" to {
                    if (target.kind == ItemKind.LIVE) {
                        applyScenario("end cleanly after 5 s", Scenario(endAfterMs = 5_000))
                    } else {
                        panelLog("end stream applies to live streams; use Finite EOF below")
                    }
                },
            )
            ButtonRow(
                "HTTP 404" to { applyScenario("404", Scenario(failWithStatus = 404)) },
                "HTTP 500" to { applyScenario("500", Scenario(failWithStatus = 500)) },
            )
            ButtonRow(
                "Reset target" to {
                    server.reset(target.path)
                    refresh++
                    panelLog("server ${target.path}: reset")
                },
                "Reset all" to {
                    server.resetAll()
                    refresh++
                    panelLog("server: reset all")
                },
            )
            // Recomputed every second and after every switch: the server is not observable.
            val serverState = remember(refresh) {
                server.paths.joinToString("\n") { path ->
                    val scenario = server.scenario(path)
                    "$path  requests=${server.requestCount(path)}" + if (scenario != Scenario.NORMAL) "  $scenario" else ""
                }
            }
            Mono(serverState)

            Section("Player")
            ButtonRow(
                "Finite EOF" to finite@{
                    val player = controller ?: return@finite
                    if (SampleItems.kindOf(player.currentMediaItem?.mediaId) == ItemKind.FINITE && player.duration > 0) {
                        player.seekTo((player.duration - 5_000).coerceAtLeast(0))
                        panelLog("seek to 5 s before the end")
                    } else {
                        panelLog("finite EOF needs the finite item playing")
                    }
                },
                "Rapid reselect" to reselect@{
                    val player = controller ?: return@reselect
                    val item = SampleItems.byId(player.currentMediaItem?.mediaId) ?: target
                    panelLog("rapid reselect ${item.id} twice")
                    player.select(item)
                    player.select(item)
                },
            )

            Section("Host")
            SwitchRow("Simulate offline (hasNetwork = false)", offline) { switches.simulateOffline.value = it }
            SwitchRow("Sleep veto (automaticStartAllowed = false)", veto) {
                switches.sleepVeto.value = it
                panelLog("sleep veto $it")
            }
            SwitchRow("Replace source URI (retry live on its mirror)", replace) {
                switches.replaceSource.value = it
                panelLog("replace source $it")
            }
            ButtonRow(
                "Simulate suppression 10 s" to {
                    SampleGraph.service?.suppressFor(10) ?: panelLog("no service yet: play something first")
                },
                "Sleep now" to {
                    SampleGraph.service?.sleepNow() ?: panelLog("no service yet: play something first")
                },
            )

            Section("Diagnostics")
            Mono(
                "version ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_COMMIT})\n" +
                    "server ${if (server.isRunning) server.baseUrl else "not running"}\n" +
                    "fixtures generated tones (FixtureCatalog)\n" +
                    "finite sha256 ${FixtureCatalog.FINITE_SHA256.take(16)}...\n" +
                    "player volume 0.2",
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ButtonRow(vararg buttons: Pair<String, () -> Unit>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, action) in buttons) {
            OutlinedButton(onClick = action, modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
