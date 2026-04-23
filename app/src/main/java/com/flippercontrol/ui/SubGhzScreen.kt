package com.flippercontrol.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import com.flippercontrol.core.FlipperRpcSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

// ─── Популярные частоты ───────────────────────────────────────────────────────

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

// ─── Sub-GHz Screen ───────────────────────────────────────────────────────────

@Composable
fun SubGhzScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var isReceiving   by remember { mutableStateOf(false) }
    var selectedFreq  by remember { mutableStateOf(frequencyPresets[0]) }
    var captured      by remember { mutableStateOf<List<CapturedSignal>>(emptyList()) }
    var statusText    by remember { mutableStateOf("Готов") }
    var log           by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    val addLog = { text: String, level: LogLevel -> log = buildLog(log, text, level) }

    // Custom frequency input state
    var freqText  by remember { mutableStateOf(selectedFreq.label) }
    var freqError by remember { mutableStateOf(false) }

    // Sync text field when preset is tapped
    LaunchedEffect(selectedFreq) {
        freqText  = selectedFreq.label
        freqError = false
    }

    fun applyFreq() {
        val mhz = freqText.replace(",", ".").toDoubleOrNull()
        if (mhz == null || mhz < 100.0 || mhz > 1000.0) {
            freqError = true
            addLog("Неверная частота: «$freqText» (100–1000 МГц)", LogLevel.ERROR)
            return
        }
        freqError = false
        selectedFreq = FrequencyPreset(
            label = "%.3f".format(mhz),
            hz = (mhz * 1_000_000.0).toLong(),
            description = "Ручная"
        )
        addLog("Частота: ${selectedFreq.label} MHz", LogLevel.INFO)
    }

    // Signal energy — bursts when receiving
    var signalEnergy by remember { mutableFloatStateOf(0f) }
    val smoothEnergy by animateFloatAsState(signalEnergy, tween(200, easing = FastOutSlowInEasing), label = "e")

    LaunchedEffect(isReceiving) {
        if (isReceiving) {
            while (true) {
                signalEnergy = 0.4f + kotlin.random.Random.nextFloat() * 0.6f
                delay(kotlin.random.Random.nextLong(150, 600))
                signalEnergy = 0.15f
                delay(80)
            }
        } else {
            signalEnergy = 0f
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(FlipperTheme.bg)
            .padding(16.dp)
    ) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("← ", color = FlipperTheme.accent, fontSize = 20.sp,
                fontFamily = FlipperTheme.mono,
                modifier = Modifier.clickable { onBack() })
            Text("SUB-GHz", color = FlipperTheme.green, fontSize = 20.sp,
                fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            if (isReceiving) AnimatedReceivingBadge()
        }

        // Scrollable content
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {

            // ── Frequency presets ────────────────────────────────────────────
            Text("ПРЕСЕТЫ", color = FlipperTheme.textSecondary, fontSize = 10.sp,
                fontFamily = FlipperTheme.mono, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            FrequencySelector(selected = selectedFreq, onSelect = { selectedFreq = it })

            Spacer(Modifier.height(10.dp))

            // ── Custom frequency input ───────────────────────────────────────
            Text("ТОЧНАЯ НАСТРОЙКА", color = FlipperTheme.textSecondary, fontSize = 10.sp,
                fontFamily = FlipperTheme.mono, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = freqText,
                    onValueChange = { freqText = it; freqError = false },
                    modifier = Modifier.weight(1f),
                    label = { Text("Частота, MHz", fontFamily = FlipperTheme.mono, fontSize = 11.sp) },
                    singleLine = true,
                    isError = freqError,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { applyFreq() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = FlipperTheme.green,
                        unfocusedBorderColor = FlipperTheme.border,
                        errorBorderColor     = FlipperTheme.red,
                        focusedLabelColor    = FlipperTheme.green,
                        unfocusedLabelColor  = FlipperTheme.textSecondary,
                        focusedTextColor     = FlipperTheme.textPrimary,
                        unfocusedTextColor   = FlipperTheme.textPrimary,
                        cursorColor          = FlipperTheme.green,
                    ),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FlipperTheme.mono, fontSize = 14.sp)
                )
                ActionButton(
                    label = "ПРИМЕНИТЬ",
                    color = FlipperTheme.green,
                    modifier = Modifier.width(110.dp),
                    onClick = ::applyFreq
                )
            }
            if (freqError) {
                Text("Диапазон: 100–1000 MHz",
                    color = FlipperTheme.red, fontSize = 10.sp, fontFamily = FlipperTheme.mono)
            }

            Spacer(Modifier.height(14.dp))

            // ── Controls ─────────────────────────────────────────────────────
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    label = if (isReceiving) "⏹ СТОП" else "⏺ LISTEN",
                    color = FlipperTheme.green,
                    modifier = Modifier.weight(1f)
                ) {
                    scope.launch {
                        if (!isReceiving) {
                            addLog("Запуск SubGHz на ${selectedFreq.label} MHz...", LogLevel.INFO)
                            val ok = session.appStart("subghz")
                            if (ok) {
                                isReceiving = true
                                statusText = "Слушаю ${selectedFreq.label} MHz..."
                                addLog("SubGHz запущен ✓", LogLevel.OK)
                            } else {
                                statusText = "Ошибка запуска Sub-GHz"
                                addLog("Ошибка запуска Sub-GHz", LogLevel.ERROR)
                            }
                        } else {
                            addLog("Остановка...", LogLevel.INFO)
                            isReceiving = false           // set immediately — don't wait for Flipper ack
                            statusText = "Остановлено"
                            try { session.appExit() } catch (_: Exception) {}
                            addLog("Остановлено", LogLevel.OK)
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
                        addLog("Replay #${signal.id} @ ${signal.frequency / 1_000_000.0} MHz", LogLevel.INFO)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(statusText, color = FlipperTheme.textSecondary,
                fontSize = 11.sp, fontFamily = FlipperTheme.mono)

            Spacer(Modifier.height(14.dp))

            // ── Oscilloscope — always visible ─────────────────────────────────
            Text(
                if (isReceiving) "ОСЦИЛЛОГРАФ — LIVE" else "ОСЦИЛЛОГРАФ",
                color = if (isReceiving) FlipperTheme.green else FlipperTheme.textSecondary,
                fontSize = 10.sp, fontFamily = FlipperTheme.mono, letterSpacing = 2.sp
            )
            Spacer(Modifier.height(6.dp))
            RealtimeOscilloscope(
                isActive = isReceiving,
                energy = smoothEnergy,
                freqLabel = "${selectedFreq.label} MHz",
                modifier = Modifier.fillMaxWidth().height(110.dp)
            )

            Spacer(Modifier.height(14.dp))

            // ── Captured signals ──────────────────────────────────────────────
            Text("ЗАХВАЧЕННЫЕ СИГНАЛЫ (${captured.size})",
                color = FlipperTheme.textSecondary, fontSize = 10.sp,
                fontFamily = FlipperTheme.mono, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))

            if (captured.isEmpty()) {
                EmptyState("Нет сигналов. Нажми LISTEN и поднеси брелок / пульт.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    captured.take(10).forEach { signal ->
                        CapturedSignalRow(signal = signal, onReplay = {
                            scope.launch {
                                statusText = "Replay #${signal.id}..."
                                addLog("Replay #${signal.id} @ ${signal.frequency / 1_000_000.0} MHz", LogLevel.INFO)
                            }
                        })
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        // ── Activity log ──────────────────────────────────────────────────────
        Spacer(Modifier.height(8.dp))
        ActivityLogPanel(log, Modifier.fillMaxWidth())
    }
}

// ─── Realtime Oscilloscope ────────────────────────────────────────────────────

@Composable
fun RealtimeOscilloscope(
    isActive: Boolean,
    energy: Float,
    freqLabel: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "osc")

    // Phase drives the scrolling animation — faster when active
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (isActive) 800 else 3000, easing = LinearEasing)
        ),
        label = "phase"
    )

    Box(
        modifier
            .background(Color(0xFF04040C), RoundedCornerShape(8.dp))
            .border(1.dp, if (isActive) FlipperTheme.green.copy(alpha = 0.35f) else FlipperTheme.border, RoundedCornerShape(8.dp))
    ) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp)) {
            val w = size.width
            val h = size.height
            val mid = h / 2f
            val gridColor = Color(0xFF0E0E20)

            // Grid lines
            for (i in 1..3) drawLine(gridColor, Offset(0f, h * i / 4), Offset(w, h * i / 4), 0.5f)
            for (i in 1..7) drawLine(gridColor, Offset(w * i / 8, 0f), Offset(w * i / 8, h), 0.5f)

            val points = 240
            val path = Path()
            val signalColor = if (isActive)
                Color(0xFF00FF87)
            else
                Color(0xFF1E3040)

            for (i in 0..points) {
                val x = w * i.toFloat() / points
                val t = (x / w + phase) * 2.0 * PI
                // Cheap pseudo-random noise: xorshift on float bits for aperiodic texture
                val noiseIn = (i * 1.618034f + phase * 97.3f)
                val h1 = sin(noiseIn * 12.9898 + 78.233)
                val h2 = sin(noiseIn * 4.1414  + 43.197)
                val prng = abs(h1 * 43758.5453 - h1.toLong()) * 2.0 - 1.0   // [-1, 1]
                val prng2 = abs(h2 * 31543.117 - h2.toLong()) * 2.0 - 1.0
                val y: Float

                if (isActive) {
                    // AM-modulated burst signal + aperiodic noise (no repeating period)
                    val carrier  = sin(t * 11.37 + prng * 0.4)
                    val burst    = sin(t * 2.71 + 1.1) * 0.5 + 0.5   // burst envelope
                    val impulsive = if (prng > 0.7) prng * 0.6 else 0.0
                    val noise    = prng2 * 0.12
                    val signal   = (carrier * burst + impulsive + noise) * energy
                    y = (mid - (h * 0.44f * signal).toFloat()).coerceIn(2f, h - 2f)
                } else {
                    // Noise floor — aperiodic, stays near center
                    val noise = prng * 0.06 + sin(t * 3.1 + prng2) * 0.03
                    y = (mid + (h * 0.15f * noise).toFloat())
                }

                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            drawPath(
                path, signalColor,
                style = Stroke(width = if (isActive) 1.5f else 1f)
            )

            // Zero-line when idle
            if (!isActive) {
                drawLine(Color(0xFF1A2A3A), Offset(0f, mid), Offset(w, mid), strokeWidth = 0.5f)
            }
        }

        // Overlay labels
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                freqLabel,
                color = if (isActive) FlipperTheme.green else FlipperTheme.textSecondary.copy(alpha = 0.5f),
                fontSize = 9.sp,
                fontFamily = FlipperTheme.mono,
                fontWeight = FontWeight.Bold
            )
            if (isActive) {
                Text(
                    "● LIVE",
                    color = FlipperTheme.green,
                    fontSize = 9.sp,
                    fontFamily = FlipperTheme.mono,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ─── Frequency selector ────────────────────────────────────────────────────────

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
                        fontSize = 13.sp, fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold
                    )
                    Text(
                        preset.description,
                        color = FlipperTheme.textSecondary,
                        fontSize = 9.sp, fontFamily = FlipperTheme.mono
                    )
                }
            }
        }
    }
}

// ─── Signal row ────────────────────────────────────────────────────────────────

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
                color = FlipperTheme.green, fontSize = 12.sp,
                fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold
            )
            Text(
                "RSSI: ${signal.rssi} dBm · ${signal.timestamp} · ${signal.rawData.size} bytes",
                color = FlipperTheme.textSecondary, fontSize = 10.sp, fontFamily = FlipperTheme.mono
            )
        }
        Text("▶", color = FlipperTheme.accent, fontSize = 18.sp,
            modifier = Modifier.clickable(onClick = onReplay).padding(8.dp))
    }
}

// ─── Receiving badge ──────────────────────────────────────────────────────────

@Composable
fun AnimatedReceivingBadge() {
    val inf = rememberInfiniteTransition(label = "rx")
    val alpha by inf.animateFloat(
        0.4f, 1f,
        infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a"
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
