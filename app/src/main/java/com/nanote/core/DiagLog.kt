package com.nanote.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Lightweight diagnostic ring-buffer log (spec section 11: "diagnostic logs
 * for every stage: open file, pageCount, every page open, any failure").
 *
 * Everything also goes to logcat under the "Nanote.<tag>" tag. The last
 * [CAP] entries are kept in memory and viewable in Settings → Diagnostics.
 * Deliberately allocation-light: one formatted line per call, no JSON, no
 * disk I/O on the hot path.
 */
object DiagLog {

    private const val TAG_PREFIX = "Nanote."
    private const val CAP = 500

    private class Entry(val ts: Long, val tag: String, val msg: String) {
        override fun toString(): String {
            val t = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ts))
            return "$t  $tag  $msg"
        }
    }

    private val buffer = ArrayDeque<Entry>(CAP)

    fun log(tag: String, msg: String) {
        Log.d(TAG_PREFIX + tag, msg)
        synchronized(buffer) {
            if (buffer.size == CAP) buffer.removeFirst()
            buffer.addLast(Entry(System.currentTimeMillis(), tag, msg))
        }
    }

    fun warn(tag: String, msg: String) {
        Log.w(TAG_PREFIX + tag, msg)
        synchronized(buffer) {
            if (buffer.size == CAP) buffer.removeFirst()
            buffer.addLast(Entry(System.currentTimeMillis(), tag, "WARN $msg"))
        }
    }

    fun error(tag: String, msg: String, t: Throwable? = null) {
        Log.e(TAG_PREFIX + tag, msg, t)
        val line = "ERROR $msg" + (t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "")
        synchronized(buffer) {
            if (buffer.size == CAP) buffer.removeFirst()
            buffer.addLast(Entry(System.currentTimeMillis(), tag, line))
        }
    }

    /** Snapshot of the newest-last entries for the settings viewer. */
    fun snapshot(): List<String> = synchronized(buffer) { buffer.map { it.toString() } }
}
