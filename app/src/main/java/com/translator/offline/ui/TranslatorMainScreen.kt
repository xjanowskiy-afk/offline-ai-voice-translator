package com.translator.offline.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.translator.offline.viewmodel.MessageLog
import com.translator.offline.viewmodel.TranslatorViewModel

enum class TranslationMode {
    HANDS_FREE,
    PUSH_TO_TALK
}

@Composable
fun TranslatorMainScreen(
    viewModel: TranslatorViewModel,
    onOpenAudioSettings: () -> Unit,
    onOpenDebugLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val messages by viewModel.messages.collectAsState()
    val srcLang by viewModel.sourceLanguage.collectAsState()
    val dstLang by viewModel.targetLanguage.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val streamingText by viewModel.currentStreamingText.collectAsState()
    val audioAmp by viewModel.audioAmplitude.collectAsState()
    val isEnginesReady by viewModel.isEnginesReady.collectAsState()

    var activeMode by remember { mutableStateOf(TranslationMode.HANDS_FREE) }
    var visualState by remember { mutableStateOf(PipelineVisualState.IDLE) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(isRecording, streamingText) {
        visualState = when {
            streamingText.isNotEmpty() -> PipelineVisualState.THINKING
            isRecording -> PipelineVisualState.LISTENING
            else -> PipelineVisualState.IDLE
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090D16))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Top Bar (Air-gapped status + Audio & Logs buttons)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF0F1E2E))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isEnginesReady) Color(0xFF10B981) else Color(0xFFF59E0B))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isEnginesReady) "100% OFFLINE AI" else "ЗАГРУЗКА AI...",
                    color = if (isEnginesReady) Color(0xFF34D399) else Color(0xFFFBBF24),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onOpenAudioSettings,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B))
                ) {
                    Icon(Icons.Default.Headphones, contentDescription = "Аудио", tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onOpenDebugLogs,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B))
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = "Логи", tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Language Switch Card
        LanguageSwitchHeader(
            srcLang = srcLang,
            dstLang = dstLang,
            onSwap = {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                viewModel.swapLanguages()
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Message Feed or Empty State
        if (messages.isEmpty() && streamingText.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        tint = Color(0xFF334155),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Готов к синхронному переводу",
                        color = Color(0xFF64748B),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Включите микрофон и говорите — распознавание и перевод отобразятся здесь",
                        color = Color(0xFF475569),
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { msg ->
                    LiveMessageCard(msg)
                }
                if (streamingText.isNotEmpty()) {
                    item {
                        StreamingLiveBubble(streamingText)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Premium Soundbar Waveform (Apple Style)
        AudioVisualizer(
            visualState = visualState,
            amplitudeNorm = if (isRecording) audioAmp else 0.0f
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Mode switch pills (АВТО-СИНХРОН / РАЦИЯ)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            SegmentedModeButton(
                text = "АВТО-СИНХРОН",
                isSelected = activeMode == TranslationMode.HANDS_FREE
            ) {
                activeMode = TranslationMode.HANDS_FREE
            }
            Spacer(modifier = Modifier.width(8.dp))
            SegmentedModeButton(
                text = "РАЦИЯ (PTT)",
                isSelected = activeMode == TranslationMode.PUSH_TO_TALK
            ) {
                activeMode = TranslationMode.PUSH_TO_TALK
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Mic Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            val micBg = if (isRecording) {
                Brush.radialGradient(listOf(Color(0xFFEF4444), Color(0xFF991B1B)))
            } else {
                Brush.radialGradient(listOf(Color(0xFF2563EB), Color(0xFF1D4ED8)))
            }

            Box(
                modifier = Modifier
                    .size(76.dp)
                    .shadow(16.dp, CircleShape, spotColor = if (isRecording) Color.Red else Color(0xFF38BDF8))
                    .clip(CircleShape)
                    .background(micBg)
                    .pointerInput(activeMode) {
                        if (activeMode == TranslationMode.PUSH_TO_TALK) {
                            detectTapGestures(
                                onPress = {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    viewModel.toggleRecording(true)
                                    tryAwaitRelease()
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    viewModel.toggleRecording(false)
                                }
                            )
                        } else {
                            detectTapGestures(
                                onTap = {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    viewModel.toggleRecording(!isRecording)
                                }
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

@Composable
fun LanguageSwitchHeader(srcLang: String, dstLang: String, onSwap: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131D31).copy(alpha = 0.85f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ИСТОЧНИК",
                    fontSize = 10.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = if (srcLang == "ru") "🇷🇺 Русский" else "🇩🇪 Deutsch",
                    color = Color(0xFF60A5FA),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            IconButton(
                onClick = onSwap,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B))
            ) {
                Icon(Icons.Default.SwapHoriz, contentDescription = "Поменять местами", tint = Color.White)
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "ПЕРЕВОД",
                    fontSize = 10.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = if (dstLang == "de") "🇩🇪 Deutsch" else "🇷🇺 Русский",
                    color = Color(0xFF34D399),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun LiveMessageCard(msg: MessageLog) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131F33).copy(alpha = 0.9f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Source Recognized Text
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF60A5FA))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "СКАЗАНО",
                    color = Color(0xFF64748B),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = msg.sourceText,
                color = Color(0xFFE2E8F0),
                fontSize = 15.sp,
                lineHeight = 20.sp
            )

            // Translation Block
            if (msg.translatedText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Divider(color = Color(0xFF1E293B), thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34D399))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ПЕРЕВОД",
                        color = Color(0xFF10B981),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = msg.translatedText,
                    color = Color(0xFF34D399),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

@Composable
fun StreamingLiveBubble(tokenStream: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2338))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF38BDF8)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Генерация перевода...",
                    fontSize = 11.sp,
                    color = Color(0xFF38BDF8),
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = tokenStream,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
fun SegmentedModeButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Color(0xFF2563EB) else Color(0xFF1E293B))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) Color.White else Color(0xFF94A3B8)
        )
    }
}
