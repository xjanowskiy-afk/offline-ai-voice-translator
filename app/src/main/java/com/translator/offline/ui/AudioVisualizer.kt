package com.translator.offline.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

enum class PipelineVisualState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

@Composable
fun AudioVisualizer(
    visualState: PipelineVisualState,
    amplitudeNorm: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveAnim")

    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val thinkingShimmer by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Status indicator pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(
                    when (visualState) {
                        PipelineVisualState.LISTENING -> Color(0xFF0C2A4D)
                        PipelineVisualState.THINKING -> Color(0xFF2C1654)
                        PipelineVisualState.SPEAKING -> Color(0xFF063327)
                        PipelineVisualState.IDLE -> Color(0xFF1E293B).copy(alpha = 0.5f)
                    }
                )
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when (visualState) {
                            PipelineVisualState.LISTENING -> Color(0xFF38BDF8)
                            PipelineVisualState.THINKING -> Color(0xFFA855F7)
                            PipelineVisualState.SPEAKING -> Color(0xFF34D399)
                            PipelineVisualState.IDLE -> Color(0xFF64748B)
                        }
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = when (visualState) {
                    PipelineVisualState.LISTENING -> if (amplitudeNorm > 0.05f) "СЛУШАЮ ГОЛОС..." else "МИКРОФОН АКТИВЕН"
                    PipelineVisualState.THINKING -> "НЕЙРОСЕТЬ ПЕРЕВОДИТ..."
                    PipelineVisualState.SPEAKING -> "ОЗВУЧИВАНИЕ РЕЧИ..."
                    PipelineVisualState.IDLE -> "РЕЖИМ ОЖИДАНИЯ"
                },
                color = when (visualState) {
                    PipelineVisualState.LISTENING -> Color(0xFF7DD3FC)
                    PipelineVisualState.THINKING -> Color(0xFFC084FC)
                    PipelineVisualState.SPEAKING -> Color(0xFF6EE7B7)
                    PipelineVisualState.IDLE -> Color(0xFF94A3B8)
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Premium Sound Waveform Bars / Dynamic Pill Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF0F172A).copy(alpha = 0.7f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val midY = canvasHeight / 2f

                when (visualState) {
                    PipelineVisualState.IDLE -> {
                        // Subtle sleeping baseline
                        drawLine(
                            color = Color(0xFF334155),
                            start = Offset(0f, midY),
                            end = Offset(canvasWidth, midY),
                            strokeWidth = 2.dp.toPx()
                        )
                    }

                    PipelineVisualState.LISTENING -> {
                        val barCount = 38
                        val spacing = canvasWidth / barCount
                        val barWidth = (spacing * 0.55f).coerceAtLeast(3f)

                        for (i in 0 until barCount) {
                            val normX = i.toFloat() / barCount
                            // Bell curve center weighting
                            val centerWeight = (1.0f - 4.0f * (normX - 0.5f) * (normX - 0.5f)).coerceAtLeast(0.15f)

                            val waveFactor = sin(normX * 12f + phase).toFloat()
                            val reactiveAmp = (amplitudeNorm * 1.5f).coerceIn(0.08f, 1.0f)
                            val barHeight = ((waveFactor * 0.4f + 0.6f) * reactiveAmp * centerWeight * (canvasHeight - 6.dp.toPx())).coerceAtLeast(4.dp.toPx())

                            val x = i * spacing + (spacing - barWidth) / 2f
                            val top = midY - barHeight / 2f

                            val barGradient = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF38BDF8),
                                    Color(0xFF6366F1),
                                    Color(0xFF818CF8)
                                ),
                                startY = top,
                                endY = top + barHeight
                            )

                            drawRoundRect(
                                brush = barGradient,
                                topLeft = Offset(x, top),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                            )
                        }
                    }

                    PipelineVisualState.THINKING -> {
                        // Apple-style Siri neural fluid bar
                        val shimmerX = canvasWidth * thinkingShimmer
                        val fluidBrush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF6366F1).copy(alpha = 0.3f),
                                Color(0xFFA855F7),
                                Color(0xFFEC4899),
                                Color(0xFF38BDF8),
                                Color(0xFF6366F1).copy(alpha = 0.3f)
                            ),
                            startX = shimmerX - canvasWidth * 0.4f,
                            endX = shimmerX + canvasWidth * 0.4f
                        )

                        drawRoundRect(
                            brush = fluidBrush,
                            topLeft = Offset(10.dp.toPx(), midY - 3.dp.toPx()),
                            size = Size(canvasWidth - 20.dp.toPx(), 6.dp.toPx()),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                        )
                    }

                    PipelineVisualState.SPEAKING -> {
                        // Energetic emerald speech wave
                        val barCount = 32
                        val spacing = canvasWidth / barCount
                        val barWidth = (spacing * 0.5f).coerceAtLeast(3f)

                        for (i in 0 until barCount) {
                            val normX = i.toFloat() / barCount
                            val waveFactor = sin(normX * 14f - phase * 1.5f).toFloat()
                            val barHeight = ((waveFactor * 0.5f + 0.5f) * 0.8f * (canvasHeight - 8.dp.toPx())).coerceAtLeast(4.dp.toPx())

                            val x = i * spacing + (spacing - barWidth) / 2f
                            val top = midY - barHeight / 2f

                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color(0xFF34D399), Color(0xFF059669))
                                ),
                                topLeft = Offset(x, top),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                            )
                        }
                    }
                }
            }
        }
    }
}
