<script lang="ts">
  import { api, ApiError, type FrameAdmin, type ImmichAlbum, type PersonAdmin } from "../api";
  import { session } from "../session.svelte";
  import FrameEditor from "./FrameEditor.svelte";

  let frames = $state<FrameAdmin[]>([]);
  let persons = $state<PersonAdmin[]>([]);
  let albums = $state<ImmichAlbum[] | null>(null);
  let albumError = $state("");
  let code = $state("");
  let claimError = $state("");
  let claiming = $state(false);

  let loadError = $state("");

  async function load() {
    try {
      [frames, persons] = await Promise.all([api.admin.frames(), api.admin.persons()]);
      loadError = "";
    } catch (e) {
      loadError = `Rahmen konnten nicht geladen werden: ${(e as Error).message}`;
    }
  }

  async function loadAlbums() {
    try {
      albums = await api.admin.albums();
    } catch {
      albumError = "Immich ist gerade nicht erreichbar, Alben können nicht geladen werden.";
    }
  }

  $effect(() => {
    session.revision;
    load();
  });
  loadAlbums();

  async function claim(event: SubmitEvent) {
    event.preventDefault();
    claiming = true;
    claimError = "";
    try {
      await api.claimPairing(code, "frame");
      code = "";
      await load();
    } catch (e) {
      claimError =
        e instanceof ApiError && e.status === 404
          ? "Code unbekannt oder abgelaufen."
          : e instanceof ApiError && e.message === "wrong kind"
            ? "Das ist kein Code eines Rahmens. Der Code ist jetzt ungültig."
            : `Das hat nicht geklappt: ${(e as Error).message}`;
    } finally {
      claiming = false;
    }
  }
</script>

<section class="stack">
  <form class="card stack" onsubmit={claim}>
    <h2>Rahmen hinzufügen</h2>
    <p class="muted">Starte CloseBy auf dem Tablet und wähle „Als Rahmen einrichten“. Gib dann den angezeigten Code ein.</p>
    <div class="row">
      <input type="text" bind:value={code} placeholder="ABCD-EFGH" required autocomplete="off" style="max-width: 14rem" />
      <button class="primary" disabled={claiming}>Übernehmen</button>
    </div>
    {#if claimError}<p class="error">{claimError}</p>{/if}
  </form>

  {#if loadError}<p class="error">{loadError}</p>{/if}
  {#if albumError}<p class="muted">{albumError}</p>{/if}

  {#each frames as frame (frame.device.id)}
    <FrameEditor {frame} {persons} {albums} onChanged={load} />
  {/each}
</section>
