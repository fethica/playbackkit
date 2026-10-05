package com.fethica.playbackkit.sample.fixtures

import java.util.Collections

/** A clock whose sleeps return at once and only move time forward. Thread-safe. */
class FakeClock : ServerClock {
    private var now = 1_000_000L
    private val sleeps: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    /** Every sleep so far, in order. A copy: server threads may still be adding. */
    fun sleepsSnapshot(): List<Long> = ArrayList(sleeps)

    @Synchronized
    override fun nowMs(): Long = now

    @Synchronized
    override fun sleep(ms: Long) {
        if (ms <= 0) return
        now += ms
        sleeps += ms
    }

    @Synchronized
    fun elapsedSince(start: Long): Long = now - start
}
