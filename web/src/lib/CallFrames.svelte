<script lang="ts">
  import { api, type FrameRef } from "../api";
  import { call, session, startCall } from "../session.svelte";
  import Avatar from "./Avatar.svelte";

  let frames = $state<FrameRef[] | null>(null);
  let error = $state("");
  let permission = $state("Notification" in window ? Notification.permission : "denied");

  async function load() {
    try {
      frames = await api.frames();
      error = "";
    } catch (e) {
      error = (e as Error).message;
    }
  }

  $effect(() => {
    session.revision;
    load();
  });

  async function askPermission() {
    permission = await Notification.requestPermission();
  }
</script>

<section class="stack">
  {#if permission === "default"}
    <div class="card row hint">
      <span>Damit du Anrufe auch bemerkst, wenn das Fenster im Hintergrund ist:</span>
      <button onclick={askPermission}>Benachrichtigungen erlauben</button>
    </div>
  {/if}

  {#if error}<p class="error">{error}</p>{/if}

  {#if frames && frames.length === 0}
    <div class="card">
      <p>Noch kein Rahmen, den du anrufen kannst.</p>
      <p class="muted">Ein Admin fügt dich unter „Rahmen“ als Kontakt hinzu.</p>
    </div>
  {/if}

  <div class="grid">
    {#each frames ?? [] as frame (frame.id)}
      <article class="card frame">
        <div class="picture" aria-hidden="true">
          {#if frame.avatarVersion != null}
            <Avatar frameId={frame.id} version={frame.avatarVersion} name={frame.name} size={96} />
          {:else}
            <img src="/icon.svg" alt="" width="72" height="72" />
          {/if}
        </div>
        <div>
          <h2>{frame.name}</h2>
          <p class="muted row"><span class="dot" class:on={frame.online}></span>{frame.online ? "online" : "offline"}</p>
        </div>
        <button
          class="primary call"
          disabled={!frame.online || call.state.phase !== "idle"}
          onclick={() => startCall(frame.id, frame.name)}
        >
          Anrufen
        </button>
      </article>
    {/each}
  </div>
</section>

<style>
  .hint {
    justify-content: space-between;
  }
  .grid {
    display: grid;
    gap: 1rem;
    grid-template-columns: repeat(auto-fill, minmax(16rem, 1fr));
  }
  .frame {
    display: grid;
    gap: 0.8rem;
  }
  .picture {
    display: grid;
    place-content: center;
    background: var(--surface-2);
    border-radius: 10px;
    padding: 1.2rem;
  }
  .picture img {
    border-radius: 14px;
    opacity: 0.85;
  }
  .frame h2 {
    margin-bottom: 0.2rem;
  }
  .frame p {
    margin: 0;
    gap: 0.4rem;
  }
  .call {
    padding: 0.8rem;
    font-size: 1.05rem;
  }
</style>
