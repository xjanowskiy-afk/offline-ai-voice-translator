package com.translator.offline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugConsoleSheet(onDismiss: () -> Unit) {
    val logs by AppLogger.logs.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .padding(16.dp)
        ) {
            Text("ЖУРНАЛ СОБЫТИЙ (DEBUG CONSOLE)", color = Color(0xFF38BDF8), fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF020617), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                items(logs) { logLine ->
                    Text(logLine, color = Color(0xFF94A3B8), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
