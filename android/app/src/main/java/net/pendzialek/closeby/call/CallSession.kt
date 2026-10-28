package net.pendzialek.closeby.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.pendzialek.closeby.api.CallJoin
import net.pendzialek.closeby.data.Api

/** The call can't go on without the microphone. */
class MicrophoneNotAllowed : Exception("microphone not allowed")

/**
 * One LiveKit room at a time. Exposes the remote and local video tracks for
 * the UI and reports when the other side leaves without hanging up.
 */
class CallSession(private val context: Context, private val api: Api) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _room = MutableStateFlow<Room?>(null)
    private var room: Room?
        get() = _room.value
        set(value) {
            _room.value = value
        }
    private var eventsJob: Job? = null
    private var peerLeftJob: Job? = null

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo

    private val _localVideo = MutableStateFlow<LocalVideoTrack?>(null)
    val localVideo: StateFlow<LocalVideoTrack?> = _localVideo

    /** Called when the remote participant disappears (crash, lost network) or never shows up. */
    var onPeerLeft: () -> Unit = {}

    /** The other side is in the room, i.e. it answered, even if its call.accepted got lost. */
    var onPeerJoined: () -> Unit = {}

    /** LiveKit gave up on the room (closed by the server, network gone for good). */
    var onDisconnected: () -> Unit = {}

    /** Set once the call is answered: from then on the other side has to show up. */
    private var peerExpected = false

    /** The room [join] is still setting up; it releases that one itself, see [leave]. */
    private var joining: Room? = null

    /** Observable: whoever accepts a call shows the call screen before the room exists. */
    val currentRoom: StateFlow<Room?> = _room

    /**
     * Joins the call's room. [leave] may come while this suspends (hung up meanwhile):
     * then the room is dropped without starting camera or microphone (or right after the
     * step that was running). [peerExpected]: the call is answered already, so the other
     * side should be in the room.
     */
    suspend fun join(join: CallJoin, volumePercent: Int?, peerExpected: Boolean = false) {
        leave()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw MicrophoneNotAllowed()
        }
        this.peerExpected = peerExpected
        // a frame has no earpiece worth using; phones in frame mode must not pick it either
        val audioHandler = AudioSwitchHandler(context).apply {
            preferredDeviceList = listOf(
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
                AudioDevice.Speakerphone::class.java,
                AudioDevice.Earpiece::class.java,
            )
        }
        val newRoom = LiveKit.create(
            appContext = context,
            options = RoomOptions(adaptiveStream = true, dynacast = true),
            overrides = LiveKitOverrides(okHttpClient = api.client, audioOptions = AudioOptions(audioHandler = audioHandler)),
        )
        room = newRoom
        eventsJob = scope.launch {
            newRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.TrackSubscribed -> (event.track as? VideoTrack)?.let { _remoteVideo.value = it }
                    is RoomEvent.TrackUnsubscribed -> if (event.track == _remoteVideo.value) _remoteVideo.value = null
                    is RoomEvent.ParticipantDisconnected -> peerGone(PEER_GRACE_MS)
                    is RoomEvent.ParticipantConnected -> {
                        peerLeftJob?.cancel()
                        onPeerJoined()
                    }
                    is RoomEvent.Disconnected -> if (room === newRoom) onDisconnected()
                    else -> Unit
                }
            }
        }
        joining = newRoom
        try {
            newRoom.connect(join.livekitUrl, join.token)
            if (room !== newRoom) return
            newRoom.localParticipant.setMicrophoneEnabled(true)
            if (room !== newRoom) return
            // without the camera permission the call still works, with sound only
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                newRoom.localParticipant.setCameraEnabled(true)
                if (room !== newRoom) return
                _localVideo.value = newRoom.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
            }
            volumePercent?.let(::setCallVolume)
            if (this.peerExpected && newRoom.remoteParticipants.isEmpty()) peerGone(PEER_JOIN_MS)
        } finally {
            if (joining === newRoom) joining = null
            // left while joining: [leave] skipped this room, so it goes now, with whatever it started
            if (room !== newRoom) {
                newRoom.disconnect()
                newRoom.release()
            }
        }
    }

    /** The call was answered: if the other side is not in the room within a while, it is gone. */
    fun expectPeer() {
        peerExpected = true
        if (room?.remoteParticipants.isNullOrEmpty()) peerGone(PEER_JOIN_MS)
    }

    /** A dead spot on the train should not end the call: give the other side time to come back. */
    private fun peerGone(graceMs: Long) {
        peerLeftJob?.cancel()
        peerLeftJob = scope.launch {
            delay(graceMs)
            if (room?.remoteParticipants.isNullOrEmpty()) onPeerLeft()
        }
    }

    fun leave() {
        peerExpected = false
        peerLeftJob?.cancel()
        eventsJob?.cancel()
        eventsJob = null
        _remoteVideo.value = null
        _localVideo.value = null
        val current = room
        room = null
        // a room that join() is still busy with is released there, once its current step returns
        if (current != null && current !== joining) {
            current.disconnect()
            current.release()
        }
    }

    private fun setCallVolume(percent: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (max * percent.coerceIn(0, 100) / 100).coerceAtLeast(1), 0)
    }

    private companion object {
        const val PEER_GRACE_MS = 15_000L
        /** A slow tablet needs a moment to join after answering. */
        const val PEER_JOIN_MS = 30_000L
    }
}
