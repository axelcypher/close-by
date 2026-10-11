package net.pendzialek.closeby.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.pendzialek.closeby.CloseByApp
import net.pendzialek.closeby.data.AppMode
import net.pendzialek.closeby.frame.FrameService
import net.pendzialek.closeby.ui.MainActivity

/**
 * Brings a frame back after a reboot or an app update. As home screen it would
 * come back anyway; this also covers a frame that is not the default launcher.
 */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CloseByApp
        if (app.mode.value != AppMode.FRAME) return
        FrameService.start(context)
        // background activity starts are allowed for device owners; elsewhere this is a no-op
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
