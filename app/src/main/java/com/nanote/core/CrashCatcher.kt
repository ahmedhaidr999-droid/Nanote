package com.nanote.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Captures any uncaught exception to `files/crash/last_crash.txt` BEFORE the
 * process dies, so the next launch can show the report and let the user share
 * it (Nanote has no internet permission — sharing goes through ACTION_SEND).
 *
 * The write is intentionally synchronous and tiny: it runs on the crashing
 * thread, where nothing else is safe to do. If writing fails we silently fall
 * back to the system crash handler.
 */
object CrashCatcher {

    private const val DIR = "crash"
    private const val LAST = "last_crash.txt"
    private const val PREV = "prev_crash.txt"
    private const val MAX_REPORT_CHARS = 24_000

    fun install(context: Context) {
        val appContext = context.applicationContext
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Never let the reporter itself break the crash path.
            }
            systemHandler?.uncaughtException(thread, throwable)
        }
    }

    /** The report of the most recent crash, or null if the app never crashed. */
    fun readLast(context: Context): String? {
        val f = File(File(context.filesDir, DIR), LAST)
        return if (f.isFile) f.readText() else null
    }

    /** Call after the report has been seen/shared so it stops re-appearing. */
    fun clear(context: Context) {
        val dir = File(context.filesDir, DIR)
        val last = File(dir, LAST)
        if (last.isFile) {
            val prev = File(dir, PREV)
            if (prev.isFile) prev.delete()
            last.renameTo(prev)
        }
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        var stack = sw.toString()
        if (stack.length > MAX_REPORT_CHARS) {
            stack = stack.substring(0, MAX_REPORT_CHARS) + "\n…(truncated)"
        }

        val versionName = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Throwable) {
            "?"
        }

        val report = buildString {
            appendLine("=== Nanote crash report ===")
            appendLine("version: $versionName")
            appendLine("device: ${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("thread: ${thread.name}")
            appendLine("time: ${System.currentTimeMillis()}")
            appendLine()
            append(stack)
        }

        val dir = File(context.filesDir, DIR).apply { mkdirs() }

        // Keep one previous report so a *new* crash doesn't destroy the old one
        // before the user had a chance to share it.
        val last = File(dir, LAST)
        if (last.isFile) {
            val prev = File(dir, PREV)
            if (prev.isFile) prev.delete()
            last.renameTo(prev)
        }
        File(dir, LAST).writeText(report)
    }
}
