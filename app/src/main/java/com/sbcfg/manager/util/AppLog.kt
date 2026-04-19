package com.sbcfg.manager.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {

    private const val TAG = "SbcfgApp"
    private const val MAX_LINES = 500

    data class Entry(val timestamp: String, val level: String, val tag: String, val message: String) {
        override fun toString(): String = "$timestamp [$level] $tag: $message"
    }

    private val _entries = mutableListOf<Entry>()
    private val _flow = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _flow.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun add(level: String, tag: String, message: String) {
        val entry = Entry(timeFormat.format(Date()), level, tag, message)
        synchronized(_entries) {
            _entries.add(entry)
            if (_entries.size > MAX_LINES) {
                _entries.removeAt(0)
            }
            _flow.value = _entries.toList()
        }
        // Also log to logcat
        when (level) {
            "E" -> Log.e(TAG, "[$tag] $message")
            "W" -> Log.w(TAG, "[$tag] $message")
            "I" -> Log.i(TAG, "[$tag] $message")
            else -> Log.d(TAG, "[$tag] $message")
        }
    }

    fun d(tag: String, message: String) = add("D", tag, message)
    fun i(tag: String, message: String) = add("I", tag, message)
    fun w(tag: String, message: String) = add("W", tag, message)
    fun e(tag: String, message: String) = add("E", tag, message)
    fun e(tag: String, message: String, t: Throwable) = add("E", tag, "$message: ${t.stackTraceToString()}")

    fun clear() {
        synchronized(_entries) {
            _entries.clear()
            _flow.value = emptyList()
        }
    }

    /** Full buffer as plain text for diagnostics export. */
    fun exportSnapshot(): String = synchronized(_entries) {
        _entries.joinToString("\n") { it.toString() }
    }
}
