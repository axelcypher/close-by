<script lang="ts">
  import { onDestroy } from "svelte";
  import { api, ApiError, formatCode, storeToken, type PairingStartResponse } from "../api";
  import Qr from "./Qr.svelte";

  let { onDone }: { onDone: () => void } = $props();

  type Mode = "choose" | "admin" | "invite" | "link";
  let mode = $state<Mode>("choose");
  let name = $state("");
  let deviceName = $state("PC");
  let code = $state("");
  let error = $state("");
  let busy = $state(false);

  // pre-fill from /invite/<code> links
  const inviteFromUrl = new URLSearchParams(location.search).get("invite");
  if (inviteFromUrl) {
    mode = "invite";
    code = inviteFromUrl;
  }

  let pairing = $state<PairingStartResponse | null>(null);
  let pollTimer: ReturnType<typeof setInterval> | undefined;
  onDestroy(() => clearInterval(pollTimer));

  async function register(event: SubmitEvent) {
    event.preventDefault();
    busy = true;
    error = "";
    try {
      const auth = await api.register({
        adminCode: mode === "admin" ? code.trim() : undefined,
        code: mode === "invite" ? code.trim() : undefined,
        device: { kind: "desktop", name: deviceName.trim() || "PC" },
        person: { name: name.trim() },
      });
      storeToken(auth.token);
      onDone();
    } catch (e) {
      if (e instanceof ApiError && e.status === 403) {
        error = mode === "admin" ? "Der Admin-Code stimmt nicht." : "Die Einladung ist ungültig oder abgelaufen.";
      } else {
        error = `Das hat nicht geklappt: ${(e as Error).message}`;
      }
    } finally {
      busy = false;
    }
  }

  async function startLink() {
    mode = "link";
    error = "";
    try {
      pairing = await api.startPairing("desktop", deviceName.trim() || "PC");
      pollTimer = setInterval(poll, 2000);
    } catch (e) {
      error = (e as Error).message;
    }
  }

  async function poll() {
    if (!pairing) return;
    if (Date.now() > pairing.expiresAt) {
      clearInterval(pollTimer);
      pairing = null;
      error = "Der Code ist abgelaufen.";
      return;
    }
    try {
      const status = await api.pollPairing(pairing.pairingId, pairing.pollSecret);
      if (status.state === "done" && status.token) {
        clearInterval(pollTimer);
        storeToken(status.token);
        onDone();
      }
    } catch {
      // keep polling; a hiccup is not fatal
    }
  }

  function back() {
    clearInterval(pollTimer);
    pairing = null;
    error = "";
    mode = "choose";
  }
</script>

<main>
  <header>
    <img src="/icon.svg" alt="" width="56" height="56" />
    <h1>CloseBy</h1>
    <p class="muted">Vertraute Gesichter, ein Tippen entfernt.</p>
  </header>

  <section class="card stack">
    {#if mode === "choose"}
      <h2>Diesen PC einrichten</h2>
      <button class="option" onclick={() => startLink()}>
        <strong>Mit meinem Handy verknüpfen</strong>
        <span class="muted">Du nutzt CloseBy schon auf dem Handy</span>
      </button>
      <button class="option" onclick={() => (mode = "invite")}>
        <strong>Ich habe eine Einladung</strong>
        <span class="muted">Code aus dem Einladungslink</span>
      </button>
      <button class="option" onclick={() => (mode = "admin")}>
        <strong>Als Admin einrichten</strong>
        <span class="muted">Mit dem Admin-Code des Servers</span>
      </button>
    {:else if mode === "link"}
      <h2>Mit dem Handy verknüpfen</h2>
      {#if pairing}
        <p>Öffne CloseBy auf deinem Handy, tippe auf <em>Gerät hinzufügen</em> und scanne den Code.</p>
        <div class="qr">
          <Qr value={`closeby://pair?server=${encodeURIComponent(location.origin)}&code=${pairing.code}`} />
          <span class="code">{formatCode(pairing.code)}</span>
        </div>
        <p class="muted">Warte auf das Handy …</p>
      {/if}
      {#if error}<p class="error">{error}</p>{/if}
      <div class="row"><button onclick={back}>Zurück</button></div>
    {:else}
      <form class="stack" onsubmit={register}>
        <h2>{mode === "admin" ? "Als Admin einrichten" : "Einladung annehmen"}</h2>
        <label>
          {mode === "admin" ? "Admin-Code" : "Einladungscode"}
          <input type={mode === "admin" ? "password" : "text"} bind:value={code} required autocomplete="off" />
        </label>
        <label>
          Dein Name, so sieht ihn der Rahmen
          <input type="text" bind:value={name} required maxlength="60" placeholder="z. B. Tobi" />
        </label>
        <label>
          Name dieses Geräts
          <input type="text" bind:value={deviceName} required maxlength="60" />
        </label>
        {#if error}<p class="error">{error}</p>{/if}
        <div class="row">
          <button type="button" onclick={back}>Zurück</button>
          <button class="primary" type="submit" disabled={busy}>Einrichten</button>
        </div>
      </form>
    {/if}
  </section>
</main>

<style>
  main {
    max-width: 30rem;
    margin: 0 auto;
    padding: 3rem 1rem;
    display: grid;
    gap: 1.5rem;
  }
  header {
    text-align: center;
  }
  header img {
    border-radius: 14px;
  }
  header h1 {
    margin: 0.6rem 0 0.2rem;
  }
  .option {
    display: grid;
    gap: 0.15rem;
    text-align: left;
    padding: 0.9rem 1rem;
  }
  .qr {
    display: grid;
    justify-items: center;
    gap: 0.6rem;
  }
</style>
