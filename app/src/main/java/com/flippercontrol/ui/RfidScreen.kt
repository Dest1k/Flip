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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.flippercontrol.core.FsFile
import com.flippercontrol.core.FlipperRpcSession
import kotlinx.coroutines.launch

data class RfidFileInfo(
    val fsFile: FsFile,
    val path: String,
    val keyType: String = "",
    val data: String = "",
)

private fun parseRfidHeader(content: String): Pair<String, String> {
    var keyType = ""; var data = ""
    content.lines().take(10).forEach { line ->
        when {
            line.startsWith("Key type:") -> keyType = line.substringAfter(":").trim()
            line.startsWith("Data:") -> data = line.substringAfter(":").trim()
        }
    }
    return Pair(keyType, data)
}

@Composable
fun RfidScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<RfidFileInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isReading by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<RfidFileInfo?>(null) }
    var statusText by remember { mutableStateOf("") }
    var log by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    val addLog = { text: String, level: LogLevel -> log = buildLog(log, text, level) }

    fun loadFiles() {
        scope.launch {
            isLoading = true
            statusText = "Загрузка /ext/lfrfid/..."
            addLog("Загрузка /ext/lfrfid/...", LogLevel.INFO)
            try {
                val dir = session.listStorage("/ext/lfrfid")
                val rfidFiles = dir.filter { !it.isDir && it.name.endsWith(".rfid") }
                val parsed = rfidFiles.map { f ->
                    val path = "/ext/lfrfid/${f.name}"
                    try {
                        val content = String(session.readFile(path), Charsets.UTF_8)
                        val (keyType, data) = parseRfidHeader(content)
                        RfidFileInfo(f, path, keyType, data)
                    } catch (_: Exception) {
                        RfidFileInfo(f, path)
                    }
                }
                files = parsed
                statusText = "${files.size} файлов"
                addLog("Найдено: ${files.size} файлов", LogLevel.OK)
            } catch (e: Exception) {
                statusText = "Ошибка: ${e.message}"
                addLog("Ошибка: ${e.message}", LogLevel.ERROR)
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
        TopBar(title = "RFID 125kHz", color = FlipperTheme.yellow, onBack = onBack)

        Row(
            Modifier.fillMaxWidth()
                .background(FlipperTheme.surface, RoundedCornerShape(10.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("СЧИТАТЬ", "ФАЙЛЫ / ЭМУЛЯЦИЯ").forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).clickable { tab = i }
                        .background(
                            if (i == tab) FlipperTheme.yellowDim else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            if (i == tab) 1.dp else 0.dp,
                            FlipperTheme.yellow.copy(alpha = 0.4f), RoundedCornerShape(8.dp)
                        )
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label,
                        color = if (i == tab) FlipperTheme.yellow else FlipperTheme.textSecondary,
                        fontSize = 11.sp, fontFamily = FlipperTheme.mono,
                        fontWeight = if (i == tab) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        when (tab) {
            0 -> RfidReadTab(
                isReading = isReading,
                onStartRead = {
                    scope.launch {
                        statusText = "Запуск RFID считывателя..."
                        addLog("Запуск lfrfid...", LogLevel.INFO)
                        val ok = session.appStart("lfrfid")
                        isReading = ok
                        statusText = if (ok) "RFID считыватель открыт на Flipper" else "Ошибка запуска"
                        addLog(if (ok) "RFID открыт ✓ (смотри экран Flipper)" else "Ошибка запуска", if (ok) LogLevel.OK else LogLevel.ERROR)
                    }
                },
                onStopRead = {
                    scope.launch {
                        isReading = false
                        statusText = "Остановлено"
                        try { session.appExit() } catch (_: Exception) {}
                        addLog("RFID остановлен", LogLevel.INFO)
                    }
                }
            )
            1 -> RfidFilesTab(
                files = files,
                isLoading = isLoading,
                selected = selectedFile,
                onSelect = { selectedFile = it },
                onRefresh = { loadFiles() },
                onEmulate = { file ->
                    scope.launch {
                        statusText = "Эмуляция: ${file.fsFile.name}..."
                        addLog("Эмуляция: ${file.fsFile.name}", LogLevel.INFO)
                        val ok = session.appStart("lfrfid", file.path)
                        statusText = if (ok) "RFID эмуляция запущена (смотри экран Flipper)" else "Ошибка"
                        addLog(if (ok) "Эмуляция запущена ✓" else "Ошибка", if (ok) LogLevel.OK else LogLevel.ERROR)
                    }
                },
                onStopEmulate = {
                    scope.launch {
                        statusText = "Остановлено"
                        try { session.appExit() } catch (_: Exception) {}
                        addLog("Эмуляция остановлена", LogLevel.INFO)
                    }
                }
            )
        }

        Spacer(Modifier.weight(1f))
        if (statusText.isNotEmpty()) {
            Text(statusText, color = FlipperTheme.textSecondary,
                fontSize = 11.sp, fontFamily = FlipperTheme.mono)
        }
        Spacer(Modifier.height(6.dp))
        ActivityLogPanel(log, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun RfidReadTab(isReading: Boolean, onStartRead: () -> Unit, onStopRead: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RfidAnimation(active = isReading)
        Spacer(Modifier.height(20.dp))
        ActionButton(
            label = if (isReading) "⏹ ОСТАНОВИТЬ RFID" else "🔑 ОТКРЫТЬ RFID СЧИТЫВАТЕЛЬ",
            color = if (isReading) FlipperTheme.red else FlipperTheme.yellow,
            modifier = Modifier.fillMaxWidth(),
            onClick = if (isReading) onStopRead else onStartRead
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth()
                .background(FlipperTheme.surface, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Text(
                if (isReading)
                    "RFID считыватель активен на Flipper.\nПоднеси карту к Flipper для считывания."
                else
                    "Открывает 125 kHz RFID приложение на Flipper.\n" +
                    "Поднеси карту к Flipper для считывания.\n" +
                    "Поддерживаются: EM4100 · HID26/35 · Indala · Keri",
                color = if (isReading) FlipperTheme.yellow else FlipperTheme.textSecondary,
                fontSize = 11.sp, fontFamily = FlipperTheme.mono, lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun RfidFilesTab(
    files: List<RfidFileInfo>,
    isLoading: Boolean,
    selected: RfidFileInfo?,
    onSelect: (RfidFileInfo) -> Unit,
    onRefresh: () -> Unit,
    onEmulate: (RfidFileInfo) -> Unit,
    onStopEmulate: () -> Unit,
) {
    var isEmulating by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("↺ ОБНОВИТЬ", FlipperTheme.textSecondary, modifier = Modifier.weight(1f)) {
                onRefresh()
            }
            ActionButton(
                if (isEmulating) "⏹ СТОП" else "▶ ЭМУЛИРОВАТЬ",
                if (isEmulating) FlipperTheme.red else FlipperTheme.yellow,
                enabled = isEmulating || selected != null,
                modifier = Modifier.weight(1f)
            ) {
                if (isEmulating) {
                    isEmulating = false
                    onStopEmulate()
                } else {
                    selected?.let { isEmulating = true; onEmulate(it) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (isLoading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FlipperTheme.yellow, modifier = Modifier.size(24.dp))
            }
        } else if (files.isEmpty()) {
            EmptyState("Нет .rfid файлов на SD карте.\nСчитай карту через RFID считыватель.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(files) { file ->
                    val isSelected = selected?.fsFile?.name == file.fsFile.name
                    RfidFileRow(file = file, isSelected = isSelected, onClick = { onSelect(file) })
                }
            }
        }
    }
}

@Composable
fun RfidFileRow(file: RfidFileInfo, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                if (isSelected) 1.dp else 0.5.dp,
                if (isSelected) FlipperTheme.yellow else FlipperTheme.border,
                RoundedCornerShape(10.dp)
            )
            .background(
                if (isSelected) FlipperTheme.yellowDim else FlipperTheme.surface,
                RoundedCornerShape(10.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🔑", fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.fsFile.name.removeSuffix(".rfid"),
                color = if (isSelected) FlipperTheme.yellow else FlipperTheme.textPrimary,
                fontSize = 13.sp, fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold
            )
            val info = buildString {
                if (file.keyType.isNotEmpty()) append(file.keyType)
                if (file.data.isNotEmpty()) append(" · ${file.data}")
            }.ifEmpty { "${file.fsFile.size} bytes" }
            Text(info, color = FlipperTheme.textSecondary, fontSize = 10.sp, fontFamily = FlipperTheme.mono)
        }
        if (isSelected) Text("✓", color = FlipperTheme.yellow, fontSize = 14.sp)
    }
}

@Composable
fun RfidAnimation(active: Boolean) {
    val inf = rememberInfiniteTransition(label = "rfid")
    val scale by inf.animateFloat(1f, 1.5f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale")
    val alpha by inf.animateFloat(0.5f, 0f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")

    Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Box(Modifier.size((60 * scale).dp).alpha(alpha)
                .background(FlipperTheme.yellowDim, RoundedCornerShape(12.dp)))
        }
        Box(
            Modifier.size(60.dp)
                .border(2.dp, if (active) FlipperTheme.yellow else FlipperTheme.border, RoundedCornerShape(12.dp))
                .background(FlipperTheme.surface, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("🔑", fontSize = 26.sp)
        }
    }
}
