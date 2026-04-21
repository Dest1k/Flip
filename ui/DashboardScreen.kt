package com.flippercontrol.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

// ─── Тема ─────────────────────────────────────────────────────────────────────

object FlipperTheme {
    val bg         = Color(0xFF08080E)
    val surface    = Color(0xFF0F0F1A)
    val card       = Color(0xFF141420)
    val border     = Color(0xFF1E1E30)
    val accent     = Color(0xFFFF6B00)   // Flipper orange
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

// ─── Feature card data ────────────────────────────────────────────────────────

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

// ─── Dashboard Screen ─────────────────────────────────────────────────────────

@Composable
fun DashboardScreen(
    bleState: BleState,
    deviceInfo: Map<String, String>,
    connectionLog: List<String>,
    onConnectClick: () -> Unit,
    onCancelClick: () -> Unit,
    onFeatureClick: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(FlipperTheme.bg)
    ) {
        // Фоновая сетка
        GridBackground()

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(48.dp))

            // Заголовок
            HeaderSection()

            Spacer(Modifier.height(20.dp))

            // Статус подключения
            ConnectionCard(bleState, deviceInfo, connectionLog, onConnectClick, onCancelClick)

            Spacer(Modifier.height(24.dp))

            // Заголовок секции фич
            Text(
                "ВОЗМОЖНОСТИ",
                color = FlipperTheme.textSecondary,
                fontSize = 11.sp,
                fontFamily = FlipperTheme.mono,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Сетка фич 2x4
            FeatureGrid(
                cards = featureCards,
                enabled = bleState is BleState.Connected,
                onCardClick = onFeatureClick
            )

            Spacer(Modifier.height(32.dp))

            // Последние действия
            if (bleState is BleState.Connected) {
                RecentActionsSection()
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ─── Header ───────────────────────────────────────────────────────────────────

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

        // Пульсирующий индикатор
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

// ─── Connection Card ──────────────────────────────────────────────────────────

@Composable
fun ConnectionCard(
    state: BleState,
    info: Map<String, String>,
    connectionLog: List<String>,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
) {
    val isConnected  = state is BleState.Connected
    val isActive     = state is BleState.Scanning || state is BleState.Connecting
    val isError      = state is BleState.Error
    val showLog      = connectionLog.isNotEmpty() && !isConnected

    val borderColor = when {
        isConnected -> FlipperTheme.green.copy(alpha = 0.5f)
        isError     -> FlipperTheme.red.copy(alpha = 0.4f)
        isActive    -> FlipperTheme.accent.copy(alpha = 0.4f)
        else        -> FlipperTheme.border
    }
    val bgColor = when {
        isConnected -> FlipperTheme.greenDim
        isError     -> FlipperTheme.redDim
        else        -> FlipperTheme.surface
    }

    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .background(bgColor, RoundedCornerShape(16.dp))
            .padding(20.dp)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (state) {
                            is BleState.Connected    -> state.name
                            is BleState.Scanning     -> "Поиск..."
                            is BleState.Connecting   -> "Подключение..."
                            is BleState.Disconnected -> "Не подключено"
                            is BleState.Error        -> "Ошибка"
                        },
                        color = when {
                            isConnected -> FlipperTheme.green
                            isError     -> FlipperTheme.red
                            isActive    -> FlipperTheme.accent
                            else        -> FlipperTheme.textPrimary
                        },
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
                            is BleState.Error        -> state.message
                        },
                        color = FlipperTheme.textSecondary,
                        fontSize = 12.sp,
                        fontFamily = FlipperTheme.mono
                    )
                }

                Spacer(Modifier.width(12.dp))

                when {
                    isConnected -> Text("✓", color = FlipperTheme.green, fontSize = 24.sp)
                    isActive -> Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FlipperTheme.red.copy(alpha = 0.15f),
                            contentColor = FlipperTheme.red
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("ОТМЕНА", fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Black, fontSize = 12.sp)
                    }
                    else -> Button(
                        onClick = onConnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FlipperTheme.accent,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("CONNECT", fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Black)
                    }
                }
            }

            // Лог подключения
            if (showLog) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = FlipperTheme.border)
                Spacer(Modifier.height(10.dp))
                ConnectionLogPanel(connectionLog)
            }

            // Инфо об устройстве когда подключены
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
fun ConnectionLogPanel(log: List<String>) {
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }

    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "LOG",
                color = FlipperTheme.textSecondary,
                fontSize = 10.sp,
                fontFamily = FlipperTheme.mono,
                letterSpacing = 2.sp
            )
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    "КОПИРОВАТЬ",
                    color = FlipperTheme.accent,
                    fontSize = 10.sp,
                    fontFamily = FlipperTheme.mono,
                    letterSpacing = 1.sp
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .background(Color(0xFF080810), RoundedCornerShape(8.dp))
                .border(1.dp, FlipperTheme.border, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            LazyColumn(state = listState) {
                items(log) { line ->
                    val isError = line.contains("Ошибка") || line.contains("error", ignoreCase = true)
                    val color = when {
                        isError                  -> FlipperTheme.red
                        line.contains("Готово") -> FlipperTheme.green
                        else                     -> FlipperTheme.textSecondary
                    }
                    Text(
                        text = line,
                        color = color,
                        fontSize = 11.sp,
                        fontFamily = FlipperTheme.mono,
                        lineHeight = 16.sp
                    )
                }
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

// ─── Feature Grid ─────────────────────────────────────────────────────────────

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

// ─── Recent Actions ────────────────────────────────────────────────────────────

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

// ─── Grid background ──────────────────────────────────────────────────────────

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
