package net.pendzialek.closeby.contact

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.pendzialek.closeby.CloseByApp
import net.pendzialek.closeby.data.AppMode

/** Rings the phone while CloseBy is in the background or not running at all. */
class PushService : FirebaseMessagingService() {
    private val app get() = application as CloseByApp

    override fun onNewToken(token: String) {
        if (app.mode.value == AppMode.CONTACT) app.contactController?.onPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (app.mode.value != AppMode.CONTACT) return
        // the controller lives on the main thread
        CoroutineScope(Dispatchers.Main).launch { app.contactController?.onPush(message.data) }
    }
}
