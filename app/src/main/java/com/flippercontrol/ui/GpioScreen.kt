package com.flippercontrol.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.flippercontrol.core.FlipperRpcSession
import kotlinx.coroutines.launch

// ─── Пины Flipper Zero GPIO ────────────────────────────────────────────────────

data class GpioPin(
    val number: Int,
    val name: String,       // PA7, PB3 и т.д.
    val label: String,      // friendly name
    val voltage: String,    // 3.3V / 5V / GND
    val canOutput: Boolean,
    val canPwm: Boolean = false,
    val canAdc: Boolean = false,
)

// Раскладка 18-пинового разъёма Flipper Zero
val gpioPins = listOf(
    GpioPin(1,  "PC0",  "C0",  "3.3V", true,  false, true),
    GpioPin(2,  "PC1",  "C1",  "3.3V", true,  false, true),
    GpioPin(3,  "PC3",  "C3",  "3.3V", true,  false, true),
    GpioPin(4,  "PB2",  "B2",  "3.3V", true),
    GpioPin(5,  "PB3",  "B3",  "3.3V", true,  true),
    GpioPin(6,  "PA4",  "A4",  "3.3V", true,  false, true),
    GpioPin(7,  "PA6",  "A6",  "3.3V", true,  false, true),
    GpioPin(8,  "PA7",  "A7",  "3.3V", true,  true),
    GpioPin(9,  "---",  "3.3V","3.3V", false),
    GpioPin(10, "---",  "GND", "GND",  false),
    GpioPin(11, "PA0",  "A0",  "3.3V", true,  true,  true),
    GpioPin(12, "PA1",  "A1",  "3.3V", true,  true),
    GpioPin(13, "---",  "5V",  "5V",   false),
    GpioPin(14, "---",  "GND", "GND",  false),
    GpioPin(15, "PB11", "B11", "3.3V", true,  true),
    GpioPin(16, "PB12", "B12", "3.3V", true),
    GpioPin(17, "PE9",  "E9",  "3.3V", true,  true),
    GpioPin(18, "PE10", "E10", "3.3V", true,  true),
)

data class PinState(
    val isOutput: Boolean = true,
    val isHigh: Boolean = false,
    val pwmEnabled: Boolean = false,
    val pwmFrequency: Int = 1000,
    val pwmDutyCycle: Int = 50,
    val adcValue: Int = 0,
)

// ─── GPIO Screen ──────────────────────────────────────────────────────────────

@Composable
fun GpioScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val pinStates = remember { mutableStateMapOf<Int, PinState>() }
    var selectedPin by remember { mutableStateOf<GpioPin?>(null) }
    var statusText by remember { mutableStateOf("GPIO готов") }

    Column(
        Modifier
            .fillMaxSize()
            .background(FlipperTheme.bg)
            .padding(16.dp)
    ) {
        TopBar(title = "GPIO", color = FlipperTheme.yellow, onBack = onBack)

        Text(
            "РАСПИНОВКА",
            color = FlipperTheme.textSecondary, fontSize = 10.sp,
            fontFamily = FlipperTheme.mono, letterSpacing = 2.sp
        )
        Spacer(Modifier.height(8.dp))

        // Pinout grid
        GpioPinGrid(
            pins = gpioPins.filter { it.canOutput },
            pinStates = pinStates,
            selected = selectedPin,
            onSelect = { selectedPin = it }
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = FlipperTheme.border)
        Spacer(Modifier.height(12.dp))

        // Pin detail panel
        selectedPin?.let { pin ->
            val state = pinStates.getOrDefault(pin.number, PinState())
            PinDetailPanel(
                pin = pin,
                state = state,
                onToggle = {
                    val newState = state.copy(isHigh = !state.isHigh)
                    pinStates[pin.number] = newState
                    scope.launch {
                        session.gpioWritePin(pin.number, newState.isHigh)
                        statusText = "${pin.label} → ${if (newState.isHigh) "HIGH" else "LOW"}"
                    }
                },
                onPwmToggle = {
                    pinStates[pin.number] = state.copy(pwmEnabled = !state.pwmEnabled)
                    statusText = "${pin.label} PWM ${if (!state.pwmEnabled) "ON" else "OFF"}"
                },
                onFreqChange = { freq ->
                    pinStates[pin.number] = state.copy(pwmFrequency = freq)
                },
                onDutyChange = { duty ->
                    pinStates[pin.number] = state.copy(pwmDutyCycle = duty)
                }
            )
        } ?: Box(
            Modifier.fillMaxWidth()
                .background(FlipperTheme.surface, RoundedCornerShape(10.dp))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Выбери пин выше",
                 color = FlipperTheme.textSecondary, fontSize = 13.sp, fontFamily = FlipperTheme.mono)
        }

        Spacer(Modifier.weight(1f))

        // Status
        Text(statusText, color = FlipperTheme.textSecondary,
             fontSize = 11.sp, fontFamily = FlipperTheme.mono)

        Spacer(Modifier.height(8.dp))

        // All LOW кнопка
        ActionButton(
            label = "⬇ ВСЕ ПИНЫ → LOW",
            color = FlipperTheme.red,
            modifier = Modifier.fillMaxWidth()
        ) {
            scope.launch {
                gpioPins.filter { it.canOutput }.forEach { pin ->
                    pinStates[pin.number] = PinState(isHigh = false)
                    session.gpioWritePin(pin.number, false)
                }
                statusText = "Все пины → LOW"
            }
        }
    }
}

