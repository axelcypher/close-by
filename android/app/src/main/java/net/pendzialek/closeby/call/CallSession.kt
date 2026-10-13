package net.pendzialek.closeby.call

import android.content.Context
import android.media.AudioManager
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

/**
 * One LiveKit room at a time. Exposes the remote and local video tracks for
 * the UI and reports when the other side leaves without hanging up.
 */
class CallSession(private val context: Context, private val api: Api) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var room: Room? = null
    private var eventsJob: Job? = null
    private var peerLeftJob: Job? = null

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo

    private val _localVideo = MutableStateFlow<LocalVideoTrack?>(null)
    val localVideo: StateFlow<LocalVideoTrack?> = _localVideo

    /** Called when the remote participant disappears (crash, lost network). */
    var onPeerLeft: () -> Unit = {}

    val currentRoom: Room? get() = room

    suspend fun join(join: CallJoin, volumePercent: Int?) {
        leave()
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
            // calls are one to one: the caller publishes while still alone in the room, and with dynacast its
            // video stayed paused for the callee; with a single subscriber neither optimization pays off
            options = RoomOptions(adaptiveStream = false, dynacast = false),
            overrides = LiveKitOverrides(okHttpClient = api.client, audioOptions = AudioOptions(audioHandler = audioHandler)),
        )
        room = newRoom
        eventsJob = scope.launch {
            newRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.TrackSubscribed -> (event.track as? VideoTrack)?.let { _remoteVideo.value = it }
                    is RoomEvent.TrackUnsubscribed -> if (event.track == _remoteVideo.value) _remoteVideo.value = null
                    is RoomEvent.ParticipantDisconnected -> peerGone()
                    is RoomEvent.ParticipantConnected -> peerLeftJob?.cancel()
                    else -> Unit
                }
            }
        }
        newRoom.connect(join.livekitUrl, join.token)
        newRoom.localParticipant.setMicrophoneEnabled(true)
        newRoom.localParticipant.setCameraEnabled(true)
        _localVideo.value = newRoom.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
        volumePercent?.let(::setCallVolume)
    }

    /** A dead spot on the train should not end the call: give the other side time to come back. */
    private fun peerGone() {
        peerLeftJob?.cancel()
        peerLeftJob = scope.launch {
            delay(PEER_GRACE_MS)
            if (room?.remoteParticipants.isNullOrEmpty()) onPeerLeft()
        }
    }

    fun leave() {
        peerLeftJob?.cancel()
        eventsJob?.cancel()
        eventsJob = null
        _remoteVideo.value = null
        _localVideo.value = null
        room?.disconnect()
        room?.release()
        room = null
    }

    private fun setCallVolume(percent: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (max * percent.coerceIn(0, 100) / 100).coerceAtLeast(1), 0)
    }

    private companion object {
        const val PEER_GRACE_MS = 15_000L
    }
}
