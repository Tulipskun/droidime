package com.tulipskun.droidime

import android.app.Application
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DroidImeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter()
                error.printStackTrace(PrintWriter(trace))
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val file = File(filesDir, "crash.log")
                FileOutputStream(file, true).bufferedWriter().use { writer ->
                    writer.appendLine("===== $timestamp =====")
                    writer.appendLine("Thread: ${thread.name}")
                    writer.appendLine(trace.toString())
                    writer.appendLine()
                }
            } catch (_: Throwable) {
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
