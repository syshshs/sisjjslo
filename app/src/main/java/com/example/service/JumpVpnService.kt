package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.core.XrayCoreRuntime
import com.example.model.ConnectionStats
import com.example.model.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class JumpVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var statsJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default)

    companion object {
        private const val TAG = "ReNoVpnService"
        const val ACTION_CONNECT = "com.example.jumpvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.example.jumpvpn.DISCONNECT"
        const val EXTRA_SERVER_NAME = "extra_server_name"
        const val EXTRA_SERVER_IP = "extra_server_ip"
        const val EXTRA_DNS_IP = "extra_dns_ip"
        const val EXTRA_RAW_CONFIG = "extra_raw_config"
        const val MAX_SESSION_DURATION_SECONDS = 3L * 3600L
        private const val CHANNEL_ID = "reno_vpn_status_channel"
        private const val NOTIFICATION_ID = 1001

        private val _vpnState = MutableStateFlow(VpnState.DISCONNECTED)
        val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()
        private val _connectionStats = MutableStateFlow(ConnectionStats())
        val connectionStats: StateFlow<ConnectionStats> = _connectionStats.asStateFlow()
        private val _sessionEvent = MutableSharedFlow<String>(extraBufferCapacity = 5)
        val sessionEvent: SharedFlow<String> = _sessionEvent.asSharedFlow()

        fun startVpn(context: Context, serverName: String, serverIp: String, dnsIp: String = "1.1.1.1", rawConfig: String) {
            val intent = Intent(context, JumpVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_SERVER_NAME, serverName)
                putExtra(EXTRA_SERVER_IP, serverIp)
                putExtra(EXTRA_DNS_IP, dnsIp)
                putExtra(EXTRA_RAW_CONFIG, rawConfig)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "start service failed", e)
                _vpnState.value = VpnState.DISCONNECTED
            }
        }

        fun stopVpn(context: Context) {
            try {
                context.startService(Intent(context, JumpVpnService::class.java).apply { action = ACTION_DISCONNECT })
            } catch (e: Exception) {
                Log.e(TAG, "stop service failed", e)
                _vpnState.value = VpnState.DISCONNECTED
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        safeStartForeground("ReNo VPN", "در حال آماده‌سازی هسته VPN...")
        when (intent?.action) {
            ACTION_CONNECT -> connectVpn(
                intent.getStringExtra(EXTRA_SERVER_NAME) ?: "VPN",
                intent.getStringExtra(EXTRA_SERVER_IP) ?: "",
                intent.getStringExtra(EXTRA_DNS_IP) ?: "1.1.1.1",
                intent.getStringExtra(EXTRA_RAW_CONFIG)?.trim().orEmpty()
            )
            ACTION_DISCONNECT -> disconnectVpn("قطع اتصال توسط کاربر")
        }
        return START_NOT_STICKY
    }

    private fun connectVpn(serverName: String, serverIp: String, dnsIp: String, rawConfig: String) {
        if (rawConfig.isBlank()) {
            _sessionEvent.tryEmit("کانفیگ خالی است.")
            failAndStop()
            return
        }
        if (_vpnState.value == VpnState.CONNECTED || _vpnState.value == VpnState.CONNECTING) return

        _vpnState.value = VpnState.CONNECTING
        updateNotification("ReNo VPN: در حال اتصال...", "راه‌اندازی Xray-core")
        serviceScope.launch {
            try {
                val builder = Builder()
                    .setSession("ReNo VPN - $serverName")
                    .setMtu(1500)
                    .addAddress("10.0.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(dnsIp)

                val tun = builder.establish()
                if (tun == null) throw IllegalStateException("Android TUN ساخته نشد")
                vpnInterface = tun

                val result = XrayCoreRuntime.start(this@JumpVpnService, tun.fd, rawConfig, dnsIp)
                if (!result.success) {
                    throw IllegalStateException(result.error ?: "Xray-core اجرا نشد")
                }

                _vpnState.value = VpnState.CONNECTED
                updateNotification("ReNo VPN: متصل ✓", "Xray-core فعال است؛ سرور $serverName")
                startStatsEngine(serverIp, dnsIp, serverName)
            } catch (e: Throwable) {
                Log.e(TAG, "connect failed", e)
                _sessionEvent.tryEmit("اتصال برقرار نشد: ${e.message ?: "خطای Xray-core"}")
                failAndStop()
            }
        }
    }

    private fun startStatsEngine(serverIp: String, dnsIp: String, serverName: String) {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            var duration = 0L
            var lastRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            var lastTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
            var totalDown = 0L
            var totalUp = 0L

            while (isActive && _vpnState.value == VpnState.CONNECTED) {
                delay(1000)
                duration++
                if (duration >= MAX_SESSION_DURATION_SECONDS) {
                    _sessionEvent.emit("مدت نشست ۳ ساعته تمام شد.")
                    disconnectVpn("پایان نشست")
                    break
                }

                val rx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
                val tx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
                val down = (rx - lastRx).coerceAtLeast(0L)
                val up = (tx - lastTx).coerceAtLeast(0L)
                lastRx = rx
                lastTx = tx
                totalDown += down
                totalUp += up

                _connectionStats.value = ConnectionStats(
                    uploadSpeedBps = up,
                    downloadSpeedBps = down,
                    totalUploadedBytes = totalUp,
                    totalDownloadedBytes = totalDown,
                    durationSeconds = duration,
                    currentPingMs = 0,
                    virtualIp = serverIp,
                    dnsServer = dnsIp
                )
                if (duration % 60L == 0L) {
                    val remaining = MAX_SESSION_DURATION_SECONDS - duration
                    updateNotification("ReNo VPN: متصل به $serverName", "باقی‌مانده نشست: ${remaining / 3600}س ${remaining % 3600 / 60}د")
                }
            }
        }
    }

    private fun disconnectVpn(reason: String) {
        _vpnState.value = VpnState.DISCONNECTING
        statsJob?.cancel()
        serviceScope.launch {
            try { XrayCoreRuntime.stop() } catch (e: Throwable) { Log.w(TAG, "core stop: ${e.message}") }
            try { vpnInterface?.close() } catch (e: Throwable) { Log.w(TAG, "tun close: ${e.message}") }
            vpnInterface = null
            _vpnState.value = VpnState.DISCONNECTED
            _connectionStats.value = ConnectionStats()
            safeStopForeground()
            stopSelf()
        }
    }

    private fun failAndStop() {
        try { XrayCoreRuntime.stop() } catch (_: Throwable) {}
        try { vpnInterface?.close() } catch (_: Throwable) {}
        vpnInterface = null
        _vpnState.value = VpnState.DISCONNECTED
        _connectionStats.value = ConnectionStats()
        safeStopForeground()
        stopSelf()
    }

    override fun onRevoke() {
        disconnectVpn("مجوز VPN لغو شد")
        super.onRevoke()
    }

    override fun onDestroy() {
        statsJob?.cancel()
        try { XrayCoreRuntime.stop() } catch (_: Throwable) {}
        try { vpnInterface?.close() } catch (_: Throwable) {}
        vpnInterface = null
        _vpnState.value = VpnState.DISCONNECTED
        super.onDestroy()
    }

    private fun safeStartForeground(title: String, content: String) {
        try { startForeground(NOTIFICATION_ID, buildNotification(title, content)) }
        catch (e: Exception) { Log.w(TAG, "foreground: ${e.message}") }
    }

    private fun safeStopForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Throwable) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ReNo VPN Service", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openPending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPending = PendingIntent.getService(
            this, 1,
            Intent(this, JumpVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title).setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_lock).setContentIntent(openPending)
            .setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel, "قطع اتصال", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private fun updateNotification(title: String, content: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(NOTIFICATION_ID, buildNotification(title, content))
    }
}
