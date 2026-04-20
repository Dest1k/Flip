package com.flippercontrol.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.flippercontrol.core.BleState

object FlipperTheme {
    val bg         = Color(0xFF08080E)
    val surface    = Color(0xFF0F0F1A)
    val card       = Color(0xFF141420)
    val border     = Color(0xFF1E1E30)
    val accent     = Color(0xFFFF6B00)
    val accentDim  = Color(0x33FF6B00)
    val green      = Color(0xFF00FF87)
    val greenDim   = Color(0x2200FF87)
    val blue       = Color(0xFF00BFFF)
    val blueDim    = Color(0x2200BFFF)
    val purple     = Color(0xFFAA44FF)
    val purpleDim  = Color(0x22AA44FF)
    val red        = Color(0xFFFF3355)
    val redDim     = Color(0x22FF3355)
    val yellow     = Color(0xFFFFCC00)
    val yellowDim  = Color(0x22FFCC00)
    val textPrimary   = Color(0xFFE8E8F0)
    val textSecondary = Color(0xFF666680)
    val mono = FontFamily.Monospace
}

data class FeatureCard(
    val id: String,
    val icon: String,
    val title: String,
    val subtitle: String,
    val color: Color,
    val dimColor: Color,
    val badge: String? = null,
)

val featureCards = listOf(
    FeatureCard("subghz",   "📡", "Sub-GHz",      "300–928 MHz · RF scan · replay",   FlipperTheme.green,  FlipperTheme.greenDim),
    FeatureCard("nfc",      "💳", "NFC",           "13.56 MHz · dump · emulate",        FlipperTheme.blue,   FlipperTheme.blueDim),
    FeatureCard("rfid",     "🔑", "RFID 125kHz",  "EM4100 · HID · clone",              FlipperTheme.yellow, FlipperTheme.yellowDim),
    FeatureCard("ir",       "🔴", "Infrared",      "Универсальный пульт · запись",      FlipperTheme.red,    FlipperTheme.redDim),
    FeatureCard("ble",      "📶", "Bluetooth",     "BLE spam · scanner · sniffer",      FlipperTheme.purple, FlipperTheme.purpleDim,  badge = "NEW"),
    FeatureCard("badusb",   "⌨️", "Bad USB",       "HID payload · скрипты",             FlipperTheme.accent, FlipperTheme.accentDim),
    FeatureCard("gpio",     "⚡", "GPIO",          "12 пинов · PWM · 3.3V/5V",          FlipperTheme.green,  FlipperTheme.greenDim),
    FeatureCard("files",    "📁", "Файлы SD",      "Браузер · загрузка · управление",   FlipperTheme.blue,   FlipperTheme.blueDim),
)

@Composable
fun DashboardScreen(
    bleState: BleState,
    deviceInfo: Map<String, String>,
    onConnectClick: () -> Unit,
    onFeatureClick: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(FlipperTheme.bg)
    ) {
        GridBackground()

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(48.dp))

            HeaderSection()

            Spacer(Modifier.height(20.dp))

            ConnectionCard(bleState, deviceInfo, onConnectClick)

            Spacer(Modifier.height(24.dp))

            Text(
                "ВОЗМОЖНОСТИ",
                color = FlipperTheme.textSecondary,
                fontSize = 11.sp,
                fontFamily = FlipperTheme.mono,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            FeatureGrid(
                cards = featureCards,
                enabled = bleState is BleState.Connected,
                onCardClick = onFeatureClick
            )

            Spacer(Modifier.height(32.dp))

            if (bleState is BleState.Connected) {
                RecentActionsSection()
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun HeaderSection() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                "FLIPPER",
                color = FlipperTheme.accent,
                fontSize = 26.sp,
                fontFamily = FlipperTheme.mono,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp
            )
            Text(
                "CONTROL",
                color = FlipperTheme.textSecondary,
                fontSize = 12.sp,
                fontFamily = FlipperTheme.mono,
                letterSpacing = 6.sp
            )
        }

        PulsingDot()
    }
}

@Composable
fun PulsingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Box(
        Modifier
            .size(10.dp)
            .alpha(alpha)
            .background(FlipperTheme.accent, shape = RoundedCornerShape(50))
    )
}

