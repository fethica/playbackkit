package com.fethica.playbackkit.sample

import com.fethica.playbackkit.ItemKind
import com.fethica.playbackkit.sample.fixtures.FixtureCatalog

/** One thing the sample can play. The id prefix decides the item kind. */
data class SampleItem(
    val id: String,
    val title: String,
    val path: String,
    /** Live streams only: a path with the same bytes and its own server scenario. */
    val mirrorPath: String?,
) {
    val kind: ItemKind get() = SampleItems.kindOf(id)

    /** A colour derived from the id, drawn in place of artwork. */
    val tileHue: Float get() = ((id.hashCode() % 360 + 360) % 360).toFloat()
}

object SampleItems {
    private const val LIVE_PREFIX = "live:"

    val finite = SampleItem("finite:tone-30s", "Finite tone, 30 s", FixtureCatalog.finite.path, mirrorPath = null)

    val liveA = SampleItem(
        "${LIVE_PREFIX}a",
        "Live-like stream A",
        FixtureCatalog.liveA.path,
        FixtureCatalog.mirrorPath(FixtureCatalog.liveA),
    )

    val liveB = SampleItem(
        "${LIVE_PREFIX}b",
        "Live-like stream B",
        FixtureCatalog.liveB.path,
        FixtureCatalog.mirrorPath(FixtureCatalog.liveB),
    )

    val all: List<SampleItem> = listOf(finite, liveA, liveB)

    fun byId(id: String?): SampleItem? = all.firstOrNull { it.id == id }

    /**
     * What [com.fethica.playbackkit.RecoveryHost.itemKind] answers: live for the `live:`
     * prefix, finite otherwise. Recovery never asks ExoPlayer whether an item is live; the
     * live-like streams here are not live to ExoPlayer at all (see the README).
     */
    fun kindOf(mediaId: String?): ItemKind =
        if (mediaId?.startsWith(LIVE_PREFIX) == true) ItemKind.LIVE else ItemKind.FINITE
}
