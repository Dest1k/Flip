package com.flippercontrol.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.flippercontrol.core.FlipperRpcSession
import kotlinx.coroutines.launch

data class RfidCard(
    val id: Int,
    val uid: String,
    val protocol: String,  // EM4100, HID26, HID35, Indala...
    val facilityCode: Int?,
    val cardNumber: Int?,
    val savedAt: String,
)

val rfidProtocols = listOf("EM4100", "HID26", "HID35", "Indala26", "Paradox", "Keri")

@Composable
fun RfidScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isReading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Поднеси карту 125 kHz к Flipper") }
    var cards by remember { mutableStateOf<List<RfidCard>>(emptyList()) }
    var selectedCard by remember { mutableStateOf<RfidCard?>(null) }
    var isEmulating by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(FlipperTheme.bg)
            .padding(16.dp)
    ) {
        TopBar(title = "RFID 125kHz", color = FlipperTheme.yellow, onBack = onBack)

        // Tabs
        Row(
            Modifier.fillMaxWidth().background(FlipperTheme.surface, RoundedCornerShape(10.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("СЧИТАТЬ", "БИБЛИОТЕКА", "ЭМУЛЯТОР").forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).clickable { tab = i }
                        .background(if (i == tab) FlipperTheme.yellowDim else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
                        .border(if (i == tab) 1.dp else 0.dp, FlipperTheme.yellow.copy(0.4f), RoundedCornerShape(8.dp))
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Text(label,
                        color = if (i == tab) FlipperTheme.yellow else FlipperTheme.textSecondary,
                        fontSize = 11.sp, fontFamily = FlipperTheme.mono,
                        fontWeight = if (i == tab) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        when (tab) {
            // ─── READ ───────────────────────────────────────────────────────────
            0 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // RFID animation
                RfidAnimation(active = isReading)
                Spacer(Modifier.height(20.dp))
                androidx.compose.material3.Text(statusText,
                    color = if (isReading) FlipperTheme.yellow else FlipperTheme.textSecondary,
                    fontSize = 13.sp, fontFamily = FlipperTheme.mono)
                Spacer(Modifier.height(16.dp))
                ActionButton(
                    label = if (isReading) "⏹ ОТМЕНА" else "🔑 ЧИТАТЬ RFID",
                    color = FlipperTheme.yellow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    scope.launch {
                        isReading = !isReading
                        statusText = if (isReading) "Ожидание карты 125 kHz..." else "Отменено"
                        if (isReading) {
                            kotlinx.coroutines.delay(3000) // mock
                            if (isReading) {
                                val card = RfidCard(
                                    id = cards.size + 1,
                                    uid = "%05d".format((10000..99999).random()),
                                    protocol = rfidProtocols.random(),
                                    facilityCode = (1..255).random(),
                                    cardNumber = (1..65535).random(),
                                    savedAt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                                )
                                cards = listOf(card) + cards
                                isReading = false
                                statusText = "Считано: ${card.protocol} · ${card.uid}"
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.Text(
                    "Поддерживаются: EM4100 · HID Prox 26/35 · Indala · Keri · Paradox · Gallagher",
                    color = FlipperTheme.textSecondary, fontSize = 10.sp, fontFamily = FlipperTheme.mono
                )
            }

            // ─── LIBRARY ────────────────────────────────────────────────────────
            1 -> if (cards.isEmpty()) {
                EmptyState("Нет сохранённых RFID карт.\nСчитай карту во вкладке СЧИТАТЬ.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(cards) { card ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { selectedCard = card; tab = 2 }
                                .border(1.dp, FlipperTheme.yellow.copy(0.3f), RoundedCornerShape(12.dp))
                                .background(FlipperTheme.surface, RoundedCornerShape(12.dp))
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                androidx.compose.material3.Text(
                                    card.protocol,
                                    color = FlipperTheme.yellow, fontSize = 14.sp,
                                    fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold
                                )
                                androidx.compose.material3.Text(
                                    "UID: ${card.uid}" +
                                    (card.facilityCode?.let { " · FC: $it" } ?: "") +
                                    (card.cardNumber?.let { " · CN: $it" } ?: ""),
                                    color = FlipperTheme.textSecondary, fontSize = 10.sp, fontFamily = FlipperTheme.mono
                                )
                            }
                            androidx.compose.material3.Text("→", color = FlipperTheme.textSecondary, fontSize = 18.sp)
                        }
                    }
                }
            }

            // ─── EMULATE ────────────────────────────────────────────────────────
            2 -> if (selectedCard == null) {
                Column {
                    EmptyState("Карта не выбрана.\nВыбери в БИБЛИОТЕКЕ.")
                    Spacer(Modifier.height(12.dp))
                    ActionButton("← БИБЛИОТЕКА", FlipperTheme.yellow, modifier = Modifier.fillMaxWidth()) { tab = 1 }
                }
            } else {
                val card = selectedCard!!
                Column {
                    Box(
                        Modifier.fillMaxWidth()
                            .border(1.dp, FlipperTheme.yellow.copy(0.5f), RoundedCornerShape(16.dp))
                            .background(FlipperTheme.yellowDim, RoundedCornerShape(16.dp))
                            .padding(20.dp)
                    ) {
                        Column {
                            androidx.compose.material3.Text("КАРТА ДЛЯ ЭМУЛЯЦИИ",
                                color = FlipperTheme.textSecondary, fontSize = 10.sp,
                                fontFamily = FlipperTheme.mono, letterSpacing = 2.sp)
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.material3.Text(card.protocol,
                                color = FlipperTheme.yellow, fontSize = 22.sp,
                                fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Black)
                            androidx.compose.material3.Text("UID: ${card.uid}",
                                color = FlipperTheme.textSecondary, fontSize = 13.sp, fontFamily = FlipperTheme.mono)
                            card.facilityCode?.let {
                                androidx.compose.material3.Text("FC: $it  CN: ${card.cardNumber}",
                                    color = FlipperTheme.textSecondary, fontSize = 12.sp, fontFamily = FlipperTheme.mono)
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    ActionButton(
                        label = if (isEmulating) "⏹ СТОП" else "▶ ЭМУЛИРОВАТЬ",
                        color = if (isEmulating) FlipperTheme.red else FlipperTheme.yellow,
                        modifier = Modifier.fillMaxWidth()
                    ) { isEmulating = !isEmulating }
                    if (isEmulating) {
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth()
                            .background(FlipperTheme.yellowDim, RoundedCornerShape(10.dp))
                            .padding(16.dp)) {
                            androidx.compose.material3.Text(
                                "Flipper эмулирует RFID карту.\nПоднеси к считывателю на воротах / двери.",
                                color = FlipperTheme.yellow, fontSize = 13.sp, fontFamily = FlipperTheme.mono, lineHeight = 20.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RfidAnimation(active: Boolean) {
    val inf = rememberInfiniteTransition(label = "rfid")
    val scale by inf.animateFloat(1f, 1.5f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900),
            androidx.compose.animation.core.RepeatMode.Reverse
        ), label = "scale")
    val alpha by inf.animateFloat(0.5f, 0f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900),
            androidx.compose.animation.core.RepeatMode.Reverse
        ), label = "alpha")

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
            androidx.compose.material3.Text("🔑", fontSize = 26.sp)
        }
    }
}
