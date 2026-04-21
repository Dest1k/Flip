package com.flippercontrol.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.flippercontrol.core.FsFile
import com.flippercontrol.core.FlipperRpcSession
import kotlinx.coroutines.launch

data class FrequencyPreset(
    val label: String,
    val hz: Long,
    val description: String,
)

val frequencyPresets = listOf(
    FrequencyPreset("433.92",  433920000L, "Гаражи · брелоки"),
    FrequencyPreset("315.00",  315000000L, "US авто · ворота"),
    FrequencyPreset("868.35",  868350000L, "EU IoT · сигнализации"),
    FrequencyPreset("915.00",  915000000L, "US ISM"),
    FrequencyPreset("868.95",  868950000L, "OpenMQTTGateway"),
    FrequencyPreset("433.075", 433075000L, "Счётчики"),
    FrequencyPreset("864.00",  864000000L, "TPMS датчики"),
    FrequencyPreset("345.00",  345000000L, "Garage US"),
)

data class SubFile(
    val fsFile: FsFile,
    val path: String,
    val frequency: Long = 0L,
    val preset: String = "",
    val protocol: String = "",
)

private fun parseSubHeader(content: String): Triple<Long, String, String> {
    var freq = 0L; var preset = ""; var protocol = ""
    content.lines().take(20).forEach { line ->
        when {
            line.startsWith("Frequency:") ->
                freq = line.substringAfter(":").trim().toLongOrNull() ?: 0L
            line.startsWith("Preset:") ->
                preset = line.substringAfter(":").trim()
                    .removePrefix("FuriHalSubGhzPreset")
                    .removeSuffix("Async")
            line.startsWith("Protocol:") ->
                protocol = line.substringAfter(":").trim()
        }
    }
    return Triple(freq, preset, protocol)
}

