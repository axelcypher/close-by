import {
  createLocalAudioTrack,
  createLocalVideoTrack,
  DisconnectReason,
  Room,
  RoomEvent,
  Track,
  type LocalTrack,
  type RemoteTrack,
} from "livekit-client";
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
  /** The server ships a newer web build; the page reloads once nobody uses it. */
  updateReady: false,
});

export const call = $state<{
  state: CallPhase;
  remoteVideo: RemoteTrack | null;
  micOn: boolean;
  camOn: boolean;
  /** No camera (missing, busy in another app, blocked): the call runs with audio only. */
  cameraFailed: boolean;
  /** Bumped when the local tracks change, so the preview re-attaches. */
  media: number;
}>({
  state: { phase: "idle" },
  remoteVideo: null,
  micOn: true,
  camOn: true,
  cameraFailed: false,
  media: 0,
});

/** A bit longer than the server's ring timeout (CALL_RING_TIMEOUT_SECONDS, 45 s), in case its call.ended got lost. */
const RING_TIMEOUT_MS = 60_000;
/** How long the other side may be missing from the room before the call ends. */
const PEER_GRACE_MS = 15_000;
/** The callee joins the room only after accepting; a slow tablet needs a moment. */
const PEER_JOIN_MS = 30_000;

let room: Room | null = null;
let socket: WebSocket | null = null;
let retryMs = 1000;
let wasConnected = false;
let endedTimer: ReturnType<typeof setTimeout> | undefined;
let peerTimer: ReturnType<typeof setTimeout> | undefined;
let ringTimer: ReturnType<typeof setTimeout> | undefined;
let notification: Notification | null = null;
/**
 * Bumped whenever a call starts or is left. Async steps compare it with the value
 * they started with and give up when another call (or the end of this one) came between.
 */
let attempt = 0;

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
      if (wasConnected) {
        // events may have been missed, and a deploy restarts the server
        session.revision++;
        checkForNewBuild();
      }
      wasConnected = true;
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
      // an admin may have moved this device to another person
      loadMe().catch(() => {});
      session.revision++;
      break;
    case "presence.changed":
      session.revision++;
      break;
    case "call.incoming":
      if (state.phase !== "idle" && state.phase !== "ended") return; // busy; the server ends it after the timeout
      attempt++;
      call.state = { phase: "incoming", callId: event.callId!, peer: event.from! };
      armRingTimeout();
      ringer.start();
      notifyIncoming(event.callId!, event.from!.name);
      break;
    case "call.accepted":
      if (state.phase === "outgoing" && state.callId && state.callId === event.callId) answered(state.callId, state.peer);
      break;
    case "call.taken":
      if (state.phase === "incoming" && state.callId === event.callId) {
        leave();
        toIdle();
      }
      break;
    case "call.declined":
      if (state.phase === "outgoing" && state.callId === event.callId) finish(state.peer, "hat abgelehnt");
      break;
    case "call.ended": {
      if (!("callId" in state) || state.callId !== event.callId) return;
      const message = { missed: "nicht erreicht", declined: "abgelehnt", cancelled: "aufgelegt" }[event.reason ?? ""] ?? "Gespräch beendet";
      finish(peerName(state), message);
      break;
    }
  }
}

interface TauriWindow {
  show(): Promise<void>;
  unminimize(): Promise<void>;
  setFocus(): Promise<void>;
  requestUserAttention(type: number | null): Promise<void>;
}

/** The Windows app (Tauri) exposes its window API; in a browser this is null. */
export function tauriWindow(): TauriWindow | null {
  const tauri = (window as unknown as { __TAURI__?: { window?: { getCurrentWindow?: () => TauriWindow } } }).__TAURI__;
  return tauri?.window?.getCurrentWindow?.() ?? null;
}

function notifyIncoming(callId: string, name: string) {
  const desktop = tauriWindow();
  if (desktop) {
    // the window may sit hidden in the tray: bring it up and flash the taskbar (1 = critical)
    desktop.show().then(() => desktop.unminimize()).then(() => desktop.setFocus()).catch(() => {});
    desktop.requestUserAttention(1).catch(() => {});
    return;
  }
  if (!document.hidden || !("Notification" in window) || Notification.permission !== "granted") return;
  const shown = new Notification("CloseBy", { body: `${name} ruft an`, tag: callId, requireInteraction: true });
  shown.onclick = () => {
    window.focus();
    shown.close();
  };
  notification = shown;
}

