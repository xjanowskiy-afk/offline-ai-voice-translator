package com.translator.offline.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.translator.offline.audio.AudioRoutingManager
import com.translator.offline.audio.AudioTranslationMode
import com.translator.offline.audio.EarChannel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioModeSelectorSheet(
    routingManager: AudioRoutingManager,
    swapLeftRight: Boolean,
    onToggleSwapEarbuds: () -> Unit,
    onTestSound: (EarChannel) -> Unit,
    onDismiss: () -> Unit
) {
    val currentMode by routingManager.currentMode.collectAsState()
    val isBtConnected by routingManager.isBluetoothConnected.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "МАРШРУТИЗАЦИЯ ЗВУКА",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                if (isBtConnected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BluetoothConnected, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("TWS A2DP", color = Color(0xFF38BDF8), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            ModeOptionCard(
                title = "На столе (Громкая связь)",
                desc = "Динамик смартфона в обе стороны (Half-Duplex Gate)",
                icon = Icons.Default.VolumeUp,
                isSelected = currentMode == AudioTranslationMode.TABLE_SPEAKER,
                onClick = { routingManager.setMode(AudioTranslationMode.TABLE_SPEAKER) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            ModeOptionCard(
                title = "Режим Суфлера (Я в наушниках)",
                desc = "Слушаю перевод собеседника в ухо (Full-Duplex)",
                icon = Icons.Default.Headphones,
                isSelected = currentMode == AudioTranslationMode.SOLO_PROMPTER,
                onClick = { routingManager.setMode(AudioTranslationMode.SOLO_PROMPTER) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            ModeOptionCard(
                title = "Раздельные наушники (Split-Earbud L/R)",
                desc = "Левый канал = RU (мне), Правый канал = DE (собеседнику)",
                icon = Icons.Default.CallSplit,
                isSelected = currentMode == AudioTranslationMode.SPLIT_EARBUDS,
                onClick = { routingManager.setMode(AudioTranslationMode.SPLIT_EARBUDS) }
            )

            AnimatedVisibility(visible = currentMode == AudioTranslationMode.SPLIT_EARBUDS) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .background(Color(0xFF1E293B).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Каналы наушников", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (!swapLeftRight) "Левый: РУССКИЙ • Правый: НЕМЕЦКИЙ" else "Левый: НЕМЕЦКИЙ • Правый: РУССКИЙ",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp
                            )
                        }
                        Button(
                            onClick = onToggleSwapEarbuds,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                        ) {
                            Text("Поменять L ↔ R", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedButton(
                            onClick = { onTestSound(EarChannel.LEFT_ONLY) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Тест Левый (L)", fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { onTestSound(EarChannel.RIGHT_ONLY) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Тест Правый (R)", fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun ModeOptionCard(
    title: String,
    desc: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .border(
                1.dp,
                if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155),
                RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFF1E293B) else Color(0xFF0F172A)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(if (isSelected) Color(0xFF2563EB) else Color(0xFF1E293B), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = if (isSelected) Color.White else Color(0xFF94A3B8))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(desc, color = Color(0xFF64748B), fontSize = 11.sp)
            }
            if (isSelected) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF38BDF8))
            }
        }
    }
}
