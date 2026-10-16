package com.translator.offline

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class CrashAndLogActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CRASH_INFO = "extra_crash_info"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashLog = intent.getStringExtra(EXTRA_CRASH_INFO) ?: "Неизвестная ошибка"

        val runtime = Runtime.getRuntime()
        val freeMemMb = runtime.freeMemory() / (1024 * 1024)
        val totalMemMb = runtime.totalMemory() / (1024 * 1024)
        val maxMemMb = runtime.maxMemory() / (1024 * 1024)

        val fullReport = "=== CRASH REPORT ===\n" +
                "RAM: Free $freeMemMb MB / Total $totalMemMb MB / Max $maxMemMb MB\n" +
                "Device: Samsung Galaxy Z Fold 4 (SM-F936B)\n" +
                "OS: Android 14 / One UI 6\n" +
                "====================\n\n" +
                crashLog

        try {
            renderComposeUi(fullReport)
        } catch (e: Throwable) {
            renderEmergencyViewUi(fullReport, e.message ?: "")
        }
    }

    private fun renderEmergencyViewUi(report: String, composeError: String) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#090D16"))
            setPadding(32, 32, 32, 32)
        }

        val title = android.widget.TextView(this).apply {
            text = "Сбой в работе приложения (Emergency Mode)"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        }
        layout.addView(title)

        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f
            )
            setBackgroundColor(android.graphics.Color.parseColor("#1E293B"))
        }

        val logView = android.widget.TextView(this).apply {
            text = if (composeError.isNotEmpty()) "$report\n\n[Compose Render Error: $composeError]" else report
            setTextColor(android.graphics.Color.parseColor("#F87171"))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(20, 20, 20, 20)
            setTextIsSelectable(true)
        }
        scrollView.addView(logView)
        layout.addView(scrollView)

        val btn = android.widget.Button(this).apply {
            text = "Скопировать полный лог ошибки"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#2563EB"))
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("CrashLog", logView.text.toString()))
                Toast.makeText(this@CrashAndLogActivity, "Лог скопирован в буфер обмена!", Toast.LENGTH_SHORT).show()
            }
        }
        val btnParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 20
        }
        layout.addView(btn, btnParams)

        setContentView(layout)
    }

    private fun renderComposeUi(fullReport: String) {
        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF090D16))
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Сбой в работе приложения", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                    ) {
                        Text(
                            text = fullReport,
                            color = Color(0xFFF87171),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .padding(14.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("CrashLog", fullReport))
                            Toast.makeText(this@CrashAndLogActivity, "Лог скопирован в буфер обмена!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Скопировать полный лог ошибки")
                    }
                }
            }
        }
    }
}
