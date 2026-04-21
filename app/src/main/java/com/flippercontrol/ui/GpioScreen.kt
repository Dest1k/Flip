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
import com.flippercontrol.core.GpioPin as CorePin
import kotlinx.coroutines.launch

data class GpioPin(
    val label: String,
    val rpcPin: CorePin,
    val canPwm: Boolean = false,
    val canAdc: Boolean = false,
)

val gpioPins = listOf(
    GpioPin("PC0", CorePin.PC0, canAdc = true),
    GpioPin("PC1", CorePin.PC1, canAdc = true),
    GpioPin("PC3", CorePin.PC3, canAdc = true),
    GpioPin("PB2", CorePin.PB2),
    GpioPin("PB3", CorePin.PB3, canPwm = true),
    GpioPin("PA4", CorePin.PA4, canAdc = true),
    GpioPin("PA6", CorePin.PA6, canAdc = true),
    GpioPin("PA7", CorePin.PA7, canPwm = true),
)

data class PinState(
    val isOutput: Boolean = true,
    val isHigh: Boolean = false,
    val pwmEnabled: Boolean = false,
    val pwmFrequency: Int = 1000,
    val pwmDutyCycle: Int = 50,
)

@Composable
fun GpioScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val pinStates = remember { mutableStateMapOf<String, PinState>() }
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
            "ПИНЫ (PC0–PA7 · 3.3V · RPC доступны)",
            color = FlipperTheme.textSecondary, fontSize = 10.sp,
            fontFamily = FlipperTheme.mono, letterSpacing = 1.sp
        )
        Spacer(Modifier.height(8.dp))

        GpioPinGrid(
            pins = gpioPins,
            pinStates = pinStates,
            selected = selectedPin,
            onSelect = { selectedPin = it }
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = FlipperTheme.border)
        Spacer(Modifier.height(12.dp))

        selectedPin?.let { pin ->
            val state = pinStates.getOrDefault(pin.label, PinState())
            PinDetailPanel(
                pin = pin,
                state = state,
                onToggle = {
                    val newHigh = !state.isHigh
                    pinStates[pin.label] = state.copy(isHigh = newHigh)
                    scope.launch {
                        // Set mode to output first, then write
                        session.gpioSetPinMode(pin.rpcPin, output = true)
                        val ok = session.gpioWritePin(pin.rpcPin, newHigh)
                        statusText = if (ok) "${pin.label} → ${if (newHigh) "HIGH" else "LOW"}"
                                     else "${pin.label}: ошибка записи"
                    }
                },
                onPwmToggle = {
                    pinStates[pin.label] = state.copy(pwmEnabled = !state.pwmEnabled)
                    statusText = "${pin.label} PWM ${if (!state.pwmEnabled) "ON" else "OFF"}"
                },
                onFreqChange = { freq -> pinStates[pin.label] = state.copy(pwmFrequency = freq) },
                onDutyChange = { duty -> pinStates[pin.label] = state.copy(pwmDutyCycle = duty) }
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

        Text(statusText, color = FlipperTheme.textSecondary,
            fontSize = 11.sp, fontFamily = FlipperTheme.mono)

        Spacer(Modifier.height(8.dp))

        ActionButton(
            label = "⬇ ВСЕ ПИНЫ → LOW",
            color = FlipperTheme.red,
            modifier = Modifier.fillMaxWidth()
        ) {
            scope.launch {
                gpioPins.forEach { pin ->
                    pinStates[pin.label] = PinState(isHigh = false)
                    session.gpioSetPinMode(pin.rpcPin, output = true)
                    session.gpioWritePin(pin.rpcPin, false)
                }
                statusText = "Все пины → LOW"
            }
        }
    }
}

@Composable
fun GpioPinGrid(
    pins: List<GpioPin>,
    pinStates: Map<String, PinState>,
    selected: GpioPin?,
    onSelect: (GpioPin) -> Unit
) {
    val chunked = pins.chunked(4)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chunked.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { pin ->
                    val state = pinStates.getOrDefault(pin.label, PinState())
                    val isSelected = selected?.label == pin.label
                    val color = when {
                        state.isHigh     -> FlipperTheme.green
                        state.pwmEnabled -> FlipperTheme.yellow
                        isSelected       -> FlipperTheme.accent
                        else             -> FlipperTheme.textSecondary
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
                                when {
                                    state.isHigh -> FlipperTheme.greenDim
                                    isSelected   -> FlipperTheme.accentDim
                                    else         -> FlipperTheme.surface
                                },
                                RoundedCornerShape(8.dp)
                            )
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(pin.label, color = color, fontSize = 11.sp,
                                fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold)
                            Text("3.3V", color = FlipperTheme.textSecondary,
                                fontSize = 8.sp, fontFamily = FlipperTheme.mono)
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pin.label, color = FlipperTheme.yellow, fontSize = 18.sp,
                fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(8.dp))
            Text(pin.rpcPin.name, color = FlipperTheme.textSecondary,
                fontSize = 12.sp, fontFamily = FlipperTheme.mono)
            Spacer(Modifier.weight(1f))
            if (pin.canPwm) Chip("PWM", FlipperTheme.yellow)
            if (pin.canAdc) { Spacer(Modifier.width(4.dp)); Chip("ADC", FlipperTheme.purple) }
        }

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
