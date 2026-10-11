<script lang="ts">
  import { api } from "../api";
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

  /** Center crop to a square and shrink, so phones' 10 MB photos stay small. */
  async function squareJpeg(file: File, size: number): Promise<Blob> {
    const bitmap = await createImageBitmap(file);
    const side = Math.min(bitmap.width, bitmap.height);
    const canvas = document.createElement("canvas");
    canvas.width = canvas.height = size;
    canvas
      .getContext("2d")!
      .drawImage(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side, 0, 0, size, size);
    return new Promise((resolve, reject) =>
      canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error("Bild konnte nicht verarbeitet werden"))), "image/jpeg", 0.85),
    );
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
