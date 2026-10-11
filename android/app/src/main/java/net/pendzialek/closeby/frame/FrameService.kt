package net.pendzialek.closeby.frame

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import net.pendzialek.closeby.CloseByApp
import net.pendzialek.closeby.R
import net.pendzialek.closeby.ui.MainActivity

/**
 * Keeps the frame process (and with it the websocket) alive while the screen
 * is off or another app is in front, and rings via a full-screen notification.
 */
class FrameService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels(this)
        val notification = NotificationCompat.Builder(this, CHANNEL_READY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("CloseBy")
            .setContentText("Bereit für Anrufe")
            .setOngoing(true)
            .setContentIntent(openApp(this))
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_READY, notification, type)
        (application as CloseByApp).ensureFrameRunning()
        return START_STICKY
    }

    companion object {
        private const val CHANNEL_READY = "frame"
        private const val CHANNEL_CALLS = "calls"
        private const val NOTIFICATION_READY = 1
        private const val NOTIFICATION_CALL = 2

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FrameService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FrameService::class.java))
        }

        /** Brings the frame to the front for an incoming call. */
        fun showIncoming(context: Context, name: String) {
            ensureChannels(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(name)
                .setContentText("ruft an")
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(openApp(context), true)
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .setTimeoutAfter(60_000)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_CALL, notification)
        }

        fun clearIncoming(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_CALL)
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_READY, "Bereitschaft", NotificationManager.IMPORTANCE_MIN),
            )
            manager.createNotificationChannel(
                // the frame plays its own ringtone; the channel only wakes the screen
                NotificationChannel(CHANNEL_CALLS, "Eingehende Anrufe", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                },
            )
        }
    }
}
