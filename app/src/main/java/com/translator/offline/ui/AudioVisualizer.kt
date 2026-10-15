package com.translator.offline.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
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
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val pulseFactor by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radius"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing)
        ),
        label = "rotate"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = size.minDimension / 4f

        when (visualState) {
            PipelineVisualState.IDLE -> {
                drawCircle(
                    color = Color(0xFF334155).copy(alpha = 0.4f),
                    radius = baseRadius * 0.8f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            PipelineVisualState.LISTENING -> {
                val activeRadius = baseRadius * (1f + amplitudeNorm * 0.8f) * pulseFactor

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF3B82F6).copy(alpha = 0.35f), Color.Transparent),
                        center = center,
                        radius = activeRadius * 1.5f
                    ),
                    radius = activeRadius * 1.5f,
                    center = center
                )

                val bars = 24
                for (i in 0 until bars) {
                    val angle = (i * (360f / bars)) * (PI / 180f)
                    val barLen = (20.dp.toPx() + (amplitudeNorm * 60.dp.toPx())) * ((i % 3 + 1) * 0.4f)
                    val start = Offset(
                        center.x + (activeRadius * cos(angle)).toFloat(),
                        center.y + (activeRadius * sin(angle)).toFloat()
                    )
                    val end = Offset(
                        center.x + ((activeRadius + barLen) * cos(angle)).toFloat(),
                        center.y + ((activeRadius + barLen) * sin(angle)).toFloat()
                    )
                    drawLine(
                        color = if (i % 2 == 0) Color(0xFF38BDF8) else Color(0xFF3B82F6),
                        start = start,
                        end = end,
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                drawCircle(
                    color = Color(0xFF60A5FA),
                    radius = activeRadius,
                    center = center,
                    style = Stroke(width = 3.dp.toPx())
                )
            }
            PipelineVisualState.THINKING -> {
                rotate(rotationAngle, pivot = center) {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF3B82F6), Color(0xFF8B5CF6))
                        ),
                        radius = baseRadius * pulseFactor,
                        center = center,
                        style = Stroke(width = 4.dp.toPx())
                    )
                }
            }
            PipelineVisualState.SPEAKING -> {
                val speakRadius = baseRadius * 1.1f * pulseFactor

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF10B981).copy(alpha = 0.4f), Color(0xFFF59E0B).copy(alpha = 0.1f), Color.Transparent),
                        center = center,
                        radius = speakRadius * 1.6f
                    ),
                    radius = speakRadius * 1.6f,
                    center = center
                )

                drawCircle(
                    color = Color(0xFF10B981),
                    radius = speakRadius,
                    center = center,
                    style = Stroke(width = 4.dp.toPx())
                )
            }
        }
    }
}
