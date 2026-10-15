package com.translator.offline.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.translator.offline.PermissionAndSystemHelper
import com.translator.offline.downloader.ModelCategory
import com.translator.offline.downloader.ModelSetupManager

@Composable
fun OnboardingSetupScreen(
    setupManager: ModelSetupManager,
    onSetupFinished: () -> Unit
) {
    val context = LocalContext.current
    val progress by setupManager.progressState.collectAsState()

    var hasAudio by remember { mutableStateOf(PermissionAndSystemHelper.hasRecordAudioPermission(context)) }
    var hasNotif by remember { mutableStateOf(PermissionAndSystemHelper.hasNotificationPermission(context)) }
    var hasBattery by remember { mutableStateOf(PermissionAndSystemHelper.isIgnoringBatteryOptimizations(context)) }

    val allPermsGranted = hasAudio && hasNotif && hasBattery

    val bgBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFF090D16), Color(0xFF0F172A), Color(0xFF060911))
    )

    LaunchedEffect(allPermsGranted) {
        if (allPermsGranted) {
            setupManager.startDownloadPipeline {
                onSetupFinished()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "OFFLINE AI TRANSLATOR",
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                letterSpacing = 2.sp
            )
            Text(
                text = "100% On-Device Neural Pipeline • Snapdragon 8+ Gen 1",
                fontSize = 11.sp,
                color = Color(0xFF38BDF8),
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            if (!allPermsGranted) {
                Text(
                    text = "ШАГ 1: СИСТЕМНЫЕ РАЗРЕШЕНИЯ",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(12.dp))

                PermissionSetupCard(
                    title = "Запись микрофона",
                    desc = "Потоковый захват в Silero VAD и ASR",
                    icon = Icons.Default.Mic,
                    isGranted = hasAudio
                ) {
                    hasAudio = true
                }
                Spacer(modifier = Modifier.height(8.dp))
                PermissionSetupCard(
                    title = "Служба переднего плана",
                    desc = "Удержание 2.4 ГБ весов нейросетей в RAM",
                    icon = Icons.Default.Notifications,
                    isGranted = hasNotif
                ) {
                    hasNotif = true
                }
                Spacer(modifier = Modifier.height(8.dp))
                PermissionSetupCard(
                    title = "Отключение оптимизации One UI",
                    desc = "Защита ядер Cortex-X2 от принудительного Deep Sleep",
                    icon = Icons.Default.BatteryChargingFull,
                    isGranted = hasBattery
                ) {
                    PermissionAndSystemHelper.requestIgnoreBatteryOptimizations(context)
                    hasBattery = PermissionAndSystemHelper.isIgnoringBatteryOptimizations(context)
                }
            } else {
                Text(
                    text = "ШАГ 2: ИНИЦИАЛИЗАЦИЯ ИИ-ЯДРА",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF34D399),
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(16.dp))

                ModelCategoryRow(ModelCategory.VAD, progress.completedCategories.contains(ModelCategory.VAD), progress.currentModuleName)
                ModelCategoryRow(ModelCategory.ASR, progress.completedCategories.contains(ModelCategory.ASR), progress.currentModuleName)
                ModelCategoryRow(ModelCategory.LLM, progress.completedCategories.contains(ModelCategory.LLM), progress.currentModuleName)
                ModelCategoryRow(ModelCategory.TTS, progress.completedCategories.contains(ModelCategory.TTS), progress.currentModuleName)

                Spacer(modifier = Modifier.height(24.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1E293B))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.overallProgressPercent / 100f)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color(0xFF2563EB), Color(0xFF10B981))
                                )
                            )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (progress.isUnpacking) "Распаковка словарей..." else "${progress.downloadSpeedMbPerSec.format(1)} МБ/сек",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF38BDF8)
                    )
                    Text(
                        text = "${progress.overallProgressPercent}%",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                if (progress.error != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(progress.error ?: "", color = Color(0xFFEF4444), fontSize = 12.sp)
                }
            }
        }
    }
}

private fun Double.format(digits: Int) = "%.${digits}f".format(this)

@Composable
fun PermissionSetupCard(
    title: String,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isGranted) Color(0xFF10B981).copy(alpha = 0.5f) else Color(0xFF334155),
                RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) Color(0xFF064E3B).copy(alpha = 0.2f) else Color(0xFF1E293B).copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(icon, contentDescription = null, tint = if (isGranted) Color(0xFF10B981) else Color(0xFF94A3B8))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(desc, color = Color(0xFF64748B), fontSize = 11.sp)
                }
            }
            if (isGranted) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981))
            } else {
                Button(
                    onClick = onClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Включить", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun ModelCategoryRow(category: ModelCategory, isDone: Boolean, currentLoading: String) {
    val isCurrent = currentLoading.contains(category.name, ignoreCase = true)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(Color(0xFF1E293B).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(category.title, color = if (isDone) Color(0xFFE2E8F0) else Color(0xFF94A3B8), fontSize = 13.sp)
        if (isDone) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
        } else if (isCurrent) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = Color(0xFF38BDF8))
        } else {
            Icon(Icons.Default.Schedule, contentDescription = null, tint = Color(0xFF475569), modifier = Modifier.size(18.dp))
        }
    }
}
