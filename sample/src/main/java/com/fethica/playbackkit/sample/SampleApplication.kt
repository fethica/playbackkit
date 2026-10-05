package com.fethica.playbackkit.sample

import android.app.Application
import android.content.Context
import com.fethica.playbackkit.sample.fixtures.LoopbackServer

/** Starts the loopback fixture server, on the port it had last time when that is free. */
class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val server = LoopbackServer()
        // Bound off the main thread; joined so every item URL is valid before any UI runs.
        Thread({
            val port = runCatching { server.start(prefs.getInt(KEY_PORT, 0)) }.getOrNull()
            if (port != null) prefs.edit().putInt(KEY_PORT, port).apply()
        }, "loopback-server-start").apply {
            start()
            join(START_TIMEOUT_MS)
        }
        SampleGraph.server = server
    }

    private companion object {
        const val PREFS = "sample"
        const val KEY_PORT = "loopback_port"
        const val START_TIMEOUT_MS = 2_000L
    }
}