@Composable
fun ConnectionCard(
    state: BleState,
    info: Map<String, String>,
    onConnect: () -> Unit
) {
    val isConnected = state is BleState.Connected

    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (isConnected) FlipperTheme.green.copy(alpha = 0.5f)
                         else FlipperTheme.border,
                    RoundedCornerShape(16.dp))
            .background(
                if (isConnected) FlipperTheme.greenDim else FlipperTheme.surface,
                RoundedCornerShape(16.dp)
            )
            .padding(20.dp)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        when (state) {
                            is BleState.Connected    -> (state as BleState.Connected).name
                            is BleState.Scanning     -> "Поиск..."
                            is BleState.Connecting   -> "Подключение..."
                            is BleState.Disconnected -> "Не подключено"
                            is BleState.Error        -> "Ошибка"
                        },
                        color = if (isConnected) FlipperTheme.green else FlipperTheme.textPrimary,
                        fontSize = 17.sp,
                        fontFamily = FlipperTheme.mono,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when (state) {
                            is BleState.Connected    -> "BLE · ${info["hardware_name"] ?: "RogueMaster"}"
                            is BleState.Scanning     -> "Сканирую BLE..."
                            is BleState.Connecting   -> "GATT handshake..."
                            is BleState.Disconnected -> "Нажми для подключения"
                            is BleState.Error        -> (state as BleState.Error).message
                        },
                        color = FlipperTheme.textSecondary,
                        fontSize = 12.sp,
                        fontFamily = FlipperTheme.mono
                    )
                }

                if (!isConnected) {
                    Button(
                        onClick = onConnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FlipperTheme.accent,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("CONNECT", fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Black)
                    }
                } else {
                    Text("✓", color = FlipperTheme.green, fontSize = 24.sp)
                }
            }

            if (isConnected && info.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = FlipperTheme.border)
                Spacer(Modifier.height(12.dp))
                DeviceInfoRow(info)
            }
        }
    }
}

@Composable
fun DeviceInfoRow(info: Map<String, String>) {
    val chips = listOf(
        "FW" to (info["firmware_version"] ?: "—"),
        "HW" to (info["hardware_revision"] ?: "—"),
        "SN" to (info["hardware_uid"] ?: "—").take(8),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { (label, value) ->
            Box(
                Modifier
                    .background(FlipperTheme.card, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    "$label: $value",
                    color = FlipperTheme.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FlipperTheme.mono
                )
            }
        }
    }
}

@Composable
fun FeatureGrid(
    cards: List<FeatureCard>,
    enabled: Boolean,
    onCardClick: (String) -> Unit
) {
    val chunked = cards.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        chunked.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { card ->
                    FeatureCardItem(
                        card = card,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        onClick = { onCardClick(card.id) }
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun FeatureCardItem(
    card: FeatureCard,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.35f

    Box(
        modifier
            .alpha(alpha)
            .clickable(enabled = enabled, onClick = onClick)
            .border(1.dp, card.color.copy(alpha = if (enabled) 0.3f else 0.1f),
                    RoundedCornerShape(14.dp))
            .background(card.dimColor, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(card.icon, fontSize = 24.sp)
                card.badge?.let {
                    Text(
                        it,
                        color = card.color,
                        fontSize = 9.sp,
                        fontFamily = FlipperTheme.mono,
                        modifier = Modifier
                            .background(card.dimColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                card.title,
                color = card.color,
                fontSize = 14.sp,
                fontFamily = FlipperTheme.mono,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                card.subtitle,
                color = FlipperTheme.textSecondary,
                fontSize = 10.sp,
                fontFamily = FlipperTheme.mono,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
fun RecentActionsSection() {
    Text(
        "ПОСЛЕДНИЕ ДЕЙСТВИЯ",
        color = FlipperTheme.textSecondary,
        fontSize = 11.sp,
        fontFamily = FlipperTheme.mono,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(bottom = 10.dp)
    )

    val items = listOf(
        Triple(FlipperTheme.green,  "Sub-GHz",  "433.920 MHz · captured"),
        Triple(FlipperTheme.blue,   "NFC",       "Mifare 1K · dumped"),
        Triple(FlipperTheme.purple, "BLE",       "AirPods spam · 847 pkts"),
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (color, label, detail) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(FlipperTheme.surface, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(color, RoundedCornerShape(50))
                )
                Spacer(Modifier.width(12.dp))
                Text(label, color = color, fontSize = 12.sp,
                     fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold,
                     modifier = Modifier.width(70.dp))
                Text(detail, color = FlipperTheme.textSecondary,
                     fontSize = 12.sp, fontFamily = FlipperTheme.mono)
            }
        }
    }
}

@Composable
fun GridBackground() {
    Canvas(Modifier.fillMaxSize()) {
        val step = 40.dp.toPx()
        val color = Color(0xFF0D0D1A)
        var x = 0f
        while (x < size.width) {
            drawLine(color, Offset(x, 0f), Offset(x, size.height), 0.5f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(color, Offset(0f, y), Offset(size.width, y), 0.5f)
            y += step
        }
    }
}
