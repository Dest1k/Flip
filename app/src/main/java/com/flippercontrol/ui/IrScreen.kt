package com.flippercontrol.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.flippercontrol.core.FlipperRpcSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─── Устройства и кнопки ──────────────────────────────────────────────────────

data class IrDevice(
    val id: String,
    val name: String,
    val icon: String,
    val buttons: List<IrButton>
)

data class IrButton(
    val id: String,
    val label: String,
    val icon: String = "",
    val row: Int,
    val col: Int,
    val wide: Boolean = false  // занимает 2 колонки
)

// Универсальный ТВ пульт
val tvButtons = listOf(
    IrButton("pwr",     "Power",    "⏻", 0, 1),
    IrButton("mute",    "Mute",     "🔇", 1, 0),
    IrButton("vol_up",  "Vol +",    "🔊", 1, 1),
    IrButton("vol_dn",  "Vol -",    "🔉", 1, 2),
    IrButton("ch_up",   "Ch +",     "⬆", 2, 1),
    IrButton("ch_dn",   "Ch -",     "⬇", 2, 2),
    IrButton("up",      "↑",        "",  3, 1),
    IrButton("left",    "←",        "",  4, 0),
    IrButton("ok",      "OK",       "",  4, 1),
    IrButton("right",   "→",        "",  4, 2),
    IrButton("down",    "↓",        "",  5, 1),
    IrButton("back",    "Back",     "↩", 6, 0),
    IrButton("home",    "Home",     "⌂", 6, 1),
    IrButton("menu",    "Menu",     "≡", 6, 2),
)

val irDevices = listOf(
    IrDevice("tv",      "Телевизор",   "📺", tvButtons),
    IrDevice("ac",      "Кондиционер", "❄️", listOf(
        IrButton("pwr",    "Power",  "⏻", 0, 1),
        IrButton("temp_up","Temp +", "🌡", 1, 0),
        IrButton("temp_dn","Temp -", "🌡", 1, 2),
        IrButton("fan_up", "Fan +",  "💨", 2, 0),
        IrButton("fan_dn", "Fan -",  "💨", 2, 2),
        IrButton("mode",   "Mode",   "↻",  3, 1),
    )),
    IrDevice("projector","Проектор",   "📽", listOf(
        IrButton("pwr",  "Power", "⏻", 0, 1),
        IrButton("up",   "↑",    "",  1, 1),
        IrButton("left", "←",    "",  2, 0),
        IrButton("ok",   "OK",   "",  2, 1),
        IrButton("right","→",    "",  2, 2),
        IrButton("down", "↓",    "",  3, 1),
        IrButton("src",  "Source","⎘", 4, 0),
        IrButton("menu", "Menu", "≡", 4, 2),
    )),
)

// ─── IR Screen ────────────────────────────────────────────────────────────────

@Composable
fun IrScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedDevice by remember { mutableStateOf(irDevices[0]) }
    var lastPressed by remember { mutableStateOf<String?>(null) }
    var isLearning by remember { mutableStateOf(false) }
    var learnedButtons by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(FlipperTheme.bg)
            .padding(16.dp)
    ) {
        TopBar(title = "INFRARED", color = FlipperTheme.red, onBack = onBack)

        // Device selector
        Text("УСТРОЙСТВО", color = FlipperTheme.textSecondary, fontSize = 10.sp,
             fontFamily = FlipperTheme.mono, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(irDevices) { device ->
                val selected = device.id == selectedDevice.id
                Box(
                    Modifier
                        .clickable { selectedDevice = device }
                        .border(1.dp,
                            if (selected) FlipperTheme.red.copy(alpha = 0.6f) else FlipperTheme.border,
                            RoundedCornerShape(10.dp))
                        .background(
                            if (selected) FlipperTheme.redDim else FlipperTheme.surface,
                            RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.material3.Text(device.icon, fontSize = 22.sp)
                        Spacer(Modifier.height(2.dp))
                        androidx.compose.material3.Text(
                            device.name,
                            color = if (selected) FlipperTheme.red else FlipperTheme.textSecondary,
                            fontSize = 10.sp, fontFamily = FlipperTheme.mono
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Статус
        lastPressed?.let {
            Box(Modifier.fillMaxWidth()
                .background(FlipperTheme.redDim, RoundedCornerShape(8.dp))
                .padding(10.dp)) {
                androidx.compose.material3.Text("↳ $it",
                    color = FlipperTheme.red, fontSize = 12.sp, fontFamily = FlipperTheme.mono)
            }
            Spacer(Modifier.height(10.dp))
        }

        // Кнопки пульта
        IrRemoteGrid(
            device = selectedDevice,
            learnedButtons = learnedButtons,
            isLearning = isLearning,
            onButtonPress = { button ->
                scope.launch {
                    lastPressed = "${selectedDevice.name} → ${button.label}"
                    if (!isLearning) {
                        session.irTransmit("${selectedDevice.id}/${button.id}")
                    }
                    delay(200)
                }
            }
        )

        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = FlipperTheme.border, modifier = Modifier.padding(vertical = 12.dp))

        // Learn mode
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                label = if (isLearning) "⏹ СТОП LEARN" else "⚡ LEARN MODE",
                color = FlipperTheme.yellow,
                modifier = Modifier.weight(1f)
            ) { isLearning = !isLearning }

            ActionButton(
                label = "📁 ФАЙЛЫ IR",
                color = FlipperTheme.textSecondary,
                modifier = Modifier.weight(1f)
            ) { /* открыть файловый браузер */ }
        }
    }
}

// ─── Remote grid ──────────────────────────────────────────────────────────────

@Composable
fun IrRemoteGrid(
    device: IrDevice,
    learnedButtons: Map<String, Boolean>,
    isLearning: Boolean,
    onButtonPress: (IrButton) -> Unit
) {
    val rows = device.buttons.groupBy { it.row }.toSortedMap()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (_, buttonsInRow) ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Заполняем сетку 3 колонки
                val grid = Array(3) { col -> buttonsInRow.find { it.col == col } }
                grid.forEach { btn ->
                    if (btn != null) {
                        IrKey(
                            button = btn,
                            isLearning = isLearning,
                            isLearned = learnedButtons[btn.id] == true,
                            modifier = Modifier.weight(1f),
                            onClick = { onButtonPress(btn) }
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun IrKey(
    button: IrButton,
    isLearning: Boolean,
    isLearned: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val color = when {
        isLearning && isLearned -> FlipperTheme.yellow
        button.id == "pwr"      -> FlipperTheme.red
        button.id == "ok"       -> FlipperTheme.green
        else                    -> FlipperTheme.textPrimary
    }

    Box(
        modifier
            .clickable(onClick = onClick)
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .background(FlipperTheme.surface, RoundedCornerShape(8.dp))
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Text(
            if (button.icon.isNotEmpty()) button.icon else button.label,
            color = color,
            fontSize = if (button.icon.isNotEmpty()) 18.sp else 13.sp,
            fontFamily = FlipperTheme.mono,
            fontWeight = FontWeight.Bold
        )
    }
}
