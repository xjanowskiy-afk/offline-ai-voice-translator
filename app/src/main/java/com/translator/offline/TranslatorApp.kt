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

                // 1. Пишем лог падения в файл на накопитель, чтобы пользователь/разработчик всегда мог его прочесть
                try {
                    val crashFile = java.io.File(getExternalFilesDir(null) ?: filesDir, "crash_log.txt")
                    crashFile.writeText(stackTrace)
                } catch (_: Throwable) {}

                // 2. Запускаем CrashAndLogActivity в изолированном процессе :crash_handler
                val intent = Intent(this, CrashAndLogActivity::class.java).apply {
                    putExtra(CrashAndLogActivity.EXTRA_CRASH_INFO, stackTrace)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)

                // 3. Небольшая задержка перед завершением упавшего процесса, чтобы Intent успел дойти до системы
                Thread.sleep(300)
                Process.killProcess(Process.myPid())
                System.exit(10)
            } catch (e: Throwable) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
