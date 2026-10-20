package com.photosapp

import android.content.Context
import android.os.Build
import java.io.File

/** Preserve the last uncaught Java/Kotlin crash, then let Android's normal crash handler run. */
object CrashDiagnostics {
    private const val FILE_NAME = "last_crash.txt"
    fun install(context: Context) {
        val folder = context.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(folder, FILE_NAME).writeText("Photos App ${BuildConfig.VERSION_NAME}\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nThread: ${thread.name}\n\n" + error.stackTraceToString().take(96_000))
            } catch (_: Exception) { /* Crash reporting must never replace the original exception. */ }
            if (previous != null) previous.uncaughtException(thread, error)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }
    fun read(context: Context): String? = File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText()
}
