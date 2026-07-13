package com.par9uet.jm.utils

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class LogEntry(
    val timestamp: String,
    val tag: String,
    val message: String,
    val level: String
) {
    val formatted: String get() = "[$timestamp][$level][$tag] $message"
}

object LogBuffer {
    private const val MAX_ENTRIES = 500
    private val entries = mutableListOf<LogEntry>()
    private val dateFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    @Synchronized
    fun append(tag: String, message: String, level: String = "D") {
        val time = LocalDateTime.now().format(dateFormatter)
        entries.add(LogEntry(time, tag, message, level))
        if (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }

    @Synchronized
    fun appendError(tag: String, message: String) {
        append(tag, message, "E")
    }

    @Synchronized
    fun getLogs(): List<LogEntry> {
        return entries.toList()
    }

    @Synchronized
    fun getLogText(): String {
        return entries.joinToString("\n") { it.formatted }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}