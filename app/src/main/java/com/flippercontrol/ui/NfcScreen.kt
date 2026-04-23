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

// ─── NFC card info parsed from .nfc file ──────────────────────────────────────

data class NfcCardFile(
    val fsFile: FsFile,
    val path: String,
    val uid: String = "",
    val deviceType: String = "",
    val atqa: String = "",
    val sak: String = "",
)

private fun parseNfcFile(content: String): Triple<String, String, Pair<String, String>> {
    var uid = ""; var deviceType = ""; var atqa = ""; var sak = ""
    content.lines().take(20).forEach { line ->
        when {
            line.startsWith("UID:")         -> uid        = line.substringAfter(":").trim()
            line.startsWith("Device type:") -> deviceType = line.substringAfter(":").trim()
            line.startsWith("ATQA:")        -> atqa       = line.substringAfter(":").trim()
            line.startsWith("SAK:")         -> sak        = line.substringAfter(":").trim()
        }
    }
    return Triple(uid, deviceType, Pair(atqa, sak))
}

// ─── NFC Screen ───────────────────────────────────────────────────────────────

@Composable
fun NfcScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var isReading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Нажми ЧИТАТЬ, затем поднеси карту к Flipper") }
    var cards by remember { mutableStateOf<List<NfcCardFile>>(emptyList()) }
    var isLoadingLibrary by remember { mutableStateOf(false) }
    var selectedCard by remember { mutableStateOf<NfcCardFile?>(null) }
    var isEmulating by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    val addLog = { text: String, level: LogLevel -> log = buildLog(log, text, level) }

    fun loadLibrary() {
        scope.launch {
            isLoadingLibrary = true
            addLog("Загрузка /ext/nfc/...", LogLevel.INFO)
            try {
                val dir = session.listStorage("/ext/nfc")
                val nfcFiles = dir.filter { !it.isDir && it.name.endsWith(".nfc") }
                val parsed = nfcFiles.map { f ->
                    val path = "/ext/nfc/${f.name}"
                    try {
                        val content = String(session.readFile(path), Charsets.UTF_8)
                        val (uid, deviceType, atqaSak) = parseNfcFile(content)
                        NfcCardFile(f, path, uid, deviceType, atqaSak.first, atqaSak.second)
                    } catch (_: Exception) {
                        NfcCardFile(f, path)
                    }
                }
                cards = parsed
                addLog("Найдено карт: ${cards.size}", LogLevel.OK)
            } catch (e: Exception) {
                statusText = "Ошибка загрузки библиотеки: ${e.message}"
                addLog("Ошибка: ${e.message}", LogLevel.ERROR)
            }
            isLoadingLibrary = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(FlipperTheme.bg)
            .padding(16.dp)
    ) {
        TopBar(title = "NFC", color = FlipperTheme.blue, onBack = onBack)

        // Tabs
        NfcTabs(selected = tab, onSelect = { newTab ->
            tab = newTab
            if (newTab == 1 && cards.isEmpty()) loadLibrary()
        })

        Spacer(Modifier.height(16.dp))

        when (tab) {
            0 -> NfcReadTab(

                isReading = isReading,
                statusText = statusText,
                onStartRead = {
                    scope.launch {
                        isReading = true
                        statusText = "Открываю NFC на Flipper..."
                        try {
                            val ok = session.appStart("nfc")
                            statusText = if (ok)
                                "NFC приложение открыто. Поднеси карту к Flipper."
                            else "Ошибка запуска NFC"
                            addLog(if (ok) "NFC запущен ✓" else "Ошибка запуска NFC", if (ok) LogLevel.OK else LogLevel.ERROR)
                            if (!ok) isReading = false
                        } catch (e: Exception) {
                            statusText = "Ошибка: ${e.message}"
                            addLog("Ошибка: ${e.message}", LogLevel.ERROR)
                            isReading = false
                        }
                    }
                },
                onStopRead = {
                    scope.launch {
                        isReading = false
                        statusText = "Остановлено"
                        try { session.appExit() } catch (_: Exception) {}
                    }
                }
            )
            1 -> NfcLibraryTab(
                cards = cards,
                isLoading = isLoadingLibrary,
                onRefresh = { loadLibrary() },
                onSelectCard = { card ->
                    selectedCard = card
                    tab = 2
                }
            )
            2 -> NfcEmulateTab(
                card = selectedCard,
                isEmulating = isEmulating,
                onToggleEmulate = {
                    scope.launch {
                        if (!isEmulating) {
                            val card = selectedCard ?: return@launch
                            addLog("Эмуляция: ${card.fsFile.name}", LogLevel.INFO)
                            statusText = "Запуск эмуляции..."
                            try {
                                val ok = session.appStart("nfc", card.path)
                                isEmulating = ok
                                statusText = if (ok) "NFC эмуляция запущена" else "Ошибка запуска"
                                addLog(if (ok) "Эмуляция запущена ✓" else "Ошибка запуска", if (ok) LogLevel.OK else LogLevel.ERROR)
                            } catch (e: Exception) {
                                statusText = "Ошибка: ${e.message}"
                                addLog("Ошибка: ${e.message}", LogLevel.ERROR)
                            }
                        } else {
                            isEmulating = false
                            statusText = "Эмуляция остановлена"
                            try { session.appExit() } catch (_: Exception) {}
                            addLog("Эмуляция остановлена", LogLevel.INFO)
                        }
                    }
                },
                onBack = { tab = 1 }
            )
        }

        // Activity log
        Spacer(Modifier.height(8.dp))
        ActivityLogPanel(log, Modifier.fillMaxWidth())
    }
}

