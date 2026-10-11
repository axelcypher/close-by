package net.pendzialek.closeby.contact

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import net.pendzialek.closeby.CloseByApp
import net.pendzialek.closeby.R
import net.pendzialek.closeby.ui.MainActivity

/**
 * The phone side of ringing: an insistent call notification with a full-screen
 * intent (shows over the lock screen) and the ongoing-call notification.
 */
object CallNotifications {
    private const val CHANNEL_INCOMING = "incoming_calls"
    private const val CHANNEL_ONGOING = "ongoing_calls"
    const val NOTIFICATION_INCOMING = 10
    const val NOTIFICATION_ONGOING = 11
    const val ACTION_ACCEPT = "net.pendzialek.closeby.ACCEPT"
    const val ACTION_DECLINE = "net.pendzialek.closeby.DECLINE"
    const val ACTION_HANG_UP = "net.pendzialek.closeby.HANG_UP"

    fun showIncoming(context: Context, name: String) {
        ensureChannels(context)
        val caller = Person.Builder().setName(name).setImportant(true).build()
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, broadcast(context, ACTION_DECLINE), activity(context, ACTION_ACCEPT)))
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(activity(context, null), true)
            .setOngoing(true)
            .setTimeoutAfter(60_000)
            .build()
        // keep ringing until answered, declined or taken elsewhere
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_INCOMING, notification)
    }

    fun clearIncoming(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_INCOMING)

    fun ongoing(context: Context, name: String): Notification {
        ensureChannels(context)
        val other = Person.Builder().setName(name).build()
        return NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(other, broadcast(context, ACTION_HANG_UP)))
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(activity(context, null))
            .setOngoing(true)
            .build()
    }

    private fun activity(context: Context, action: String?): PendingIntent = PendingIntent.getActivity(
        context, action.hashCode(),
        Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun broadcast(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(),
        Intent(context, CallActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING, "Eingehende Anrufe", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
                )
                enableVibration(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Laufende Anrufe", NotificationManager.IMPORTANCE_LOW),
        )
    }
}

/** Decline and hang up straight from the notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = (context.applicationContext as CloseByApp).contactController ?: return
        when (intent.action) {
            CallNotifications.ACTION_DECLINE -> controller.decline()
            CallNotifications.ACTION_HANG_UP -> controller.hangUp()
        }
    }
}

/**
 * Runs while a call is active so Android keeps camera and microphone
 * available when the screen turns off or another app comes to the front.
 */
class OngoingCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "CloseBy"
        val type = if (Build.VERSION.SDK_INT >= 30) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, CallNotifications.NOTIFICATION_ONGOING, CallNotifications.ongoing(this, name), type)
        return START_NOT_STICKY
    }

    companion object {
        private const val EXTRA_NAME = "name"

        fun start(context: Context, name: String) = ContextCompat.startForegroundService(
            context,
            Intent(context, OngoingCallService::class.java).putExtra(EXTRA_NAME, name),
        )

        fun stop(context: Context) {
            context.stopService(Intent(context, OngoingCallService::class.java))
        }
    }
}
