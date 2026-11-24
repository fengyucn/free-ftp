package com.freeftp.app.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.freeftp.app.FreeFtpApp
import com.freeftp.core.transfer.TransferKeepAlivePolicy
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A foreground service that keeps the process alive for as long as the transfer queue
 * has work.
 *
 * Without it, leaving the app demotes the process to the cached state and Android
 * freezes it within seconds, which silently kills the sockets under a running transfer.
 * While this service is foreground the process keeps full priority, and a partial wake
 * lock keeps the CPU from sleeping between reads so a slow server cannot stall a
 * download with the screen off.
 *
 * Lifecycle: [FreeFtpApp] starts the service when the queue gains active work; the
 * service then watches the queue itself and stops a short grace period after it goes
 * idle — the grace rides out a pause/resume or a rapid follow-up enqueue without a
 * stop/start cycle.
 */
class TransferForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transfers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of file transfers running in the background"
                setShowBadge(false)
            },
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "freeftp:transfer")
            .apply {
                setReferenceCounted(false)
                // No timeout: the lock's lifetime is exactly this service's lifetime,
                // and onDestroy always runs before the service goes away.
                acquire()
            }
        observeQueue()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(currentSnapshot()))
        // If the process dies mid-transfer the in-memory queue dies with it, so there
        // is nothing to restart; the next enqueue starts the service again.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    /**
     * Android 16+ (API 36) caps `dataSync` foreground services at six hours. Nothing
     * keeps a transfer alive past that, so stop cleanly and let the process return to
     * the cached state; the transfer then fails like any other dropped connection.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    private fun observeQueue() {
        val queue = (application as FreeFtpApp).container.session.transfers
        scope.launch {
            var pendingStop = 0
            queue.transfers.map { TransferKeepAlivePolicy.snapshot(it) }.collect { snapshot ->
                if (snapshot == null) {
                    val token = ++pendingStop
                    delay(IDLE_GRACE_MS)
                    if (token == pendingStop) stopSelf()
                } else {
                    pendingStop++ // any stop that is still waiting out its grace is stale
                    updateNotification(snapshot)
                }
            }
        }
    }

    private fun currentSnapshot(): TransferKeepAlivePolicy.Snapshot? =
        TransferKeepAlivePolicy.snapshot(
            (application as FreeFtpApp).container.session.transfers.transfers.value,
        )

    private fun updateNotification(snapshot: TransferKeepAlivePolicy.Snapshot) {
        // Progress ticks arrive far more often than a notification is worth redrawing.
        val now = System.currentTimeMillis()
        if (now - lastNotificationAt < NOTIFICATION_MIN_INTERVAL_MS) return
        lastNotificationAt = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(snapshot))
    }

    private fun buildNotification(snapshot: TransferKeepAlivePolicy.Snapshot?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle("Transferring")
        if (snapshot == null) {
            return builder.setContentText("Starting…").build()
        }
        builder.setContentText(describe(snapshot))
        if (snapshot.total > 0) {
            val percent = (snapshot.transferred * 100 / snapshot.total).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun describe(snapshot: TransferKeepAlivePolicy.Snapshot): String {
        val moved = "${bytes(snapshot.transferred)} of ${bytes(snapshot.total)}"
        val count = when (snapshot.transfersLeft) {
            1 -> "1 transfer"
            else -> "${snapshot.transfersLeft} transfers"
        }
        return if (snapshot.total > 0) "$moved · $count" else "${bytes(snapshot.transferred)} · $count"
    }

    private fun bytes(n: Long): String {
        if (n < 1024) return "$n B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = n.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    private companion object {
        const val CHANNEL_ID = "transfers"
        const val NOTIFICATION_ID = 1
        const val IDLE_GRACE_MS = 3_000L
        const val NOTIFICATION_MIN_INTERVAL_MS = 500L
    }
}