// ─── Tabs ─────────────────────────────────────────────────────────────────────

@Composable
fun NfcTabs(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf("СЧИТАТЬ", "БИБЛИОТЕКА", "ЭМУЛЯТОР")
    Row(
        Modifier
            .fillMaxWidth()
            .background(FlipperTheme.surface, RoundedCornerShape(10.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tabs.forEachIndexed { i, label ->
            Box(
                Modifier
                    .weight(1f)
                    .clickable { onSelect(i) }
                    .background(
                        if (i == selected) FlipperTheme.blueDim else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        if (i == selected) 1.dp else 0.dp,
                        FlipperTheme.blue.copy(alpha = 0.4f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (i == selected) FlipperTheme.blue else FlipperTheme.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FlipperTheme.mono,
                    fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

// ─── Read tab ─────────────────────────────────────────────────────────────────

@Composable
fun NfcReadTab(
    isReading: Boolean,
    statusText: String,
    onStartRead: () -> Unit,
    onStopRead: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        NfcAnimation(active = isReading)

        Spacer(Modifier.height(24.dp))

        Text(
            statusText,
            color = if (isReading) FlipperTheme.blue else FlipperTheme.textSecondary,
            fontSize = 13.sp,
            fontFamily = FlipperTheme.mono
        )

        Spacer(Modifier.height(20.dp))

        ActionButton(
            label = if (isReading) "⏹ ЗАКРЫТЬ NFC" else "📡 ОТКРЫТЬ NFC",
            color = FlipperTheme.blue,
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
                "Открывает NFC приложение на Flipper Zero.\n" +
                "Считанные карты сохраняются на SD → /ext/nfc/\n" +
                "Загрузи их через вкладку БИБЛИОТЕКА.",
                color = FlipperTheme.textSecondary,
                fontSize = 10.sp,
                fontFamily = FlipperTheme.mono,
                lineHeight = 16.sp
            )
        }
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

// ─── Library tab ──────────────────────────────────────────────────────────────

@Composable
fun NfcLibraryTab(
    cards: List<NfcCardFile>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onSelectCard: (NfcCardFile) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("↺ ОБНОВИТЬ", FlipperTheme.textSecondary, modifier = Modifier.fillMaxWidth()) {
                onRefresh()
            }
        }
        Spacer(Modifier.height(12.dp))

        if (isLoading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FlipperTheme.blue, modifier = Modifier.size(24.dp))
            }
        } else if (cards.isEmpty()) {
            EmptyState("Нет .nfc файлов на SD карте.\nСчитай карту через вкладку СЧИТАТЬ.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cards) { card ->
                    NfcCardRow(card = card, onClick = { onSelectCard(card) })
                }
            }
        }
    }
}

@Composable
fun NfcCardRow(card: NfcCardFile, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(1.dp, FlipperTheme.blue.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .background(FlipperTheme.surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                card.fsFile.name.removeSuffix(".nfc"),
                color = FlipperTheme.blue, fontSize = 14.sp,
                fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(3.dp))
            val info = buildString {
                if (card.uid.isNotEmpty()) append("UID: ${card.uid}")
                if (card.deviceType.isNotEmpty()) append(" · ${card.deviceType}")
                if (card.atqa.isNotEmpty() || card.sak.isNotEmpty()) {
                    append("\nATQA:${card.atqa} SAK:${card.sak}")
                }
            }.ifEmpty { "${card.fsFile.size} bytes" }
            Text(info, color = FlipperTheme.textSecondary, fontSize = 10.sp,
                fontFamily = FlipperTheme.mono, lineHeight = 14.sp)
        }
        Text("→", color = FlipperTheme.textSecondary, fontSize = 18.sp,
            modifier = Modifier.padding(start = 8.dp))
    }
}

// ─── Emulate tab ──────────────────────────────────────────────────────────────

@Composable
fun NfcEmulateTab(
    card: NfcCardFile?,
    isEmulating: Boolean,
    onToggleEmulate: () -> Unit,
    onBack: () -> Unit
) {
    if (card == null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState("Карта не выбрана.\nВыбери карту в БИБЛИОТЕКЕ.")
            Spacer(Modifier.height(12.dp))
            ActionButton("← БИБЛИОТЕКА", FlipperTheme.blue, modifier = Modifier.fillMaxWidth()) { onBack() }
        }
        return
    }

    Column {
        Box(
            Modifier.fillMaxWidth()
                .border(1.dp, FlipperTheme.blue.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .background(FlipperTheme.blue.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column {
                Text("АКТИВНАЯ КАРТА", color = FlipperTheme.textSecondary,
                    fontSize = 10.sp, fontFamily = FlipperTheme.mono, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                Text(card.fsFile.name.removeSuffix(".nfc"),
                    color = FlipperTheme.blue, fontSize = 18.sp,
                    fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Black)
                if (card.uid.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("UID: ${card.uid}", color = FlipperTheme.textSecondary,
                        fontSize = 12.sp, fontFamily = FlipperTheme.mono)
                }
                if (card.deviceType.isNotEmpty()) {
                    Text(card.deviceType, color = FlipperTheme.textSecondary,
                        fontSize = 11.sp, fontFamily = FlipperTheme.mono)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        ActionButton(
            label = if (isEmulating) "⏹ СТОП ЭМУЛЯЦИЯ" else "▶ ЭМУЛИРОВАТЬ",
            color = if (isEmulating) FlipperTheme.red else FlipperTheme.blue,
            modifier = Modifier.fillMaxWidth(),
            onClick = onToggleEmulate
        )

        if (isEmulating) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth()
                    .background(FlipperTheme.blueDim, RoundedCornerShape(10.dp))
                    .padding(16.dp)
            ) {
                Text(
                    "Flipper эмулирует карту. Поднеси к ридеру.",
                    color = FlipperTheme.blue, fontSize = 13.sp, fontFamily = FlipperTheme.mono
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        ActionButton("← БИБЛИОТЕКА", FlipperTheme.textSecondary, modifier = Modifier.fillMaxWidth()) { onBack() }
    }
}
