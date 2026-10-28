<script lang="ts">
  import type { Track } from "livekit-client";
  import { acceptCall, call, declineCall, hangUp, localVideoTrack, toggleCam, toggleMic } from "../session.svelte";
  import Avatar from "./Avatar.svelte";

  const state = $derived(call.state);
  const peer = $derived(state.phase === "incoming" ? state.peer.name : "peer" in state ? state.peer : "");

  // attaches a LiveKit track to a <video>; re-runs when the track changes
  function video(element: HTMLVideoElement, track: Track | null) {
    track?.attach(element);
    return {
      update(next: Track | null) {
        track?.detach(element);
        track = next;
        track?.attach(element);
      },
      destroy() {
        track?.detach(element);
      },
    };
  }

  const localTrack = $derived.by(() => {
    call.media;
    call.camOn;
    return state.phase === "active" || state.phase === "outgoing" ? localVideoTrack() : null;
  });
</script>

<div class="overlay" role="dialog" aria-modal="true" aria-label="Anruf">
  {#if state.phase === "active"}
    <div class="stage">
      {#if call.remoteVideo}
        <video class="remote" use:video={call.remoteVideo} autoplay playsinline></video>
      {:else}
        <p class="waiting">Verbinde Video …</p>
      {/if}
      {#if localTrack}
        <video class="self" use:video={localTrack} autoplay playsinline muted></video>
      {/if}
      <div class="name">{peer}</div>
      {#if call.cameraFailed}<div class="notice">Kamera nicht verfügbar, nur Ton</div>{/if}
    </div>
    <div class="controls">
      <button class:off={!call.micOn} onclick={toggleMic}>{call.micOn ? "Mikrofon aus" : "Mikrofon an"}</button>
      <button class:off={!call.camOn} onclick={toggleCam}>{call.camOn ? "Kamera aus" : "Kamera an"}</button>
      <button class="hangup" onclick={hangUp}>Auflegen</button>
    </div>
  {:else}
    <div class="prompt">
      {#if state.phase === "incoming"}
        <p class="label">Eingehender Anruf</p>
        {#if state.peer.kind === "frame" && state.peer.avatarVersion != null}
          <Avatar frameId={state.peer.id} version={state.peer.avatarVersion} name={state.peer.name} size={160} />
        {/if}
        <h1>{peer}</h1>
        <div class="row actions">
          <button class="hangup big" onclick={declineCall}>Ablehnen</button>
          <button class="accept big" onclick={acceptCall}>Annehmen</button>
        </div>
      {:else if state.phase === "outgoing"}
        {#if localTrack}
          <video class="preview" use:video={localTrack} autoplay playsinline muted></video>
        {/if}
        <p class="label">Rufe an …</p>
        <h1>{peer}</h1>
        <button class="hangup big" onclick={hangUp}>Abbrechen</button>
      {:else if state.phase === "ended"}
        <h1>{peer}</h1>
        <p class="label">{state.message}</p>
      {/if}
    </div>
  {/if}
</div>

<style>
  .overlay {
    position: fixed;
    inset: 0;
    z-index: 50;
    background: #111;
    color: #fff;
    display: grid;
    grid-template-rows: 1fr auto;
  }
  .stage {
    position: relative;
    overflow: hidden;
  }
  .remote {
    width: 100%;
    height: 100%;
    object-fit: contain;
    background: #000;
  }
  .self {
    position: absolute;
    right: 1rem;
    bottom: 1rem;
    width: min(24vw, 16rem);
    border-radius: 12px;
    border: 2px solid rgb(255 255 255 / 30%);
    transform: scaleX(-1);
  }
  .waiting {
    position: absolute;
    inset: 0;
    display: grid;
    place-content: center;
    opacity: 0.7;
  }
  .name {
    position: absolute;
    left: 1rem;
    top: 1rem;
    padding: 0.3rem 0.7rem;
    border-radius: 999px;
    background: rgb(0 0 0 / 45%);
  }
  .notice {
    position: absolute;
    left: 1rem;
    bottom: 1rem;
    padding: 0.3rem 0.7rem;
    border-radius: 999px;
    background: rgb(0 0 0 / 45%);
  }
  .controls {
    display: flex;
    justify-content: center;
    gap: 0.8rem;
    padding: 1rem;
    background: #1b1b1b;
  }
  .controls button,
  .prompt button {
    border: none;
    color: #fff;
    background: #333;
    padding: 0.8rem 1.2rem;
    border-radius: 999px;
  }
  .controls button.off {
    background: #555;
  }
  .prompt {
    grid-row: 1 / -1;
    display: grid;
    place-content: center;
    justify-items: center;
    gap: 0.8rem;
    text-align: center;
  }
  .prompt h1 {
    font-size: 2.4rem;
    margin: 0;
  }
  .label {
    margin: 0;
    opacity: 0.75;
    font-size: 1.1rem;
  }
  .actions {
    margin-top: 1.5rem;
    gap: 1.5rem;
  }
  .big {
    min-width: 9rem;
    font-size: 1.1rem;
    font-weight: 600;
  }
  .hangup {
    background: #c0392b !important;
  }
  .accept {
    background: #2f8f4e !important;
  }
  .preview {
    width: min(70vw, 28rem);
    border-radius: 14px;
    transform: scaleX(-1);
    margin-bottom: 1rem;
  }
</style>
