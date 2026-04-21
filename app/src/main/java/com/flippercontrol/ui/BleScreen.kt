package com.flippercontrol.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.flippercontrol.core.FlipperRpcSession
import kotlinx.coroutines.launch

// ─── BLE устройства (для спама) ───────────────────────────────────────────────

data class BleSpamTarget(
    val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val color: Color,
)

val bleSpamTargets = listOf(
    BleSpamTarget("airpods_pro",  "AirPods Pro",   "🎧", "Proximity pairing 0x0E20",   Color(0xFFE8E8E8)),
    BleSpamTarget("airpods_max",  "AirPods Max",   "🎧", "Proximity pairing 0x0A20",   Color(0xFFE8E8E8)),
    BleSpamTarget("apple_watch",  "Apple Watch",   "⌚", "Proximity pairing 0x0255",   Color(0xFF00BFFF)),
    BleSpamTarget("apple_tv",     "Apple TV",      "📺", "Nearby Info 0x10",            Color(0xFF888888)),
    BleSpamTarget("samsung_buds", "Samsung Buds",  "🎵", "Samsung 0x0075",              Color(0xFF1428A0)),
    BleSpamTarget("ms_swift",     "MS Swift Pair", "🖥", "Microsoft 0x0006",            Color(0xFF00A4EF)),
)

data class ScannedDevice(
    val mac: String,
    val name: String?,
    val rssi: Int,
    val company: String?,
    val seenAt: String,
)

// ─── BLE Screen ───────────────────────────────────────────────────────────────