// ---- new web builds ----

const RELOADED_FOR_KEY = "closeby.reloadedFor";

/**
 * Long-running windows (the tray app, pinned tabs) would keep an old build forever.
 * The page names its current script; if that differs from ours, a release went out.
 */
async function checkForNewBuild() {
  if (!import.meta.env.PROD || session.updateReady) return;
  try {
    const page = await fetch("/", { cache: "no-store" }).then((r) => (r.ok ? r.text() : ""));
    const latest = /<script[^>]+src="([^"]+)"/.exec(page)?.[1];
    // import.meta.url is the bundle this code runs in (there is only one)
    if (!latest || latest === new URL(import.meta.url).pathname) return;
    // reload at most once per build, so a mismatch that a reload cannot fix never loops
    if (sessionStorage.getItem(RELOADED_FOR_KEY) === latest) return;
    sessionStorage.setItem(RELOADED_FOR_KEY, latest);
    session.updateReady = true;
    document.addEventListener("visibilitychange", () => reloadIfOutdated());
    reloadIfOutdated();
  } catch {
    // offline or no sessionStorage: try again after the next reconnect
  }
}

/** Reloads into the new build, but not during a call and not under someone's hands. */
export function reloadIfOutdated(force = false) {
  if (session.updateReady && call.state.phase === "idle" && (force || document.hidden)) location.reload();
}

// ---- calls ----

export async function startCall(frameId: string, frameName: string) {
  const mine = ++attempt;
  call.state = { phase: "outgoing", peer: frameName };
  let callId: string | undefined;
  try {
    const join = await api.startCall(frameId);
    callId = join.callId;
    if (mine !== attempt) {
      // hung up while the request was running
      await api.endCall(join.callId).catch(() => {});
      return;
    }
    call.state = { phase: "outgoing", callId, peer: frameName };
    armRingTimeout();
    await joinRoom(mine, join.livekitUrl, join.token);
  } catch (e) {
    if (mine !== attempt) return; // whoever ended it already cleaned up
    // don't leave the frame ringing, or sitting in an empty room after it answered
    if (callId) api.endCall(callId).catch(() => {});
    const busy = e instanceof Error && /busy|unreachable/.test(e.message);
    finish(frameName, busy ? "ist gerade nicht erreichbar" : failure(e));
  }
}

export async function acceptCall() {
  const state = call.state;
  if (state.phase !== "incoming") return;
  leave();
  const mine = attempt;
  // "active" right away: the buttons are gone, so a double click can't accept twice
  call.state = { phase: "active", callId: state.callId, peer: state.peer.name };
  try {
    const join = await api.acceptCall(state.callId);
    if (mine !== attempt) return; // hung up meanwhile, which ended the call on the server
    await joinRoom(mine, join.livekitUrl, join.token);
  } catch (e) {
    if (mine !== attempt) return;
    // the server only lets the device that answered end the call, so this is harmless if another one took it
    api.endCall(state.callId).catch(() => {});
    finish(state.peer.name, failure(e));
  }
}

export async function declineCall() {
  const state = call.state;
  if (state.phase !== "incoming") return;
  leave();
  toIdle();
  await api.declineCall(state.callId).catch(() => {});
}

export async function hangUp() {
  const state = call.state;
  const callId = "callId" in state ? state.callId : undefined;
  finish(peerName(state), "Gespräch beendet");
  if (callId) await api.endCall(callId).catch(() => {});
}

export async function toggleMic() {
  const on = !call.micOn;
  call.micOn = on;
  try {
    await room?.localParticipant.setMicrophoneEnabled(on);
  } catch {
    call.micOn = !on;
  }
}

export async function toggleCam() {
  const on = !call.camOn;
  call.camOn = on;
  try {
    await room?.localParticipant.setCameraEnabled(on);
    if (on) call.cameraFailed = false;
  } catch {
    call.camOn = false;
    call.cameraFailed = true;
  }
  call.media++;
}

export function localVideoTrack() {
  return room?.localParticipant.getTrackPublication(Track.Source.Camera)?.videoTrack ?? null;
}

class MicrophoneUnavailable extends Error {}

function failure(e: unknown) {
  return e instanceof MicrophoneUnavailable ? "Kein Mikrofon verfügbar" : "Anruf fehlgeschlagen";
}

