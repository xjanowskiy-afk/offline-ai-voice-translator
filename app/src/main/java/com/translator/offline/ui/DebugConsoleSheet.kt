package com.translator.offline.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

object AppLogger {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(message: String) {
        val entry = "[${timeFormat.format(Date())}] $message"
        _logs.value = _logs.value + entry
    }

    fun getAllLogsText(): String {
        return _logs.value.joinToString("\n")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugConsoleSheet(onDismiss: () -> Unit) {
    val logs by AppLogger.logs.collectAsState()
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "СИСТЕМНЫЙ ЖУРНАЛ (LOGS)",
                        color = Color(0xFF38BDF8),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "${logs.size} записей",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp
                    )
                }

                Button(
                    onClick = {
                        val text = AppLogger.getAllLogsText()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Translator Logs", text)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Логи скопированы в буфер обмена!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Копировать",
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Скопировать все", fontSize = 12.sp, color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF020617), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                items(logs) { logLine ->
                    val color = when {
                        logLine.contains("ОШИБКА", ignoreCase = true) || logLine.contains("Exception", ignoreCase = true) -> Color(0xFFF87171)
                        logLine.contains("результат инициализации движков: true", ignoreCase = true) || logLine.contains("успешно", ignoreCase = true) -> Color(0xFF4ADE80)
                        logLine.contains("VAD", ignoreCase = true) -> Color(0xFFFDE047)
                        logLine.contains("ASR", ignoreCase = true) -> Color(0xFF38BDF8)
                        else -> Color(0xFF94A3B8)
                    }
                    Text(
                        logLine,
                        color = color,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}
