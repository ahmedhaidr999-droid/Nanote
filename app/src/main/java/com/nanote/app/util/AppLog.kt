package com.nanote.app.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central diagnostic logger. Every subsystem logs through here so release
 * builds can be filtered and performance issues traced (see README §Diagnostics).
 */
object AppLog {
    private const val PREFIX = "Nanote."
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, msg: String) = Log.d(PREFIX + tag, msg)
    fun i(tag: String, msg: String) = Log.i(PREFIX + tag, msg)
    fun w(tag: String, msg: String) = Log.w(PREFIX + tag, msg)

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(PREFIX + tag, msg, t)
    }

    fun perf(tag: String, msg: String) {
        Log.i(PREFIX + "perf." + tag, "[$${timeFmt.format(Date())}] $msg")
    }
}
