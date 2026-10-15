package com.translator.offline

import android.app.Application
import android.content.Intent
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter

class TranslatorApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()

                val intent = Intent(this, CrashAndLogActivity::class.java).apply {
                    putExtra(CrashAndLogActivity.EXTRA_CRASH_INFO, stackTrace)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
                Process.killProcess(Process.myPid())
                System.exit(10)
            } catch (e: Exception) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
