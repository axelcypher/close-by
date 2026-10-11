import { Room, RoomEvent, Track, type RemoteTrack } from "livekit-client";
import { api, storedToken, storeToken, type CallParty, type Me, type ServerEvent } from "./api";
import { ringer } from "./ringer";

type CallPhase =
  | { phase: "idle" }
  | { phase: "outgoing"; callId?: string; peer: string }
  | { phase: "incoming"; callId: string; peer: CallParty }
  | { phase: "active"; callId: string; peer: string }
  | { phase: "ended"; peer: string; message: string };

export const session = $state({
  me: null as Me | null,
  connected: false,
  /** Bumped on config.changed and after admin edits, so views reload. */
  revision: 0,
});

export const call = $state<{ state: CallPhase; remoteVideo: RemoteTrack | null; micOn: boolean; camOn: boolean }>({
  state: { phase: "idle" },
  remoteVideo: null,
  micOn: true,
  camOn: true,
});

let room: Room | null = null;
let socket: WebSocket | null = null;
let retryMs = 1000;
let endedTimer: ReturnType<typeof setTimeout> | undefined;
let peerTimer: ReturnType<typeof setTimeout> | undefined;

// ---- session ----

export async function loadMe() {
  session.me = await api.me();
}

/** Signs this device out on the server too, so the token is gone for good. */
export async function signOut() {
  await api.signOut().catch(() => {});
  logout();
}

export function logout() {
  storeToken(null);
  socket?.close();
  socket = null;
  session.me = null;
}

// ---- realtime ----

export function connectRealtime() {
  if (socket || !storedToken()) return;
  const ws = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/api/ws`);
  socket = ws;
  ws.onopen = () => ws.send(JSON.stringify({ type: "auth", token: storedToken() }));
  ws.onmessage = (message) => {
    const event = JSON.parse(message.data) as ServerEvent;
    if (event.type === "ready") {
      session.connected = true;
      retryMs = 1000;
    } else {
      handleEvent(event);
    }
  };
  ws.onclose = (close) => {
    socket = null;
    session.connected = false;
    if (close.code === 1008) {
      // the token was revoked
      logout();
      return;
    }
    setTimeout(connectRealtime, retryMs);
    retryMs = Math.min(retryMs * 2, 15_000);
  };
}

function handleEvent(event: ServerEvent) {
  const state = call.state;
  switch (event.type) {
    case "config.changed":
    case "presence.changed":
      session.revision++;
      break;
    case "call.incoming":
      if (state.phase !== "idle" && state.phase !== "ended") return; // busy; the server ends it after the timeout
      call.state = { phase: "incoming", callId: event.callId!, peer: event.from! };
      ringer.start();
      notifyIncoming(event.from!.name);
      break;
    case "call.accepted":
      if (state.phase === "outgoing" && state.callId === event.callId) {
        call.state = { phase: "active", callId: event.callId!, peer: state.peer };
      }
      break;
    case "call.taken":
      if (state.phase === "incoming" && state.callId === event.callId) {
        ringer.stop();
        call.state = { phase: "idle" };
      }
      break;
    case "call.declined":
      if (state.phase === "outgoing" && state.callId === event.callId) finish(state.peer, "hat abgelehnt");
      break;
    case "call.ended": {
      if (!("callId" in state) || state.callId !== event.callId) return;
      const peer = state.phase === "incoming" ? state.peer.name : state.peer;
      const message = { missed: "nicht erreicht", declined: "abgelehnt", cancelled: "aufgelegt" }[event.reason ?? ""] ?? "Gespräch beendet";
      finish(peer, message);
      break;
    }
  }
}

function notifyIncoming(name: string) {
  if (!document.hidden || !("Notification" in window) || Notification.permission !== "granted") return;
  const notification = new Notification("CloseBy", { body: `${name} ruft an`, requireInteraction: true });
  notification.onclick = () => {
    window.focus();
    notification.close();
  };
}

// ---- calls ----

export async function startCall(frameId: string, frameName: string) {
  call.state = { phase: "outgoing", peer: frameName };
  try {
    const join = await api.startCall(frameId);
    if (call.state.phase !== "outgoing") {
      // hung up while the request was running
      await api.endCall(join.callId).catch(() => {});
      return;
    }
    call.state = { phase: "outgoing", callId: join.callId, peer: frameName };
    await joinRoom(join.livekitUrl, join.token);
  } catch (e) {
    const busy = e instanceof Error && /busy|unreachable/.test(e.message);
    finish(frameName, busy ? "ist gerade nicht erreichbar" : "Anruf fehlgeschlagen");
  }
}

export async function acceptCall() {
  const state = call.state;
  if (state.phase !== "incoming") return;
  ringer.stop();
  try {
    const join = await api.acceptCall(state.callId);
    call.state = { phase: "active", callId: state.callId, peer: state.peer.name };
    await joinRoom(join.livekitUrl, join.token);
  } catch {
    finish(state.peer.name, "Anruf fehlgeschlagen");
  }
}

export async function declineCall() {
  const state = call.state;
  if (state.phase !== "incoming") return;
  ringer.stop();
  call.state = { phase: "idle" };
  await api.declineCall(state.callId).catch(() => {});
}

export async function hangUp() {
  const state = call.state;
  const callId = "callId" in state ? state.callId : undefined;
  const peer = state.phase === "incoming" ? state.peer.name : "peer" in state ? state.peer : "";
  finish(peer, "Gespräch beendet");
  if (callId) await api.endCall(callId).catch(() => {});
}

export async function toggleMic() {
  call.micOn = !call.micOn;
  await room?.localParticipant.setMicrophoneEnabled(call.micOn);
}

export async function toggleCam() {
  call.camOn = !call.camOn;
  await room?.localParticipant.setCameraEnabled(call.camOn);
}

export function localVideoTrack() {
  return room?.localParticipant.getTrackPublication(Track.Source.Camera)?.videoTrack ?? null;
}

async function joinRoom(url: string, token: string) {
  const current = new Room({ adaptiveStream: true, dynacast: true });
  room = current;
  current.on(RoomEvent.TrackSubscribed, (track) => {
    if (track.kind === Track.Kind.Video) call.remoteVideo = track;
    if (track.kind === Track.Kind.Audio) track.attach(); // plays via a detached <audio>
  });
  current.on(RoomEvent.TrackUnsubscribed, (track) => {
    track.detach();
    if (call.remoteVideo === track) call.remoteVideo = null;
  });
  current.on(RoomEvent.ParticipantDisconnected, () => {
    // the other side is gone without a proper hang-up (crash, network);
    // give it a moment to come back before giving up
    clearTimeout(peerTimer);
    peerTimer = setTimeout(() => {
      if (room === current && current.remoteParticipants.size === 0 && call.state.phase === "active") hangUp();
    }, 15_000);
  });
  current.on(RoomEvent.ParticipantConnected, () => clearTimeout(peerTimer));
  await current.connect(url, token);
  call.micOn = true;
  call.camOn = true;
  await current.localParticipant.enableCameraAndMicrophone();
  session.revision++; // local preview changed
}

function finish(peer: string, message: string) {
  ringer.stop();
  clearTimeout(peerTimer);
  room?.disconnect();
  room = null;
  call.remoteVideo = null;
  call.state = { phase: "ended", peer, message };
  clearTimeout(endedTimer);
  endedTimer = setTimeout(() => {
    if (call.state.phase === "ended") call.state = { phase: "idle" };
  }, 3000);
}
