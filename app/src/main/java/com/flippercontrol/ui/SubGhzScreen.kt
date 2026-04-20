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

data class CapturedSignal(
    val id: Int,
    val frequency: Long,
    val rssi: Int,
    val modulation: String,
    val timestamp: String,
    val rawData: ByteArray = byteArrayOf()
)

@Composable
fun SubGhzScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var isScanning by remember { mutableStateOf(false) }
    var isReceiving by remember { mutableStateOf(false) }
    var selectedFreq by remember { mutableStateOf(frequencyPresets[0]) }
    var captured by remember { mutableStateOf<List<CapturedSignal>>(emptyList()) }
    var signalCounter by remember { mutableIntStateOf(0) }
    var statusText by remember { mutableStateOf("Готов") }

    LaunchedEffect(session) {
        session.events.collect { response ->
            val rawBytes = response.payload[402] as? ByteArray ?: return@collect
            signalCounter++
            val newSignal = CapturedSignal(
                id          = signalCounter,
                frequency   = selectedFreq.hz,
                rssi        = -65,
                modulation  = "AM650",
                timestamp   = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                  .format(java.util.Date()),
                rawData     = rawBytes
            )
            captured = listOf(newSignal) + captured.take(49)
        }
    }

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
            Text(
                "← ",
                color = FlipperTheme.accent,
                fontSize = 20.sp,
                fontFamily = FlipperTheme.mono,
                modifier = Modifier.clickable { onBack() }
            )
            Text(
                "SUB-GHz",
                color = FlipperTheme.green,
                fontSize = 20.sp,
                fontFamily = FlipperTheme.mono,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.weight(1f))
            if (isReceiving) AnimatedReceivingBadge()
        }

        Text("ЧАСТОТА", color = FlipperTheme.textSecondary, fontSize = 10.sp,
             fontFamily = FlipperTheme.mono, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))

        FrequencySelector(
            selected = selectedFreq,
            onSelect = { selectedFreq = it }
        )

        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ActionButton(
                label = if (isReceiving) "⏹ СТОП" else "⏺ LISTEN",
                color = FlipperTheme.green,
                modifier = Modifier.weight(1f)
            ) {
                scope.launch {
                    if (!isReceiving) {
                        val ok = session.subGhzStartReceive(selectedFreq.hz)
                        if (ok) {
                            isReceiving = true
                            statusText = "Слушаю ${selectedFreq.label} MHz..."
                        } else {
                            statusText = "Ошибка запуска Sub-GHz"
                        }
                    } else {
                        session.subGhzStopReceive()
                        isReceiving = false
                        statusText = "Остановлено"
                    }
                }
            }

            ActionButton(
                label = "▶ REPLAY",
                color = FlipperTheme.accent,
                enabled = captured.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                scope.launch {
                    val signal = captured.firstOrNull() ?: return@launch
                    statusText = "Replay → ${signal.frequency / 1_000_000.0} MHz"
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            statusText,
            color = FlipperTheme.textSecondary,
            fontSize = 11.sp,
            fontFamily = FlipperTheme.mono
        )

        Spacer(Modifier.height(16.dp))

        if (isReceiving) {
            SignalVisualizer()
            Spacer(Modifier.height(16.dp))
        }

        Text(
            "ЗАХВАЧЕННЫЕ СИГНАЛЫ (${captured.size})",
            color = FlipperTheme.textSecondary,
            fontSize = 10.sp,
            fontFamily = FlipperTheme.mono,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(captured) { signal ->
                CapturedSignalRow(signal = signal, onReplay = {
                    scope.launch {
                        statusText = "Replay #${signal.id}..."
                    }
                })
            }
            if (captured.isEmpty()) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(FlipperTheme.surface, RoundedCornerShape(10.dp))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Нет сигналов. Нажми LISTEN и поднеси брелок / пульт.",
                            color = FlipperTheme.textSecondary,
                            fontSize = 12.sp,
                            fontFamily = FlipperTheme.mono
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FrequencySelector(
    selected: FrequencyPreset,
    onSelect: (FrequencyPreset) -> Unit
) {
    val scroll = rememberScrollState()
    Row(
        Modifier.horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        frequencyPresets.forEach { preset ->
            val isSelected = preset == selected
            Box(
                Modifier
                    .clickable { onSelect(preset) }
                    .border(
                        1.dp,
                        if (isSelected) FlipperTheme.green else FlipperTheme.border,
                        RoundedCornerShape(8.dp)
                    )
                    .background(
                        if (isSelected) FlipperTheme.greenDim else FlipperTheme.surface,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${preset.label} MHz",
                        color = if (isSelected) FlipperTheme.green else FlipperTheme.textPrimary,
                        fontSize = 13.sp,
                        fontFamily = FlipperTheme.mono,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        preset.description,
                        color = FlipperTheme.textSecondary,
                        fontSize = 9.sp,
                        fontFamily = FlipperTheme.mono
                    )
                }
            }
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
fun CapturedSignalRow(signal: CapturedSignal, onReplay: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(FlipperTheme.surface, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${signal.frequency / 1_000_000.0} MHz · ${signal.modulation}",
                color = FlipperTheme.green,
                fontSize = 12.sp,
                fontFamily = FlipperTheme.mono,
                fontWeight = FontWeight.Bold
            )
            Text(
                "RSSI: ${signal.rssi} dBm · ${signal.timestamp} · ${signal.rawData.size} bytes",
                color = FlipperTheme.textSecondary,
                fontSize = 10.sp,
                fontFamily = FlipperTheme.mono
            )
        }
        Text(
            "▶",
            color = FlipperTheme.accent,
            fontSize = 18.sp,
            modifier = Modifier.clickable(onClick = onReplay).padding(8.dp)
        )
    }
}

@Composable
fun SignalVisualizer() {
    val infiniteTransition = rememberInfiniteTransition(label = "signal")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "offset"
    )

    Box(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(FlipperTheme.surface, RoundedCornerShape(8.dp))
    ) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val w = size.width
            val h = size.height
            val mid = h / 2

            val path = Path()
            path.moveTo(0f, mid)
            val points = 120
            for (i in 0..points) {
                val x = w * i / points
                val phase = (x / w + offset) * 2 * Math.PI
                val noiseFreq = if (i % 7 < 3) 8.0 else 2.0
                val y = mid + (h * 0.35f * Math.sin(phase * noiseFreq)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = Color(0xFF00FF87), style =
                androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
        }
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
