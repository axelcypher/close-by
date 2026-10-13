package net.pendzialek.closeby.call

import net.pendzialek.closeby.data.Images

/** Call state shared by the frame and the contact mode. */
sealed interface CallUi {
    data object None : CallUi

    /** [picture] shows the other side: a path from [Images.personPicture] or [Images.framePicture]. */
    data class Outgoing(
        val name: String,
        val picture: String? = null,
        val callId: String? = null,
    ) : CallUi

    data class Incoming(
        val callId: String,
        val name: String,
        val picture: String? = null,
        val autoAnswer: Boolean = false,
    ) : CallUi

    data class Active(val callId: String, val name: String) : CallUi
    data class Ended(val name: String, val message: String) : CallUi
}

/** What [CallScreen] needs from whoever runs the call. */
interface CallController {
    val callSession: CallSession
    val images: Images
    fun accept()
    fun decline()
    fun hangUp()
}
