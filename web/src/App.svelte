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

  async function start() {
    loading = true;
    offline = false;
    if (storedToken()) {
      try {
        await loadMe();
        connectRealtime();
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) logout();
        else offline = true;
      }
    }
    loading = false;
  }

  onMount(() => {
    start();
    // audio may only start after a user gesture; the first click unlocks the ringtone
    const unlock = () => ringer.unlock();
    window.addEventListener("pointerdown", unlock, { once: true });
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
