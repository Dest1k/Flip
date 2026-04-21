package com.flippercontrol.core

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

// ─── Foreground Service ────────────────────────────────────────────────────────
// Держит BLE соединение живым в фоне.
// Активности биндятся к нему через FlipperBinder.

class FlipperService : Service() {

    inner class FlipperBinder : Binder() {
        fun getSession(): FlipperRpcSession? = session
        fun getBleState(): StateFlow<BleState> = ble.state
        fun getBle(): FlipperBleManager = ble
    }

    private val binder = FlipperBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    lateinit var ble: FlipperBleManager
        private set

    var session: FlipperRpcSession? = null
        private set

    companion object {
        const val CHANNEL_ID = "flipper_connection"
        const val NOTIF_ID   = 1001
        const val ACTION_DISCONNECT = "com.flippercontrol.DISCONNECT"
    }

    override fun onCreate() {
        super.onCreate()
        ble = FlipperBleManager(applicationContext)

        // Следим за подключением — создаём RPC сессию когда connected
        scope.launch {
            ble.state.collect { state ->
                when (state) {
                    is BleState.Connected -> {
                        session = FlipperRpcSession(ble)
                        updateNotification("Подключено: ${state.name}")
                        val ok = session?.ping() ?: false
                        ble.logPublic(if (ok) "Ping OK — RPC сессия активна" else "Ping failed — нет ответа от Flipper")
                    }
                    is BleState.Disconnected -> {
                        session?.stop()
                        session = null
                        updateNotification("Не подключено")
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