// ─── Pin grid ─────────────────────────────────────────────────────────────────

@Composable
fun GpioPinGrid(
    pins: List<GpioPin>,
    pinStates: Map<Int, PinState>,
    selected: GpioPin?,
    onSelect: (GpioPin) -> Unit
) {
    val chunked = pins.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chunked.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { pin ->
                    val state = pinStates.getOrDefault(pin.number, PinState())
                    val isSelected = selected?.number == pin.number
                    val color = when {
                        state.isHigh   -> FlipperTheme.green
                        state.pwmEnabled -> FlipperTheme.yellow
                        isSelected     -> FlipperTheme.accent
                        else           -> FlipperTheme.textSecondary
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .clickable { onSelect(pin) }
                            .border(
                                if (isSelected) 1.5.dp else 1.dp,
                                color.copy(alpha = if (isSelected) 0.8f else 0.3f),
                                RoundedCornerShape(8.dp)
                            )
                            .background(
                                if (state.isHigh) FlipperTheme.greenDim
                                else if (isSelected) FlipperTheme.accentDim
                                else FlipperTheme.surface,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(pin.label, color = color, fontSize = 11.sp,
                                 fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold)
                            Text(pin.voltage, color = FlipperTheme.textSecondary,
                                 fontSize = 9.sp, fontFamily = FlipperTheme.mono)
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ─── Pin detail ───────────────────────────────────────────────────────────────

@Composable
fun PinDetailPanel(
    pin: GpioPin,
    state: PinState,
    onToggle: () -> Unit,
    onPwmToggle: () -> Unit,
    onFreqChange: (Int) -> Unit,
    onDutyChange: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, FlipperTheme.yellowDim, RoundedCornerShape(12.dp))
            .background(FlipperTheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        // Pin header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pin.label, color = FlipperTheme.yellow, fontSize = 18.sp,
                 fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(8.dp))
            Text(pin.name, color = FlipperTheme.textSecondary,
                 fontSize = 12.sp, fontFamily = FlipperTheme.mono)
            Spacer(Modifier.weight(1f))
            // Capabilities
            if (pin.canPwm) Chip("PWM", FlipperTheme.yellow)
            if (pin.canAdc) { Spacer(Modifier.width(4.dp)); Chip("ADC", FlipperTheme.purple) }
        }

        Spacer(Modifier.height(12.dp))

        // Toggle HIGH/LOW
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionButton(
                label = if (state.isHigh) "● HIGH" else "○ LOW",
                color = if (state.isHigh) FlipperTheme.green else FlipperTheme.red,
                modifier = Modifier.weight(1f),
                onClick = onToggle
            )
            if (pin.canPwm) {
                ActionButton(
                    label = if (state.pwmEnabled) "⚡ PWM ON" else "⚡ PWM",
                    color = FlipperTheme.yellow,
                    modifier = Modifier.weight(1f),
                    onClick = onPwmToggle
                )
            }
        }

        // PWM controls
        if (pin.canPwm && state.pwmEnabled) {
            Spacer(Modifier.height(12.dp))
            Text("Частота: ${state.pwmFrequency} Hz",
                 color = FlipperTheme.yellow, fontSize = 11.sp, fontFamily = FlipperTheme.mono)
            Slider(
                value = state.pwmFrequency.toFloat(),
                onValueChange = { onFreqChange(it.toInt()) },
                valueRange = 100f..50000f,
                colors = SliderDefaults.colors(
                    thumbColor = FlipperTheme.yellow,
                    activeTrackColor = FlipperTheme.yellow,
                    inactiveTrackColor = FlipperTheme.border
                )
            )
            Text("Скважность: ${state.pwmDutyCycle}%",
                 color = FlipperTheme.yellow, fontSize = 11.sp, fontFamily = FlipperTheme.mono)
            Slider(
                value = state.pwmDutyCycle.toFloat(),
                onValueChange = { onDutyChange(it.toInt()) },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = FlipperTheme.yellow,
                    activeTrackColor = FlipperTheme.yellow,
                    inactiveTrackColor = FlipperTheme.border
                )
            )
        }
    }
}

@Composable
fun Chip(label: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(label, color = color, fontSize = 9.sp,
             fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold)
    }
}
