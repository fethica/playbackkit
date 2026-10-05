package com.fethica.playbackkit.sample

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where a log line came from. */
enum class LogSource { PLAYER, RECOVERY, HOST, PANEL }

/**
 * One structured line: when, which item, which playback-intent generation, what. Never a
 * URL: the text names paths and states only.
 */
data class LogEntry(
    val sequence: Long,
    val wallTimeMs: Long,
    val itemId: String?,
    val generation: Long,
    val source: LogSource,
    val text: String,
)

/** The sample's in-memory event log, newest first, capped. Safe to add from any thread. */
class EventLog(private val capacity: Int = 400) {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    private var nextSequence = 0L

    @Synchronized
    fun add(source: LogSource, itemId: String?, generation: Long, text: String) {
        val entry = LogEntry(nextSequence++, System.currentTimeMillis(), itemId, generation, source, text)
        _entries.value = (listOf(entry) + _entries.value).take(capacity)
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }
}
