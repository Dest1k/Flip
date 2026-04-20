package com.flippercontrol

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.flippercontrol.core.*
import com.flippercontrol.ui.*

class MainActivity : ComponentActivity() {

    private var flipperService: FlipperService? = null
    private val serviceState = mutableStateOf<FlipperService?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val b = binder as FlipperService.FlipperBinder
            flipperService = b.getSession()?.let { _ -> null }
            serviceState.value = (binder as FlipperService.FlipperBinder)
                .let { flipperService }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            flipperService = null
            serviceState.value = null
        }
    }

    private var flipperBinder: FlipperService.FlipperBinder? = null
    private val binderState = mutableStateOf<FlipperService.FlipperBinder?>(null)

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            flipperBinder = binder as FlipperService.FlipperBinder
            binderState.value = flipperBinder
        }
        override fun onServiceDisconnected(name: ComponentName) {
            flipperBinder = null
            binderState.value = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* обрабатываем в UI */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val perms = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        permissionLauncher.launch(perms)

        val serviceIntent = Intent(this, FlipperService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConn, Context.BIND_AUTO_CREATE)

        setContent {
            FlipperApp(binderState = binderState)
        }
    }

    override fun onDestroy() {
        unbindService(serviceConn)
        super.onDestroy()
    }
}

@Composable
fun FlipperApp(binderState: State<FlipperService.FlipperBinder?>) {
    val navController = rememberNavController()
    val binder by binderState

    val bleState by (binder?.getBleState()?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(BleState.Disconnected) })

    var deviceInfo by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(bleState) {
        if (bleState is BleState.Connected) {
            binder?.getSession()?.let { session ->
                deviceInfo = session.deviceInfo()
            }
        } else {
            deviceInfo = emptyMap()
        }
    }

    NavHost(navController = navController, startDestination = "dashboard") {

        composable("dashboard") {
            DashboardScreen(
                bleState   = bleState,
                deviceInfo = deviceInfo,
                onConnectClick = {
                    binder?.getBle()?.startScan { device, _ ->
                        binder?.getBle()?.connect(device)
                    }
                },
                onFeatureClick = { feature ->
                    navController.navigate(feature)
                }
            )
        }

        composable("subghz") {
            val session = binder?.getSession()
            if (session != null) {
                SubGhzScreen(session = session, onBack = { navController.popBackStack() })
            } else {
                NoConnectionScreen { navController.popBackStack() }
            }
        }

        composable("nfc") {
            val session = binder?.getSession()
            if (session != null) {
                NfcScreen(session = session, onBack = { navController.popBackStack() })
            } else {
                NoConnectionScreen { navController.popBackStack() }
            }
        }

        composable("rfid") {
            val session = binder?.getSession()
            if (session != null) {
                RfidScreen(session = session, onBack = { navController.popBackStack() })
            } else {
                NoConnectionScreen { navController.popBackStack() }
            }
        }

        composable("ir") {
            val session = binder?.getSession()
            if (session != null) {
                IrScreen(session = session, onBack = { navController.popBackStack() })
            } else {
                NoConnectionScreen { navController.popBackStack() }
            }
        }

        composable("ble") {
            val session = binder?.getSession()
            if (session != null) {
                BleScreen(session = session, onBack = { navController.popBackStack() })
            } else {
                NoConnectionScreen { navController.popBackStack() }
            }
        }

        composable("badusb") {
            val session = binder?.getSession()
            if (session != null) {
                BadUsbScreen(session = session, onBack = { navController.popBackStack() })
            } else {
                NoConnectionScreen { navController.popBackStack() }
            }
        }

        composable("gpio") {
            val session = binder?.getSession()
            if (session != null) {
                GpioScreen(session = session, onBack = { navController.popBackStack() })
            } else {
                NoConnectionScreen { navController.popBackStack() }
            }
        }

        composable("files") {
            val session = binder?.getSession()
            if (session != null) {
                FilesScreen(session = session, onBack = { navController.popBackStack() })
            } else {
                NoConnectionScreen { navController.popBackStack() }
            }
        }
    }
}

@Composable
fun NoConnectionScreen(onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(FlipperTheme.bg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            androidx.compose.material3.Text(
                "⚠ Flipper не подключён",
                color = FlipperTheme.accent,
                fontSize = 16.sp,
                fontFamily = FlipperTheme.mono
            )
            Spacer(Modifier.height(16.dp))
            ActionButton(
                label = "← НАЗАД",
                color = FlipperTheme.textSecondary,
                modifier = Modifier.width(160.dp),
                onClick = onBack
            )
        }
    }
}
