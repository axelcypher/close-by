<script lang="ts">
  import { onMount } from "svelte";
  import { ApiError, storedToken } from "./api";
  import { ringer } from "./ringer";
  import { call, connectRealtime, loadMe, logout, session } from "./session.svelte";
  import Setup from "./lib/Setup.svelte";
  import Home from "./lib/Home.svelte";
  import CallOverlay from "./lib/CallOverlay.svelte";

  let loading = $state(true);
  let offline = $state(false);
  let retryTimer: ReturnType<typeof setTimeout> | undefined;
  let retryMs = 2000;

  async function start() {
    clearTimeout(retryTimer);
    offline = false;
    if (storedToken()) {
      try {
        await loadMe();
        retryMs = 2000;
        connectRealtime();
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) {
          logout();
        } else {
          // e.g. the server restarts for a release; the tray app must come back on its own to ring again
          offline = true;
          retryTimer = setTimeout(start, retryMs);
          retryMs = Math.min(retryMs * 2, 60_000);
        }
      }
    }
    loading = false;
  }

  onMount(() => {
    start();
    // audio may only start after a user gesture in some browsers; the first click unlocks the ringtone
    const unlock = () => ringer.unlock();
    window.addEventListener("pointerdown", unlock, { once: true });
    const online = () => offline && start();
    window.addEventListener("online", online);
    return () => {
      clearTimeout(retryTimer);
      window.removeEventListener("online", online);
    };
  });
</script>

{#if loading}
  <main class="center"><p class="muted">CloseBy wird geladen …</p></main>
{:else if offline}
  <main class="center stack">
    <p>Der CloseBy-Server ist gerade nicht erreichbar.</p>
    <button class="primary" onclick={start}>Erneut versuchen</button>
  </main>
{:else if session.me}
  <Home />
{:else}
  <Setup onDone={start} />
{/if}

{#if call.state.phase !== "idle"}
  <CallOverlay />
{/if}

<style>
  .center {
    min-height: 100vh;
    display: grid;
    place-content: center;
    text-align: center;
    padding: 1rem;
  }
</style>
