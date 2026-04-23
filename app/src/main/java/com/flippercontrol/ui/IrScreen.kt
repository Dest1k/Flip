package com.flippercontrol.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.flippercontrol.core.FsFile
import com.flippercontrol.core.FlipperRpcSession
import kotlinx.coroutines.launch

data class IrFileInfo(
    val fsFile: FsFile,
    val path: String,
    val signals: List<String> = emptyList(),
)

private fun parseIrSignals(content: String): List<String> {
    val names = mutableListOf<String>()
    content.lines().forEach { line ->
        if (line.startsWith("name:")) {
            val name = line.substringAfter(":").trim()
            if (name.isNotEmpty()) names.add(name)
        }
    }
    return names
}

@Composable
fun IrScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<IrFileInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<IrFileInfo?>(null) }
    var statusText by remember { mutableStateOf("") }
    var log by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    val addLog = { text: String, level: LogLevel -> log = buildLog(log, text, level) }

    fun loadFiles() {
        scope.launch {
            isLoading = true
            statusText = "Загрузка /ext/infrared/..."
            addLog("Загрузка /ext/infrared/...", LogLevel.INFO)
            try {
                val dir = session.listStorage("/ext/infrared")
                val irFiles = dir.filter { !it.isDir && it.name.endsWith(".ir") }
                val parsed = irFiles.map { f ->
                    val path = "/ext/infrared/${f.name}"
                    try {
                        val content = String(session.readFile(path), Charsets.UTF_8)
                        val signals = parseIrSignals(content)
                        IrFileInfo(f, path, signals)
                    } catch (_: Exception) {
                        IrFileInfo(f, path)
                    }
                }
                files = parsed
                selectedFile = null
                statusText = "${files.size} файлов"
                addLog("Найдено: ${files.size} .ir файлов", LogLevel.OK)
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
        TopBar(title = "INFRARED", color = FlipperTheme.red, onBack = onBack)

        // File list header
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ФАЙЛЫ /ext/infrared/",
                color = FlipperTheme.textSecondary, fontSize = 10.sp,
                fontFamily = FlipperTheme.mono, letterSpacing = 2.sp)
            if (isLoading) CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = FlipperTheme.red, strokeWidth = 2.dp
            )
        }
        Spacer(Modifier.height(8.dp))

        if (files.isEmpty() && !isLoading) {
            EmptyState("Нет .ir файлов на SD карте.\nЗапиши сигналы через Infrared приложение.")
        } else {
            // File list
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(files) { file ->
                    val isSelected = selectedFile?.fsFile?.name == file.fsFile.name
                    IrFileRow(
                        file = file,
                        isSelected = isSelected,
                        onClick = { selectedFile = if (isSelected) null else file }
                    )
                }
            }
        }

        // Signal list for selected file
        selectedFile?.let { file ->
            if (file.signals.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("СИГНАЛЫ: ${file.fsFile.name.removeSuffix(".ir")}",
                    color = FlipperTheme.textSecondary, fontSize = 10.sp,
                    fontFamily = FlipperTheme.mono, letterSpacing = 2.sp)
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier.height(140.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(file.signals) { signal ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(FlipperTheme.surface, RoundedCornerShape(8.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🔴", fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                            Text(signal, color = FlipperTheme.textPrimary,
                                fontSize = 13.sp, fontFamily = FlipperTheme.mono,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = FlipperTheme.border)
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("↺ ОБНОВИТЬ", FlipperTheme.textSecondary, modifier = Modifier.weight(1f)) {
                loadFiles()
            }
            ActionButton(
                label = "▶ ОТКРЫТЬ",
                color = FlipperTheme.red,
                enabled = selectedFile != null,
                modifier = Modifier.weight(1f)
            ) {
                scope.launch {
                    selectedFile?.let { file ->
                        statusText = "Открываю: ${file.fsFile.name}..."
                        addLog("Открываю: ${file.fsFile.name}", LogLevel.INFO)
                        try {
                            val ok = session.appStart("infrared", file.path)
                            statusText = if (ok) "Infrared открыт на Flipper" else "Ошибка запуска"
                            addLog(if (ok) "Infrared открыт ✓" else "Ошибка запуска", if (ok) LogLevel.OK else LogLevel.ERROR)
                        } catch (e: Exception) {
                            statusText = "Ошибка: ${e.message}"
                            addLog("Ошибка: ${e.message}", LogLevel.ERROR)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        ActionButton(
            label = "📹 ЗАПИСЬ СИГНАЛОВ",
            color = FlipperTheme.accent,
            modifier = Modifier.fillMaxWidth()
        ) {
            scope.launch {
                addLog("Открываю Infrared для записи...", LogLevel.INFO)
                statusText = "Запуск..."
                try {
                    val ok = session.appStart("infrared")
                    statusText = if (ok) "Infrared открыт на Flipper" else "Ошибка запуска"
                    addLog(if (ok) "Infrared открыт ✓" else "Ошибка запуска", if (ok) LogLevel.OK else LogLevel.ERROR)
                } catch (e: Exception) {
                    statusText = "Ошибка: ${e.message}"
                    addLog("Ошибка: ${e.message}", LogLevel.ERROR)
                }
            }
        }

        if (statusText.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(statusText, color = FlipperTheme.textSecondary,
                fontSize = 11.sp, fontFamily = FlipperTheme.mono)
        }

        Spacer(Modifier.height(6.dp))
        ActivityLogPanel(log, Modifier.fillMaxWidth())
    }
}

@Composable
fun IrFileRow(file: IrFileInfo, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                if (isSelected) 1.dp else 0.5.dp,
                if (isSelected) FlipperTheme.red else FlipperTheme.border,
                RoundedCornerShape(10.dp)
            )
            .background(
                if (isSelected) FlipperTheme.redDim else FlipperTheme.surface,
                RoundedCornerShape(10.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🔴", fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.fsFile.name.removeSuffix(".ir"),
                color = if (isSelected) FlipperTheme.red else FlipperTheme.textPrimary,
                fontSize = 13.sp, fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold
            )
            val info = when {
                file.signals.isNotEmpty() -> "${file.signals.size} сигналов: ${file.signals.take(3).joinToString(", ")}"
                file.fsFile.size > 0 -> "${file.fsFile.size} bytes"
                else -> "Нажми для просмотра"
            }
            Text(info, color = FlipperTheme.textSecondary, fontSize = 10.sp,
                fontFamily = FlipperTheme.mono)
        }
        Text(if (isSelected) "▼" else "›",
            color = FlipperTheme.textSecondary, fontSize = 16.sp)
    }
}
