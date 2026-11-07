package com.botghost.smsgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Foreground service: poll the relay worker, send each queued SMS from the
 * default SIM, and ack the result. Settings are re-read every cycle so edits
 * in the UI apply without restarting the service.
 */
class GatewayService : Service() {

    companion object {
        const val ACTION_START = "com.botghost.smsgateway.START"
        const val ACTION_STOP = "com.botghost.smsgateway.STOP"
        private const val CHANNEL_ID = "gateway"
        private const val NOTIF_ID = 1
        private const val TAG = "GatewayService"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, GatewayService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, GatewayService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Gateway status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows the SMS gateway polling status"
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground("Starting…")
        if (!GatewayRuntime.running.value) {
            GatewayRuntime.running.value = true
            GatewayRuntime.lastError.value = null
            scope.launch { runLoop() }
        }
        return START_STICKY
    }

    private fun goForeground(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, GatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("BotGhost SMS Gateway")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOnlyAlertOnce(true)
            .build()
    }

    private suspend fun runLoop() {
        val sms = SmsSender(this)
        val sendTimes = ArrayDeque<Long>()

        var wakeLock: PowerManager.WakeLock? = null
        try {
            while (true) {
                val settings = GatewaySettings.load(this)
                val api = GatewayApi(settings.serverUrl, settings.deviceId, settings.apiKey)

                if (settings.keepAwake && wakeLock == null) {
                    wakeLock = getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "botghost_sms:gateway")
                        .apply { setReferenceCounted(false); acquire() }
                } else if (!settings.keepAwake && wakeLock != null) {
                    wakeLock.release(); wakeLock = null
                }

                try {
                    runCatching { api.heartbeat() } // presence ping; failures surface via poll
                    val messages = api.poll()
                    for (m in messages) {
                        awaitSendSlot(sendTimes, settings.maxPerMinute)
                        val result = if (settings.dryRun) {
                            SmsSender.Result.Sent
                        } else {
                            runCatching { sms.send(m.to, m.message) }.getOrElse {
                                SmsSender.Result.Failed(it.message ?: it.javaClass.simpleName)
                            }
                        }
                        val ok = result is SmsSender.Result.Sent
                        val err = (result as? SmsSender.Result.Failed)?.reason
                        runCatching { api.ack(m.id, ok, err) }
                            .onFailure { EventLog.add(this, "error", m.to, "ack failed: ${it.message}") }
                        if (ok) {
                            GatewayRuntime.sentCount.value++
                            EventLog.add(this, "sent", m.to, m.message.take(60))
                        } else {
                            GatewayRuntime.failedCount.value++
                            EventLog.add(this, "failed", m.to, err ?: "unknown error")
                        }
                        Log.i(TAG, "message id=${m.id} to=${m.to} -> ${if (ok) "sent" else "failed: $err"}")
                    }
                    val nowText = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                    GatewayRuntime.lastPoll.value = nowText
                    GatewayRuntime.lastError.value = null
                    goForeground("Sent ${GatewayRuntime.sentCount.value} • Failed ${GatewayRuntime.failedCount.value} • Last check $nowText")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val msg = e.message ?: e.javaClass.simpleName
                    GatewayRuntime.lastError.value = msg
                    EventLog.add(this, "error", null, "poll failed: $msg")
                    Log.w(TAG, "poll failed", e)
                    goForeground("Error: $msg")
                }
                delay(GatewaySettings.load(this).pollSeconds.coerceIn(3, 300) * 1000L)
            }
        } finally {
            wakeLock?.let { if (it.isHeld) it.release() }
            GatewayRuntime.running.value = false
        }
    }

    /** Blocks until a send slot is free, enforcing the max-per-minute rate limit. */
    private suspend fun awaitSendSlot(sendTimes: ArrayDeque<Long>, maxPerMinute: Int) {
        while (true) {
            val now = System.currentTimeMillis()
            while (sendTimes.isNotEmpty() && now - sendTimes.first() >= 60_000) sendTimes.removeFirst()
            if (sendTimes.size < maxPerMinute) {
                sendTimes.addLast(System.currentTimeMillis())
                return
            }
            delay((60_000 - (now - sendTimes.first())).coerceIn(1_000, 60_000))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        GatewayRuntime.running.value = false
    }
}
