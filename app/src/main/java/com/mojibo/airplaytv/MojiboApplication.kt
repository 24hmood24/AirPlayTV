package com.mojibo.airplaytv

import android.app.Application
import android.content.Intent
import android.util.Log
import com.mojibo.airplaytv.player.NativeBridge
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class MojiboApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        try {
            NativeBridge.setCrashLogPath(File(getExternalFilesDir(null), "last_crash.txt").absolutePath)
        } catch (_: Throwable) {}

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val fullText = "Thread: ${thread.name}\n\n${sw}"

                Log.e("MojiboCrash", fullText)
                try { File(filesDir, "last_crash.txt").writeText(fullText) } catch (_: Exception) {}

                val intent = Intent(applicationContext, CrashDisplayActivity::class.java).apply {
                    putExtra("crash_text", fullText)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
            } catch (_: Exception) {
            }

            Thread.sleep(200)
            defaultHandler?.uncaughtException(thread, throwable)
                ?: exitProcess(1)
        }
    }
}