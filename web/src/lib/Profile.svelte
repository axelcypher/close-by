<script lang="ts">
  import { api } from "../api";
  import { squareJpeg } from "../image";
  import { loadMe, session, signOut, tauriWindow } from "../session.svelte";
  import Avatar from "./Avatar.svelte";

  const person = $derived(session.me?.person);
  let name = $state(session.me?.person?.name ?? "");
  let message = $state("");
  let error = $state("");

  async function saveName(event: SubmitEvent) {
    event.preventDefault();
    await run(() => api.rename(name.trim()), "Name gespeichert.");
  }

  async function pickAvatar(event: Event) {
    const file = (event.currentTarget as HTMLInputElement).files?.[0];
    if (!file) return;
    await run(async () => api.uploadAvatar(await squareJpeg(file, 512)), "Bild gespeichert.");
  }

  async function run(action: () => Promise<void>, success: string) {
    error = "";
    message = "";
    try {
      await action();
      await loadMe();
      message = success;
    } catch (e) {
      error = (e as Error).message;
    }
  }
</script>

<section class="stack">
  {#if person}
    <div class="card stack">
      <h2>So sieht dich der Rahmen</h2>
      <div class="row">
        <Avatar personId={person.id} version={person.avatarVersion} name={person.displayName ?? person.name} size={96} />
        <label class="upload">
          <span>Bild ändern</span>
          <input type="file" accept="image/*" onchange={pickAvatar} />
        </label>
      </div>
      <form class="row" onsubmit={saveName}>
        <label class="grow">Dein Name <input type="text" bind:value={name} required maxlength="60" /></label>
        <button class="primary">Speichern</button>
      </form>
      {#if person.displayName}
        <p class="muted">Ein Admin hat festgelegt, dass der Rahmen „{person.displayName}“ anzeigt.</p>
      {/if}
      {#if message}<p class="muted">{message}</p>{/if}
      {#if error}<p class="error">{error}</p>{/if}
    </div>
  {/if}

  {#if !tauriWindow()}
    <div class="card stack">
      <h2>Windows-App</h2>
      <p class="muted">Läuft im Infobereich, startet mit Windows und klingelt auch bei geschlossenem Fenster.</p>
      <div class="row"><a href="/download/windows">CloseBy für Windows herunterladen</a></div>
    </div>
  {/if}

  <div class="card stack">
    <h2>Dieses Gerät</h2>
    <p class="muted">{session.me?.device.name}</p>
    <div class="row"><button class="danger" onclick={signOut}>Abmelden</button></div>
  </div>
</section>

<style>
  .grow {
    flex: 1;
  }
  form.row {
    align-items: flex-end;
  }
  .upload {
    cursor: pointer;
    color: var(--accent);
  }
  .upload input {
    display: none;
  }
</style>
