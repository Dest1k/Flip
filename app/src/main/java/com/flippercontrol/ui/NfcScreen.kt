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

data class NfcFileInfo(
    val fsFile: FsFile,
    val path: String,
    val deviceType: String = "",
    val uid: String = "",
    val atqa: String = "",
    val sak: String = "",
)

private fun parseNfcHeader(content: String): Triple<String, String, String> {
    var deviceType = ""; var uid = ""; var atqa = ""; var sak = ""
    content.lines().take(15).forEach { line ->
        when {
            line.startsWith("Device type:") -> deviceType = line.substringAfter(":").trim()
            line.startsWith("UID:") -> uid = line.substringAfter(":").trim()
            line.startsWith("ATQA:") -> atqa = line.substringAfter(":").trim()
            line.startsWith("SAK:") -> sak = line.substringAfter(":").trim()
        }
    }
    return Triple(deviceType, uid, atqa)
}

@Composable
fun NfcScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<NfcFileInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<NfcFileInfo?>(null) }
    var statusText by remember { mutableStateOf("") }

    fun loadFiles() {
        scope.launch {
            isLoading = true
            statusText = "Загрузка /ext/nfc/..."
            try {
                val dir = session.listStorage("/ext/nfc")
                val nfcFiles = dir.filter { !it.isDir && it.name.endsWith(".nfc") }
                val parsed = nfcFiles.map { f ->
                    val path = "/ext/nfc/${f.name}"
                    try {
                        val content = String(session.readFile(path), Charsets.UTF_8)
                        val (deviceType, uid, atqa) = parseNfcHeader(content)
                        NfcFileInfo(f, path, deviceType, uid, atqa)
                    } catch (_: Exception) {
                        NfcFileInfo(f, path)
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
        TopBar(title = "NFC", color = FlipperTheme.blue, onBack = onBack)

        NfcTabBar(selected = tab, onSelect = { tab = it })

        Spacer(Modifier.height(16.dp))

        when (tab) {
            0 -> NfcReadTab(
                onStartRead = {
                    scope.launch {
                        statusText = "Запуск NFC считывателя..."
                        val ok = session.appStart("nfc")
                        statusText = if (ok) "NFC открыт на Flipper" else "Ошибка запуска"
                    }
                }
            )
            1 -> NfcFilesTab(
                files = files,
                isLoading = isLoading,
                selected = selectedFile,
                onSelect = { selectedFile = it },
                onRefresh = { loadFiles() },
                onEmulate = { file ->
                    scope.launch {
                        statusText = "Эмуляция: ${file.fsFile.name}..."
                        val ok = session.appStart("nfc", file.path)
                        statusText = if (ok) "NFC эмуляция запущена" else "Ошибка"
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
fun NfcTabBar(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(FlipperTheme.surface, RoundedCornerShape(10.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf("СЧИТАТЬ", "ФАЙЛЫ / ЭМУЛЯЦИЯ").forEachIndexed { i, label ->
            Box(
                Modifier.weight(1f).clickable { onSelect(i) }
                    .background(
                        if (i == selected) FlipperTheme.blueDim else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        if (i == selected) 1.dp else 0.dp,
                        FlipperTheme.blue.copy(alpha = 0.4f), RoundedCornerShape(8.dp)
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label,
                    color = if (i == selected) FlipperTheme.blue else FlipperTheme.textSecondary,
                    fontSize = 11.sp, fontFamily = FlipperTheme.mono,
                    fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun NfcReadTab(onStartRead: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        NfcAnimation(active = false)
        Spacer(Modifier.height(24.dp))
        ActionButton(
            label = "📡 ОТКРЫТЬ NFC СЧИТЫВАТЕЛЬ",
            color = FlipperTheme.blue,
            modifier = Modifier.fillMaxWidth(),
            onClick = onStartRead
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth()
                .background(FlipperTheme.surface, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Text(
                "Открывает NFC приложение на Flipper.\n" +
                "Поднеси карту к Flipper для считывания.\n" +
                "Поддерживаются: Mifare Classic/UL · NTAG · ISO 14443-A",
                color = FlipperTheme.textSecondary, fontSize = 11.sp,
                fontFamily = FlipperTheme.mono, lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun NfcFilesTab(
    files: List<NfcFileInfo>,
    isLoading: Boolean,
    selected: NfcFileInfo?,
    onSelect: (NfcFileInfo) -> Unit,
    onRefresh: () -> Unit,
    onEmulate: (NfcFileInfo) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("↺ ОБНОВИТЬ", FlipperTheme.textSecondary, modifier = Modifier.weight(1f)) {
                onRefresh()
            }
            ActionButton(
                "▶ ЭМУЛИРОВАТЬ",
                FlipperTheme.blue,
                enabled = selected != null,
                modifier = Modifier.weight(1f)
            ) { selected?.let { onEmulate(it) } }
        }

        Spacer(Modifier.height(12.dp))

        if (isLoading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FlipperTheme.blue, modifier = Modifier.size(24.dp))
            }
        } else if (files.isEmpty()) {
            EmptyState("Нет .nfc файлов на SD карте.\nСчитай карту через NFC считыватель.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(files) { file ->
                    val isSelected = selected?.fsFile?.name == file.fsFile.name
                    NfcFileRow(file = file, isSelected = isSelected, onClick = { onSelect(file) })
                }
            }
        }
    }
}

@Composable
fun NfcFileRow(file: NfcFileInfo, isSelected: Boolean, onClick: () -> Unit) {
    val typeColor = when {
        file.deviceType.contains("Classic") -> FlipperTheme.blue
        file.deviceType.contains("Ultralight") -> FlipperTheme.green
        file.deviceType.contains("NTAG") -> FlipperTheme.purple
        else -> FlipperTheme.textSecondary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                if (isSelected) 1.dp else 0.5.dp,
                if (isSelected) FlipperTheme.blue else FlipperTheme.border,
                RoundedCornerShape(10.dp)
            )
            .background(
                if (isSelected) FlipperTheme.blueDim else FlipperTheme.surface,
                RoundedCornerShape(10.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("💳", fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.fsFile.name.removeSuffix(".nfc"),
                color = if (isSelected) FlipperTheme.blue else FlipperTheme.textPrimary,
                fontSize = 13.sp, fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold
            )
            val info = buildString {
                if (file.deviceType.isNotEmpty()) append(file.deviceType)
                if (file.uid.isNotEmpty()) append(" · UID: ${file.uid}")
            }.ifEmpty { "${file.fsFile.size} bytes" }
            Text(info, color = typeColor, fontSize = 10.sp, fontFamily = FlipperTheme.mono)
        }
        if (isSelected) Text("✓", color = FlipperTheme.blue, fontSize = 14.sp)
    }
}

@Composable
fun NfcAnimation(active: Boolean) {
    val inf = rememberInfiniteTransition(label = "nfc")
    val scale1 by inf.animateFloat(1f, 1.4f,
        infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "s1")
    val scale2 by inf.animateFloat(1f, 1.7f,
        infiniteRepeatable(tween(1000, delayMillis = 200), RepeatMode.Reverse), label = "s2")
    val alpha1 by inf.animateFloat(0.6f, 0f,
        infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "a1")

    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Box(Modifier.size((60 * scale2).dp).alpha(alpha1)
                .background(FlipperTheme.blueDim, RoundedCornerShape(50)))
            Box(Modifier.size((60 * scale1).dp).alpha(0.3f)
                .background(FlipperTheme.blueDim, RoundedCornerShape(50)))
        }
        Box(
            Modifier.size(60.dp)
                .border(2.dp, if (active) FlipperTheme.blue else FlipperTheme.border, RoundedCornerShape(50))
                .background(FlipperTheme.surface, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Text("💳", fontSize = 28.sp)
        }
    }
}
