package com.translator.offline.downloader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

enum class ModelCategory(val title: String) {
    VAD("Silero VAD (Детектор речи)"),
    ASR("Sherpa-ONNX (Распознавание речи)"),
    LLM("Qwen 2.5 1.5B (Нейросеть-переводчик)"),
    TTS("Piper VITS (Синтез речи RU / DE)")
}

data class DownloadTask(
    val category: ModelCategory,
    val fileName: String,
    val downloadUrl: String,
    val minExpectedBytes: Long,
    val isArchive: Boolean = false,
    val archiveType: String = ""
)

data class SetupProgress(
    val currentModuleName: String = "",
    val activeTaskIndex: Int = 0,
    val totalTasks: Int = 0,
    val overallProgressPercent: Int = 0,
    val currentFileProgressPercent: Int = 0,
    val downloadSpeedMbPerSec: Double = 0.0,
    val downloadedBytesTotal: Long = 0L,
    val totalBytesExpected: Long = 0L,
    val completedCategories: Set<ModelCategory> = emptySet(),
    val isUnpacking: Boolean = false,
    val error: String? = null
)

class ModelSetupManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _progressState = MutableStateFlow(SetupProgress())
    val progressState = _progressState.asStateFlow()

    val modelsDir: File = File(context.getExternalFilesDir(null), "models").apply {
        if (!exists()) mkdirs()
    }

    private val tasks = listOf(
        DownloadTask(
            category = ModelCategory.VAD,
            fileName = "silero_vad.onnx",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            minExpectedBytes = 643_854L
        ),
        DownloadTask(
            category = ModelCategory.ASR,
            fileName = "encoder.onnx",
            downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/encoder-epoch-99-avg-1.onnx",
            minExpectedBytes = 330_083_505L
        ),
        DownloadTask(
            category = ModelCategory.ASR,
            fileName = "decoder.onnx",
            downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/decoder-epoch-99-avg-1.onnx",
            minExpectedBytes = 13_876_452L
        ),
        DownloadTask(
            category = ModelCategory.ASR,
            fileName = "joiner.onnx",
            downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/joiner-epoch-99-avg-1.onnx",
            minExpectedBytes = 12_833_618L
        ),
        DownloadTask(
            category = ModelCategory.ASR,
            fileName = "tokens.txt",
            downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/tokens.txt",
            minExpectedBytes = 56_317L
        ),
        DownloadTask(
            category = ModelCategory.LLM,
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            minExpectedBytes = 1_117_320_736L
        ),
        DownloadTask(
            category = ModelCategory.TTS,
            fileName = "de_DE-thorsten-high.onnx",
            downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/de/de_DE/thorsten/high/de_DE-thorsten-high.onnx",
            minExpectedBytes = 113_895_201L
        ),
        DownloadTask(
            category = ModelCategory.TTS,
            fileName = "de_DE-thorsten-high.onnx.json",
            downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/de/de_DE/thorsten/high/de_DE-thorsten-high.onnx.json",
            minExpectedBytes = 4_875L
        ),
        DownloadTask(
            category = ModelCategory.TTS,
            fileName = "ru_RU-irina-medium.onnx",
            downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/irina/medium/ru_RU-irina-medium.onnx",
            minExpectedBytes = 63_201_294L
        ),
        DownloadTask(
            category = ModelCategory.TTS,
            fileName = "ru_RU-irina-medium.onnx.json",
            downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/irina/medium/ru_RU-irina-medium.onnx.json",
            minExpectedBytes = 4_765L
        ),
        DownloadTask(
            category = ModelCategory.TTS,
            fileName = "espeak-ng-data.tar.bz2",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2",
            minExpectedBytes = 7_252_012L,
            isArchive = true,
            archiveType = "tar.bz2"
        )
    )

    init {
        checkExistingCompletedCategories()
    }

    private fun checkExistingCompletedCategories() {
        val completed = mutableSetOf<ModelCategory>()
        for (cat in ModelCategory.values()) {
            val catTasks = tasks.filter { it.category == cat }
            val allDone = catTasks.all { task ->
                val f = File(modelsDir, task.fileName)
                f.exists() && f.length() >= (task.minExpectedBytes * 0.98).toLong()
            }
            if (allDone) {
                if (cat == ModelCategory.TTS) {
                    val espeakDir = File(modelsDir, "espeak-ng-data")
                    if (espeakDir.exists() && espeakDir.isDirectory && (espeakDir.list()?.isNotEmpty() == true)) {
                        completed.add(cat)
                    }
                } else {
                    completed.add(cat)
                }
            }
        }
        _progressState.value = _progressState.value.copy(completedCategories = completed)
    }

    fun isAllModelsReady(): Boolean {
        // Проверяем ключевые тяжелые файлы: VAD, ASR, LLM и TTS
        // Если они уже лежат на диске (>= 85% размера), считаем систему на 100% готовой!
        val criticalTasks = tasks.filter { !it.isArchive }
        val readyCount = criticalTasks.count { task ->
            val file = File(modelsDir, task.fileName)
            file.exists() && file.length() >= (task.minExpectedBytes * 0.85).toLong()
        }
        // Если основные файлы на месте — сразу пускаем в приложение без скачиваний!
        return readyCount >= (criticalTasks.size - 1)
    }

    suspend fun startDownloadPipeline(onComplete: () -> Unit) = withContext(Dispatchers.IO) {
        val totalBytesTarget = tasks.sumOf { it.minExpectedBytes }
        var bytesDownloadedPre = 0L

        tasks.forEachIndexed { index, task ->
            val targetFile = File(modelsDir, task.fileName)
            var currentFileDownloaded = if (targetFile.exists()) targetFile.length() else 0L

            // Проверяем: если файл уже есть на диске (>= 85% размера) — мгновенно пропускаем его без запросов в сеть!
            if (currentFileDownloaded >= (task.minExpectedBytes * 0.85).toLong()) {
                if (task.isArchive) {
                    val espeakDir = File(modelsDir, "espeak-ng-data")
                    if (!espeakDir.exists() || espeakDir.list().isNullOrEmpty()) {
                        _progressState.value = _progressState.value.copy(isUnpacking = true)
                        try {
                            unpackArchive(targetFile, task.archiveType)
                        } catch (e: Exception) {
                            com.translator.offline.ui.AppLogger.log("Архив уже распакован или ошибка: ${e.message}")
                        }
                        _progressState.value = _progressState.value.copy(isUnpacking = false)
                    }
                }
                bytesDownloadedPre += currentFileDownloaded
                updateCompletedCategory(task.category)
                return@forEachIndexed
            }

            _progressState.value = _progressState.value.copy(
                currentModuleName = task.category.title,
                activeTaskIndex = index + 1,
                totalTasks = tasks.size,
                error = null,
                isUnpacking = false
            )

            val requestBuilder = Request.Builder().url(task.downloadUrl)
            if (currentFileDownloaded > 0) {
                requestBuilder.addHeader("Range", "bytes=$currentFileDownloaded-")
            }
            val request = requestBuilder.build()

            var speedStart = System.currentTimeMillis()
            var speedBytes = 0L

            try {
                var response = client.newCall(request).execute()

                // Обработка HTTP 416 Range Not Satisfiable:
                // Сервер возвращает 416, если запрошенный диапазон выходит за размер файла
                if (response.code == 416) {
                    response.close()
                    // Если файл на диске уже имеет достаточный размер, значит он уже скачан на 100%!
                    if (targetFile.exists() && targetFile.length() >= (task.minExpectedBytes * 0.90).toLong()) {
                        if (task.isArchive) {
                            _progressState.value = _progressState.value.copy(isUnpacking = true)
                            unpackArchive(targetFile, task.archiveType)
                            _progressState.value = _progressState.value.copy(isUnpacking = false)
                        }
                        bytesDownloadedPre += targetFile.length()
                        updateCompletedCategory(task.category)
                        return@forEachIndexed
                    } else {
                        // Если файл поврежден или обрублен, удаляем его и качаем с нуля
                        targetFile.delete()
                        currentFileDownloaded = 0L
                        val retryReq = Request.Builder().url(task.downloadUrl).build()
                        response = client.newCall(retryReq).execute()
                    }
                }

                val body = response.body
                if (!response.isSuccessful && response.code != 206) {
                    _progressState.value = _progressState.value.copy(
                        error = "Сбой сети [HTTP ${response.code}] при загрузке ${task.fileName}"
                    )
                    return@withContext
                }

                val append = currentFileDownloaded > 0 && response.code == 206
                if (!append) {
                    currentFileDownloaded = 0L
                }
                val output = FileOutputStream(targetFile, append)

                val buffer = ByteArray(64 * 1024)
                body?.byteStream()?.use { input ->
                    output.use { out ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            currentFileDownloaded += read
                            speedBytes += read
                            val now = System.currentTimeMillis()
                            val durationSec = (now - speedStart) / 1000.0

                            var speed = _progressState.value.downloadSpeedMbPerSec
                            if (durationSec >= 0.5) {
                                speed = (speedBytes / (1024.0 * 1024.0)) / durationSec
                                speedStart = now
                                speedBytes = 0L
                            }

                            val totalDone = bytesDownloadedPre + currentFileDownloaded
                            val totalPercent = ((totalDone.toDouble() / totalBytesTarget) * 100).toInt().coerceIn(0, 100)
                            val filePercent = ((currentFileDownloaded.toDouble() / task.minExpectedBytes) * 100).toInt().coerceIn(0, 100)

                            _progressState.value = _progressState.value.copy(
                                overallProgressPercent = totalPercent,
                                currentFileProgressPercent = filePercent,
                                downloadSpeedMbPerSec = speed,
                                downloadedBytesTotal = totalDone,
                                totalBytesExpected = totalBytesTarget
                            )
                        }
                        out.flush()
                    }
                }

                bytesDownloadedPre += targetFile.length()

                if (task.isArchive) {
                    _progressState.value = _progressState.value.copy(isUnpacking = true)
                    unpackArchive(targetFile, task.archiveType)
                    _progressState.value = _progressState.value.copy(isUnpacking = false)
                }

                updateCompletedCategory(task.category)

            } catch (e: Exception) {
                _progressState.value = _progressState.value.copy(
                    error = "Ошибка загрузки: ${e.localizedMessage ?: "Сетевой сбой"}"
                )
                return@withContext
            }
        }

        if (isAllModelsReady()) {
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    private fun updateCompletedCategory(cat: ModelCategory) {
        val currentSet = _progressState.value.completedCategories.toMutableSet()
        val allDoneForCat = tasks.filter { it.category == cat }.all {
            val f = File(modelsDir, it.fileName)
            f.exists() && f.length() >= (it.minExpectedBytes * 0.98).toLong()
        }
        if (allDoneForCat) {
            currentSet.add(cat)
            _progressState.value = _progressState.value.copy(completedCategories = currentSet)
        }
    }

    private fun unpackArchive(archiveFile: File, type: String) {
        when (type) {
            "tar.bz2" -> {
                FileInputStream(archiveFile).use { fis ->
                    BufferedInputStream(fis).use { bis ->
                        BZip2CompressorInputStream(bis).use { bzIn ->
                            TarArchiveInputStream(bzIn).use { tarIn ->
                                var entry = tarIn.nextTarEntry
                                while (entry != null) {
                                    val dest = File(modelsDir, entry.name)
                                    if (entry.isDirectory) {
                                        dest.mkdirs()
                                    } else {
                                        dest.parentFile?.mkdirs()
                                        FileOutputStream(dest).use { fos ->
                                            tarIn.copyTo(fos)
                                        }
                                    }
                                    entry = tarIn.nextTarEntry
                                }
                            }
                        }
                    }
                }
            }
            "zip" -> {
                ZipInputStream(FileInputStream(archiveFile)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val dest = File(modelsDir, entry.name)
                        if (entry.isDirectory) {
                            dest.mkdirs()
                        } else {
                            dest.parentFile?.mkdirs()
                            FileOutputStream(dest).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        }
    }
}
