package com.translator.offline.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.translator.offline.nlp.TextNormalizer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

enum class EarChannel {
    BOTH,
    LEFT_ONLY,
    RIGHT_ONLY
}

class StreamingVoicePipeline(
    private val sampleRate: Int = 22050,
    private val ttsSynthesizeFn: (text: String, isTargetGerman: Boolean) -> FloatArray,
    private val onSpeakingStateChanged: (isSpeaking: Boolean) -> Unit
) {
    private val textChunksChannel = Channel<Pair<String, EarChannel>>(Channel.UNLIMITED)
    private val audioStereoChannel = Channel<FloatArray>(Channel.BUFFERED)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    var isSpeaking: Boolean = false
        private set

    @Volatile
    var swapLeftRight: Boolean = false

    @Volatile
    var isTargetGerman: Boolean = true

    private val audioTrack: AudioTrack by lazy {
        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBufSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().apply { play() }
    }

    init {
        scope.launch(Dispatchers.Default) {
            for ((rawChunk, requestedChannel) in textChunksChannel) {
                if (rawChunk.isNotBlank()) {
                    val lang = if (isTargetGerman) "de" else "ru"
                    val normalized = TextNormalizer.normalize(rawChunk, lang)
                    if (normalized.isNotBlank()) {
                        val monoPcm = ttsSynthesizeFn(normalized, isTargetGerman)
                        if (monoPcm.isNotEmpty()) {
                            val resolvedChannel = if (swapLeftRight) {
                                when (requestedChannel) {
                                    EarChannel.LEFT_ONLY -> EarChannel.RIGHT_ONLY
                                    EarChannel.RIGHT_ONLY -> EarChannel.LEFT_ONLY
                                    EarChannel.BOTH -> EarChannel.BOTH
                                }
                            } else {
                                requestedChannel
                            }

                            val stereoPcm = convertMonoToStereoPanned(monoPcm, resolvedChannel)
                            audioStereoChannel.send(stereoPcm)
                        }
                    }
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            for (stereoPcm in audioStereoChannel) {
                if (!isSpeaking) {
                    isSpeaking = true
                    onSpeakingStateChanged(true)
                }

                audioTrack.write(stereoPcm, 0, stereoPcm.size, AudioTrack.WRITE_BLOCKING)

                if (audioStereoChannel.isEmpty) {
                    delay(150)
                    isSpeaking = false
                    onSpeakingStateChanged(false)
                }
            }
        }
    }

    fun convertMonoToStereoPanned(monoPcm: FloatArray, targetChannel: EarChannel): FloatArray {
        val stereo = FloatArray(monoPcm.size * 2)
        when (targetChannel) {
            EarChannel.LEFT_ONLY -> {
                for (i in monoPcm.indices) {
                    stereo[i * 2] = monoPcm[i]
                    stereo[i * 2 + 1] = 0.0f
                }
            }
            EarChannel.RIGHT_ONLY -> {
                for (i in monoPcm.indices) {
                    stereo[i * 2] = 0.0f
                    stereo[i * 2 + 1] = monoPcm[i]
                }
            }
            EarChannel.BOTH -> {
                for (i in monoPcm.indices) {
                    val s = monoPcm[i]
                    stereo[i * 2] = s
                    stereo[i * 2 + 1] = s
                }
            }
        }
        return stereo
    }

    private val currentSentenceBuffer = StringBuilder()

    fun onNewTranslatedToken(token: String, isEndOfGeneration: Boolean, targetChannel: EarChannel) {
        currentSentenceBuffer.append(token)
        val text = currentSentenceBuffer.toString()
        val trimmed = text.trim()
        val wordCount = if (trimmed.isEmpty()) 0 else trimmed.split(Regex("\\s+")).size

        val hasPunctuationBreak = text.endsWith(",") ||
                text.endsWith(".") ||
                text.endsWith("!") ||
                text.endsWith("?") ||
                text.endsWith(":") ||
                text.endsWith(";") ||
                text.endsWith(" - ")

        if ((hasPunctuationBreak && wordCount >= 3) || (isEndOfGeneration && trimmed.isNotEmpty())) {
            val readyChunk = trimmed
            currentSentenceBuffer.clear()
            textChunksChannel.trySend(Pair(readyChunk, targetChannel))
        }
    }

    fun playTestBeep(channel: EarChannel) {
        scope.launch(Dispatchers.Default) {
            val freq = if (channel == EarChannel.LEFT_ONLY) 440.0 else 880.0
            val numSamples = (sampleRate * 0.4).toInt()
            val mono = FloatArray(numSamples)
            for (i in 0 until numSamples) {
                mono[i] = (0.3f * Math.sin(2.0 * Math.PI * i * freq / sampleRate)).toFloat()
            }
            val stereo = convertMonoToStereoPanned(mono, channel)
            audioStereoChannel.send(stereo)
        }
    }

    fun release() {
        scope.cancel()
        textChunksChannel.close()
        audioStereoChannel.close()
        try {
            audioTrack.stop()
            audioTrack.release()
        } catch (_: Exception) {}
    }
}
