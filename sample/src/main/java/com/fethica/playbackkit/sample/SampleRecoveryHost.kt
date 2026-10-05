package com.fethica.playbackkit.sample

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.fethica.playbackkit.ItemKind
import com.fethica.playbackkit.RecoveryEvent
import com.fethica.playbackkit.RecoveryHost
import com.fethica.playbackkit.sample.fixtures.LoopbackServer

/**
 * The sample's answers to recovery's questions. Every method runs on the player's looper
 * (main, here) and each answer is a plain read of a developer-panel switch or the system.
 */
class SampleRecoveryHost(
    context: Context,
    private val switches: DevSwitches,
    private val server: LoopbackServer,
    private val onRecoveryEvent: (RecoveryEvent) -> Unit,
) : RecoveryHost {

    private val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    override fun itemKind(mediaId: String?): ItemKind = SampleItems.kindOf(mediaId)

    /** The INTERNET capability of the default network, unless the panel simulates offline. */
    override fun hasNetwork(): Boolean = !switches.simulateOffline.value && systemHasInternet()

    /**
     * Fail-open: INTERNET, validated or not, and true when the system cannot be asked. No
     * default network at all is the one case that answers false. The loopback server
     * keeps working offline, so a real offline device does not make these fixtures fail;
     * "simulate offline" is how the sample exercises the network wait.
     */
    private fun systemHasInternet(): Boolean = try {
        val manager = connectivity ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: SecurityException) {
        true
    }

    /** False while the panel's sleep veto is on: only a person pressing play starts audio. */
    override fun automaticStartAllowed(): Boolean = !switches.sleepVeto.value

    /**
     * The live item's mirror while "replace source URI" is on, else null. Turns a
     * permanent failure (a 404) into a retry on the mirror, with the media id kept.
     */
    override fun retrySourceFor(mediaId: String?, currentUri: String?, permanent: Boolean): String? {
        if (!switches.replaceSource.value) return null
        val mirror = SampleItems.byId(mediaId)?.mirrorPath ?: return null
        return server.urlFor(mirror)
    }

    override fun onEvent(event: RecoveryEvent) = onRecoveryEvent(event)
}
