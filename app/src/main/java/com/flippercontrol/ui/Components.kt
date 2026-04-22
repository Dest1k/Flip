package com.flippercontrol.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── Лог событий ─────────────────────────────────────────────────────────────

enum class LogLevel { INFO, OK, WARN, ERROR }

data class LogEntry(
    val time: String,
    val text: String,
    val level: LogLevel = LogLevel.INFO
)

fun buildLog(entries: List<LogEntry>, text: String, level: LogLevel = LogLevel.INFO): List<LogEntry> {
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    return (listOf(LogEntry(time, text, level)) + entries).take(100)
}

@Composable
fun ActivityLogPanel(
    entries: List<LogEntry>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    // Auto-scroll to top (newest) when new entries arrive
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(
        modifier
            .background(FlipperTheme.surface, RoundedCornerShape(8.dp))
    ) {
        // Header row
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Text(
                "LOG",
                color = FlipperTheme.textSecondary,
                fontSize = 9.sp,
                fontFamily = FlipperTheme.mono,
                letterSpacing = 2.sp,
                modifier = Modifier.weight(1f)
            )
            // Status dot — color of latest entry
            if (entries.isNotEmpty()) {
                val dotColor = when (entries.first().level) {
                    LogLevel.OK    -> FlipperTheme.green
                    LogLevel.ERROR -> FlipperTheme.red
                    LogLevel.WARN  -> FlipperTheme.yellow
                    LogLevel.INFO  -> FlipperTheme.textSecondary
                }
                Box(
                    Modifier
                        .size(6.dp)
                        .background(dotColor, RoundedCornerShape(50))
                )
            }
        }

        HorizontalDivider(color = FlipperTheme.border, thickness = 0.5.dp)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .height(88.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            if (entries.isEmpty()) {
                item {
                    androidx.compose.material3.Text(
                        "Нет событий",
                        color = FlipperTheme.textSecondary,
                        fontSize = 10.sp,
                        fontFamily = FlipperTheme.mono
                    )
                }
            }
            items(entries) { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Text(
                        "${entry.time} ",
                        color = FlipperTheme.textSecondary.copy(alpha = 0.5f),
                        fontSize = 9.sp,
                        fontFamily = FlipperTheme.mono
                    )
                    val textColor: Color = when (entry.level) {
                        LogLevel.OK    -> FlipperTheme.green
                        LogLevel.ERROR -> FlipperTheme.red
                        LogLevel.WARN  -> FlipperTheme.yellow
                        LogLevel.INFO  -> FlipperTheme.textSecondary
                    }
                    androidx.compose.material3.Text(
                        entry.text,
                        color = textColor,
                        fontSize = 10.sp,
                        fontFamily = FlipperTheme.mono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ─── Общий TopBar ─────────────────────────────────────────────────────────────

@Composable
fun TopBar(
    title: String,
    color: androidx.compose.ui.graphics.Color,
    onBack: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Text(
            "←",
            color = FlipperTheme.accent, fontSize = 20.sp,
            fontFamily = FlipperTheme.mono,
            modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp)
        )
        androidx.compose.material3.Text(
            title, color = color, fontSize = 20.sp,
            fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Black,
            letterSpacing = 3.sp
        )
    }
}

// ─── Empty state ──────────────────────────────────────────────────────────────

@Composable
fun EmptyState(message: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(FlipperTheme.surface, RoundedCornerShape(10.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Text(
            message,
            color = FlipperTheme.textSecondary,
            fontSize = 12.sp,
            fontFamily = FlipperTheme.mono,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 18.sp
        )
    }
}