@Composable
fun SubGhzScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<SubFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<SubFile?>(null) }
    var statusText by remember { mutableStateOf("") }

    fun loadFiles() {
        scope.launch {
            isLoading = true
            statusText = "Загрузка /ext/subghz/..."
            try {
                val dir = session.listStorage("/ext/subghz")
                val subFiles = dir.filter { !it.isDir && it.name.endsWith(".sub") }
                val parsed = subFiles.map { f ->
                    val path = "/ext/subghz/${f.name}"
                    try {
                        val content = String(session.readFile(path), Charsets.UTF_8)
                        val (freq, preset, proto) = parseSubHeader(content)
                        SubFile(f, path, freq, preset, proto)
                    } catch (_: Exception) {
                        SubFile(f, path)
                    }
                }
                files = parsed
                statusText = "${files.size} файлов"
            } catch (e: Exception) {
                statusText = "Ошибка: ${e.message}"
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { loadFiles() }

    Column(
        Modifier
            .fillMaxSize()
            .background(FlipperTheme.bg)
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("← ", color = FlipperTheme.accent, fontSize = 20.sp,
                fontFamily = FlipperTheme.mono,
                modifier = Modifier.clickable { onBack() })
            Text("SUB-GHz", color = FlipperTheme.green, fontSize = 20.sp,
                fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            if (isLoading) CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = FlipperTheme.green, strokeWidth = 2.dp
            )
        }

        // Tabs
        Row(
            Modifier.fillMaxWidth()
                .background(FlipperTheme.surface, RoundedCornerShape(10.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("ФАЙЛЫ SD", "УПРАВЛЕНИЕ").forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).clickable { tab = i }
                        .background(
                            if (i == tab) FlipperTheme.greenDim else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            if (i == tab) 1.dp else 0.dp,
                            FlipperTheme.green.copy(alpha = 0.4f), RoundedCornerShape(8.dp)
                        )
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label,
                        color = if (i == tab) FlipperTheme.green else FlipperTheme.textSecondary,
                        fontSize = 11.sp, fontFamily = FlipperTheme.mono,
                        fontWeight = if (i == tab) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        when (tab) {
            0 -> SubGhzFilesTab(
                files = files,
                selected = selectedFile,
                onSelect = { selectedFile = it },
                onRefresh = { loadFiles() },
                onReplay = { file ->
                    scope.launch {
                        statusText = "Replay: ${file.fsFile.name}..."
                        val ok = session.appStart("subghz", file.path)
                        statusText = if (ok) "Запущено на Flipper" else "Ошибка запуска"
                    }
                }
            )
            1 -> SubGhzControlTab(
                onStartReceiver = {
                    scope.launch {
                        statusText = "Открываю приёмник Sub-GHz..."
                        val ok = session.appStart("subghz")
                        statusText = if (ok) "Sub-GHz приёмник открыт" else "Ошибка"
                    }
                }
            )
        }

        Spacer(Modifier.weight(1f))
        if (statusText.isNotEmpty()) {
            Text(statusText, color = FlipperTheme.textSecondary,
                fontSize = 11.sp, fontFamily = FlipperTheme.mono)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun SubGhzFilesTab(
    files: List<SubFile>,
    selected: SubFile?,
    onSelect: (SubFile) -> Unit,
    onRefresh: () -> Unit,
    onReplay: (SubFile) -> Unit
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionButton("↺ ОБНОВИТЬ", FlipperTheme.textSecondary, modifier = Modifier.weight(1f)) {
                onRefresh()
            }
            ActionButton(
                "▶ REPLAY",
                FlipperTheme.green,
                enabled = selected != null,
                modifier = Modifier.weight(1f)
            ) { selected?.let { onReplay(it) } }
        }

        Spacer(Modifier.height(12.dp))

        if (files.isEmpty()) {
            EmptyState("Нет .sub файлов на SD карте.\nЗапиши сигналы через Sub-GHz приёмник.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(files) { file ->
                    val isSelected = selected?.fsFile?.name == file.fsFile.name
                    SubFileRow(file = file, isSelected = isSelected, onClick = { onSelect(file) })
                }
            }
        }
    }
}

@Composable
fun SubFileRow(file: SubFile, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                if (isSelected) 1.dp else 0.5.dp,
                if (isSelected) FlipperTheme.green else FlipperTheme.border,
                RoundedCornerShape(10.dp)
            )
            .background(
                if (isSelected) FlipperTheme.greenDim else FlipperTheme.surface,
                RoundedCornerShape(10.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📡", fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.fsFile.name.removeSuffix(".sub"),
                color = if (isSelected) FlipperTheme.green else FlipperTheme.textPrimary,
                fontSize = 13.sp, fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold
            )
            val info = buildString {
                if (file.frequency > 0) append("${file.frequency / 1_000_000.0} MHz")
                if (file.protocol.isNotEmpty()) append(" · ${file.protocol}")
                if (file.preset.isNotEmpty()) append(" · ${file.preset}")
            }.ifEmpty { "${file.fsFile.size} bytes" }
            Text(info, color = FlipperTheme.textSecondary, fontSize = 10.sp, fontFamily = FlipperTheme.mono)
        }
        if (isSelected) Text("✓", color = FlipperTheme.green, fontSize = 14.sp)
    }
}

@Composable
fun SubGhzControlTab(onStartReceiver: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ПРЕДУСТАНОВЛЕННЫЕ ЧАСТОТЫ",
            color = FlipperTheme.textSecondary, fontSize = 10.sp,
            fontFamily = FlipperTheme.mono, letterSpacing = 2.sp)

        val scroll = rememberScrollState()
        Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            frequencyPresets.forEach { preset ->
                Box(
                    Modifier
                        .border(1.dp, FlipperTheme.border, RoundedCornerShape(8.dp))
                        .background(FlipperTheme.surface, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${preset.label} MHz",
                            color = FlipperTheme.textPrimary, fontSize = 13.sp,
                            fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold)
                        Text(preset.description,
                            color = FlipperTheme.textSecondary, fontSize = 9.sp,
                            fontFamily = FlipperTheme.mono)
                    }
                }
            }
        }

        HorizontalDivider(color = FlipperTheme.border)

        Text("УПРАВЛЕНИЕ",
            color = FlipperTheme.textSecondary, fontSize = 10.sp,
            fontFamily = FlipperTheme.mono, letterSpacing = 2.sp)

        ActionButton(
            label = "📡 ОТКРЫТЬ SUB-GHz ПРИЁМНИК",
            color = FlipperTheme.green,
            modifier = Modifier.fillMaxWidth(),
            onClick = onStartReceiver
        )

        Box(
            Modifier.fillMaxWidth()
                .background(FlipperTheme.surface, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Text(
                "Открывает Sub-GHz приложение на Flipper.\n" +
                "Управление записью и воспроизведением\n" +
                "выполняется через интерфейс Flipper Zero.",
                color = FlipperTheme.textSecondary, fontSize = 11.sp,
                fontFamily = FlipperTheme.mono, lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun ActionButton(
    label: String,
    color: Color,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = color,
            fontSize = 13.sp,
            fontFamily = FlipperTheme.mono,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun AnimatedReceivingBadge() {
    val inf = rememberInfiniteTransition(label = "rx")
    val alpha by inf.animateFloat(
        0.4f, 1f,
        infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "a"
    )
    Box(
        Modifier
            .alpha(alpha)
            .background(FlipperTheme.greenDim, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text("● RX", color = FlipperTheme.green, fontSize = 11.sp,
            fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold)
    }
}
