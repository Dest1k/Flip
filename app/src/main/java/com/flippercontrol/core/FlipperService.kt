package com.flippercontrol.core

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// ─── Foreground Service ────────────────────────────────────────────────────────
// Держит BLE соединение живым в фоне.
// Активности биндятся к нему через FlipperBinder.

class FlipperService : Service() {

    inner class FlipperBinder : Binder() {
        fun getSession(): FlipperRpcSession? = session
        fun getBleState(): StateFlow<BleState> = ble.state
        fun getBle(): FlipperBleManager = ble
        fun getDeviceInfo(): StateFlow<Map<String, String>> = _deviceInfo.asStateFlow()
    }

    private val binder = FlipperBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    lateinit var ble: FlipperBleManager
        private set

    var session: FlipperRpcSession? = null
        private set

    private val _deviceInfo = MutableStateFlow<Map<String, String>>(emptyMap())

    companion object {
        const val CHANNEL_ID = "flipper_connection"
        const val NOTIF_ID   = 1001
        const val ACTION_DISCONNECT = "com.flippercontrol.DISCONNECT"
    }

    override fun onCreate() {
        super.onCreate()
        ble = FlipperBleManager(applicationContext)

        scope.launch {
            ble.state.collect { state ->
                when (state) {
                    is BleState.Connected -> {
                        session = FlipperRpcSession(ble)
                        updateNotification("Подключено: ${state.name}")
                        // Give Flipper RPC daemon time to activate after CCCD write
                        delay(1000)
                        var pingOk = false
                        repeat(3) { attempt ->
                            if (!pingOk) {
                                val ok = session?.ping() ?: false
                                ble.logPublic("Ping #${attempt + 1}: ${if (ok) "OK" else "нет ответа"}")
                                if (ok) pingOk = true else delay(2000)
                            }
                        }
                        if (pingOk) {
                            _deviceInfo.value = session?.deviceInfo() ?: emptyMap()
                        } else {
                            ble.logPublic("Ping failed после 3 попыток — RPC недоступен")
                        }
                    }
                    is BleState.Disconnected -> {
                        session?.stop()
                        session = null
                        _deviceInfo.value = emptyMap()
                        updateNotification("Не подключено")
                    }
                    is BleState.Error -> {
                        session?.stop()
                        session = null
                        _deviceInfo.value = emptyMap()
                        updateNotification("Ошибка подключения")
                    }
                    else -> {}
                }
            }
        }

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Ожидание Flipper Zero..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            ble.disconnect()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        session?.stop()
        ble.disconnect()
        super.onDestroy()
    }

    // ─── Notification ────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Flipper Zero Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Фоновое соединение с Flipper Zero" }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val disconnectIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FlipperService::class.java).apply {
                action = ACTION_DISCONNECT
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Flipper Control")
            .setContentText(text)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", disconnectIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
