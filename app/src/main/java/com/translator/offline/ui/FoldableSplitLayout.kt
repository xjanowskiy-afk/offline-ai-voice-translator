package com.translator.offline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.translator.offline.viewmodel.TranslatorViewModel

@Composable
fun FoldableSplitLayout(
    viewModel: TranslatorViewModel,
    onOpenAudioSettings: () -> Unit,
    onOpenDebugLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.messages.collectAsState()
    var isFlippedForPartner by remember { mutableStateOf(true) }

    val lastTranslatedText = messages.lastOrNull()?.translatedText
        ?: "Ожидание речи для перевода..."

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF0D131F))
                .then(if (isFlippedForPartner) Modifier.rotate(180f) else Modifier)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = lastTranslatedText,
                    color = Color(0xFF34D399),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 32.sp
                )
            }

            IconButton(
                onClick = { isFlippedForPartner = !isFlippedForPartner },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(Color(0xFF1E293B), CircleShape)
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Flip,
                    contentDescription = "Развернуть на 180°",
                    tint = Color.White
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Color(0xFF334155))
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            TranslatorMainScreen(
                viewModel = viewModel,
                onOpenAudioSettings = onOpenAudioSettings,
                onOpenDebugLogs = onOpenDebugLogs
            )
        }
    }
}