@Composable
fun BleScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var spamTarget by remember { mutableStateOf(bleSpamTargets[0]) }
    var isSpamming by remember { mutableStateOf(false) }
    var pktCount by remember { mutableLongStateOf(0L) }
    var scannedDevices by remember { mutableStateOf<List<ScannedDevice>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(FlipperTheme.bg)
            .padding(16.dp)
    ) {
        TopBar(title = "BLUETOOTH", color = FlipperTheme.purple, onBack = onBack)

        // Tabs
        Row(
            Modifier.fillMaxWidth().background(FlipperTheme.surface, RoundedCornerShape(10.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("BLE SPAM", "СКАНЕР").forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).clickable { tab = i }
                        .background(if (i == tab) FlipperTheme.purpleDim else Color.Transparent, RoundedCornerShape(8.dp))
                        .border(if (i == tab) 1.dp else 0.dp, FlipperTheme.purple.copy(0.4f), RoundedCornerShape(8.dp))
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Text(label,
                        color = if (i == tab) FlipperTheme.purple else FlipperTheme.textSecondary,
                        fontSize = 11.sp, fontFamily = FlipperTheme.mono,
                        fontWeight = if (i == tab) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        when (tab) {
            0 -> BleSpamTab(
                targets = bleSpamTargets,
                selected = spamTarget,
                onSelect = { spamTarget = it },
                isSpamming = isSpamming,
                pktCount = pktCount,
                onToggle = {
                    isSpamming = !isSpamming
                    if (isSpamming) {
                        scope.launch {
                            while (isSpamming) {
                                // session.bleSpam(spamTarget.id)
                                pktCount++
                                kotlinx.coroutines.delay(30)
                            }
                        }
                    }
                }
            )
            1 -> BleScannerTab(
                devices = scannedDevices,
                isScanning = isScanning,
                onToggle = {
                    isScanning = !isScanning
                    if (isScanning) {
                        scope.launch {
                            // Mock: в реальности парсим события от Flipper BLE scanner
                            repeat(5) {
                                kotlinx.coroutines.delay(800)
                                scannedDevices = listOf(
                                    ScannedDevice(
                                        mac = buildString { repeat(6) { append("%02X".format((0..255).random())); if (it < 5) append(":") } },
                                        name = listOf("JBL Flip 6", "Mi Band 7", "Galaxy Buds", null, "iPhone").random(),
                                        rssi = (-90..-40).random(),
                                        company = listOf("Apple Inc.", "Samsung", "Xiaomi", null).random(),
                                        seenAt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                                    )
                                ) + scannedDevices
                            }
                            isScanning = false
                        }
                    }
                }
            )
        }
    }
}

// ─── Spam tab ─────────────────────────────────────────────────────────────────

@Composable
fun BleSpamTab(
    targets: List<BleSpamTarget>,
    selected: BleSpamTarget,
    onSelect: (BleSpamTarget) -> Unit,
    isSpamming: Boolean,
    pktCount: Long,
    onToggle: () -> Unit
) {
    Column {
        Text("ЦЕЛЬ", color = FlipperTheme.textSecondary, fontSize = 10.sp,
             fontFamily = FlipperTheme.mono, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))

        // Target grid 2 columns
        targets.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { target ->
                    val sel = target.id == selected.id
                    Box(
                        Modifier.weight(1f).clickable { onSelect(target) }
                            .border(1.dp, if (sel) FlipperTheme.purple.copy(0.7f) else FlipperTheme.border, RoundedCornerShape(10.dp))
                            .background(if (sel) FlipperTheme.purpleDim else FlipperTheme.surface, RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            androidx.compose.material3.Text(target.icon, fontSize = 20.sp)
                            Spacer(Modifier.height(4.dp))
                            androidx.compose.material3.Text(target.name,
                                color = if (sel) FlipperTheme.purple else FlipperTheme.textPrimary,
                                fontSize = 11.sp, fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold)
                            androidx.compose.material3.Text(target.description,
                                color = FlipperTheme.textSecondary, fontSize = 9.sp, fontFamily = FlipperTheme.mono)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(8.dp))

        // Counter
        if (isSpamming || pktCount > 0) {
            Box(Modifier.fillMaxWidth().background(FlipperTheme.purpleDim, RoundedCornerShape(8.dp)).padding(12.dp)) {
                androidx.compose.material3.Text(
                    "Отправлено пакетов: $pktCount",
                    color = FlipperTheme.purple, fontSize = 13.sp, fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        ActionButton(
            label = if (isSpamming) "⏹ СТОП SPAM" else "📡 НАЧАТЬ SPAM",
            color = FlipperTheme.purple,
            modifier = Modifier.fillMaxWidth(),
            onClick = onToggle
        )
    }
}

// ─── Scanner tab ──────────────────────────────────────────────────────────────

@Composable
fun BleScannerTab(
    devices: List<ScannedDevice>,
    isScanning: Boolean,
    onToggle: () -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Text(
                "НАЙДЕНО: ${devices.size}",
                color = FlipperTheme.textSecondary, fontSize = 10.sp,
                fontFamily = FlipperTheme.mono, letterSpacing = 2.sp
            )
            if (isScanning) AnimatedReceivingBadge()
        }

        Spacer(Modifier.height(8.dp))

        ActionButton(
            label = if (isScanning) "⏹ СТОП" else "🔍 СКАНИРОВАТЬ",
            color = FlipperTheme.purple,
            modifier = Modifier.fillMaxWidth(),
            onClick = onToggle
        )

        Spacer(Modifier.height(12.dp))

        if (devices.isEmpty()) {
            EmptyState("Нажми СКАНИРОВАТЬ.\nFlipper начнёт поиск BLE устройств вокруг.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(devices) { dev ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(FlipperTheme.surface, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            androidx.compose.material3.Text(
                                dev.name ?: "Unknown",
                                color = if (dev.name != null) FlipperTheme.purple else FlipperTheme.textSecondary,
                                fontSize = 13.sp, fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold
                            )
                            androidx.compose.material3.Text(
                                "${dev.mac} · ${dev.company ?: "?"} · ${dev.seenAt}",
                                color = FlipperTheme.textSecondary, fontSize = 10.sp, fontFamily = FlipperTheme.mono
                            )
                        }
                        // RSSI bar
                        val rssiColor = when {
                            dev.rssi > -60 -> FlipperTheme.green
                            dev.rssi > -75 -> FlipperTheme.yellow
                            else           -> FlipperTheme.red
                        }
                        androidx.compose.material3.Text(
                            "${dev.rssi}",
                            color = rssiColor, fontSize = 12.sp,
                            fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