async function joinRoom(mine: number, url: string, token: string) {
  const current = new Room({ adaptiveStream: true, dynacast: true });
  room = current;
  const stale = () => mine !== attempt;
  current.on(RoomEvent.TrackSubscribed, (track) => {
    if (track.kind === Track.Kind.Video) call.remoteVideo = track;
    if (track.kind === Track.Kind.Audio) track.attach(); // plays via a detached <audio>
  });
  current.on(RoomEvent.TrackUnsubscribed, (track) => {
    track.detach();
    if (call.remoteVideo === track) call.remoteVideo = null;
  });
  // the other side is gone without a proper hang-up (crash, network);
  // give it a moment to come back before giving up
  current.on(RoomEvent.ParticipantDisconnected, () => watchPeer(current, PEER_GRACE_MS));
  current.on(RoomEvent.ParticipantConnected, () => {
    clearTimeout(peerTimer);
    // the callee is here, even if its call.accepted got lost on the way
    const state = call.state;
    if (state.phase === "outgoing" && state.callId) answered(state.callId, state.peer);
  });
  current.on(RoomEvent.Disconnected, (reason) => {
    if (room !== current) return; // left on purpose
    const state = call.state;
    if ("callId" in state && state.callId) api.endCall(state.callId).catch(() => {});
    finish(peerName(state), reason === DisconnectReason.ROOM_DELETED ? "Gespräch beendet" : "Verbindung verloren");
  });

  await current.connect(url, token);
  if (stale()) return void current.disconnect();

  // separate requests, so a missing or busy camera still allows a call with audio
  let microphone: LocalTrack;
  try {
    microphone = await createLocalAudioTrack();
  } catch {
    if (stale()) return void current.disconnect();
    throw new MicrophoneUnavailable();
  }
  const camera = await createLocalVideoTrack().catch(() => null);
  const tracks = camera ? [microphone, camera] : [microphone];
  const release = () => tracks.forEach((track) => track.stop());
  if (stale()) {
    release();
    return void current.disconnect();
  }
  try {
    for (const track of tracks) await current.localParticipant.publishTrack(track);
  } catch (e) {
    release();
    throw e;
  }
  if (stale()) {
    release();
    return void current.disconnect();
  }
  call.micOn = true;
  call.camOn = camera !== null;
  call.cameraFailed = camera === null;
  call.media++;
  if (call.state.phase === "active" && current.remoteParticipants.size === 0) watchPeer(current, PEER_JOIN_MS);
}

/** The callee took the call. */
function answered(callId: string, peer: string) {
  clearTimeout(ringTimer);
  call.state = { phase: "active", callId, peer };
  if (room && room.remoteParticipants.size === 0) watchPeer(room, PEER_JOIN_MS);
}

/** Hangs up when nobody else is in the room after [ms]. */
function watchPeer(current: Room, ms: number) {
  clearTimeout(peerTimer);
  peerTimer = setTimeout(() => {
    if (room === current && current.remoteParticipants.size === 0 && call.state.phase === "active") hangUp();
  }, ms);
}

function armRingTimeout() {
  clearTimeout(ringTimer);
  const mine = attempt;
  ringTimer = setTimeout(() => {
    const state = call.state;
    if (mine !== attempt || (state.phase !== "incoming" && state.phase !== "outgoing")) return;
    if (state.phase === "outgoing" && state.callId) api.endCall(state.callId).catch(() => {});
    finish(peerName(state), "nicht erreicht");
  }, RING_TIMEOUT_MS);
}

function peerName(state: CallPhase) {
  return state.phase === "incoming" ? state.peer.name : "peer" in state ? state.peer : "";
}

/** Drops everything of the current call on this side: sound, timers, room, notification. */
function leave() {
  attempt++;
  ringer.stop();
  clearTimeout(ringTimer);
  clearTimeout(peerTimer);
  notification?.close();
  notification = null;
  const old = room;
  room = null; // before disconnect(), so its Disconnected event is known to be ours
  old?.disconnect();
  call.remoteVideo = null;
}

function finish(peer: string, message: string) {
  leave();
  call.state = { phase: "ended", peer, message };
  clearTimeout(endedTimer);
  endedTimer = setTimeout(() => {
    if (call.state.phase === "ended") toIdle();
  }, 3000);
}

function toIdle() {
  call.state = { phase: "idle" };
  reloadIfOutdated();
}
