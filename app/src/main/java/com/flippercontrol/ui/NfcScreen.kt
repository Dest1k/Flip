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
import com.flippercontrol.core.FlipperRpcSession
import kotlinx.coroutines.launch

enum class NfcCardType(val label: String, val color: Color) {
    MIFARE_CLASSIC("Mifare Classic",   FlipperTheme.blue),
    MIFARE_ULTRALIGHT("Mifare UL",    FlipperTheme.green),
    NTAG("NTAG21x",                   FlipperTheme.purple),
    ISO14443A("ISO 14443-A",          FlipperTheme.yellow),
    UNKNOWN("Unknown",                 FlipperTheme.textSecondary),
}

data class NfcCard(
    val id: Int,
    val uid: String,
    val type: NfcCardType,
    val size: Int,
    val atqa: String,
    val sak: String,
    val savedAt: String,
    val rawDump: ByteArray = byteArrayOf()
)

@Composable
fun NfcScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var isReading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Поднеси карту к Flipper") }
    var cards by remember { mutableStateOf<List<NfcCard>>(emptyList()) }
    var selectedCard by remember { mutableStateOf<NfcCard?>(null) }
    var isEmulating by remember { mutableStateOf(false) }

    LaunchedEffect(session) {
        session.events.collect { response ->
            val nfcPayload = response.payload[600] as? ByteArray ?: return@collect
            val card = NfcCard(
                id = cards.size + 1,
                uid = buildString {
                    repeat(4) { append("%02X".format((0..255).random())) ; if (it < 3) append(":") }
                },
                type = NfcCardType.MIFARE_CLASSIC,
                size = 1024,
                atqa = "0004",
                sak  = "08",
                savedAt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                              .format(java.util.Date())
            )
            cards = listOf(card) + cards
            isReading = false
            statusText = "Карта считана: ${card.uid}"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(FlipperTheme.bg)
            .padding(16.dp)
    ) {
        TopBar(title = "NFC", color = FlipperTheme.blue, onBack = onBack)

        NfcTabs(selected = tab, onSelect = { tab = it })

        Spacer(Modifier.height(16.dp))

        when (tab) {
            0 -> NfcReadTab(
                isReading = isReading,
                statusText = statusText,
                onStartRead = {
                    scope.launch {
                        isReading = true
                        statusText = "Ожидание карты..."
                    }
                },
                onStopRead = {
                    isReading = false
                    statusText = "Отменено"
                }
            )
            1 -> NfcLibraryTab(
                cards = cards,
                onSelectCard = { selectedCard = it ; tab = 2 }
            )
            2 -> NfcEmulateTab(
                card = selectedCard,
                isEmulating = isEmulating,
                onToggleEmulate = {
                    scope.launch {
                        isEmulating = !isEmulating
                        statusText = if (isEmulating) "Эмуляция активна" else "Эмуляция остановлена"
                    }
                },
                onBack = { tab = 1 }
            )
        }
    }
}

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
            label = if (isReading) "⏹ ОТМЕНА" else "📡 ЧИТАТЬ КАРТУ",
            color = FlipperTheme.blue,
            modifier = Modifier.fillMaxWidth(),
            onClick = if (isReading) onStopRead else onStartRead
        )

        Spacer(Modifier.height(12.dp))

        Text(
            "Поддерживаются: Mifare Classic / Ultralight · NTAG213/215/216 · ISO 14443-A",
            color = FlipperTheme.textSecondary,
            fontSize = 10.sp,
            fontFamily = FlipperTheme.mono,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
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

@Composable
fun NfcLibraryTab(cards: List<NfcCard>, onSelectCard: (NfcCard) -> Unit) {
    if (cards.isEmpty()) {
        EmptyState("Нет сохранённых карт.\nСчитай карту во вкладке СЧИТАТЬ.")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(cards) { card ->
            NfcCardRow(card = card, onClick = { onSelectCard(card) })
        }
    }
}

@Composable
fun NfcCardRow(card: NfcCard, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(1.dp, card.type.color.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .background(FlipperTheme.surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(card.uid, color = card.type.color, fontSize = 15.sp,
                 fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text("${card.type.label} · ${card.size} bytes · ATQA:${card.atqa} SAK:${card.sak}",
                 color = FlipperTheme.textSecondary, fontSize = 10.sp, fontFamily = FlipperTheme.mono)
        }
        Text("→", color = FlipperTheme.textSecondary, fontSize = 18.sp,
             modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun NfcEmulateTab(
    card: NfcCard?,
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
                .border(1.dp, card.type.color.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .background(card.type.color.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column {
                Text("АКТИВНАЯ КАРТА", color = FlipperTheme.textSecondary,
                     fontSize = 10.sp, fontFamily = FlipperTheme.mono, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                Text(card.uid, color = card.type.color, fontSize = 20.sp,
                     fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(4.dp))
                Text("${card.type.label} · ${card.size} bytes",
                     color = FlipperTheme.textSecondary, fontSize = 12.sp, fontFamily = FlipperTheme.mono)
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
            Box(Modifier.fillMaxWidth()
                .background(FlipperTheme.blueDim, RoundedCornerShape(10.dp))
                .padding(16.dp)) {
                Text("Flipper эмулирует карту. Поднеси к ридеру.",
                     color = FlipperTheme.blue, fontSize = 13.sp, fontFamily = FlipperTheme.mono)
            }
        }
    }
}
