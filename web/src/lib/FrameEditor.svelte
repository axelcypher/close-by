<script lang="ts">
  import { untrack } from "svelte";
  import { api, type FrameAdmin, type FrameContactEntry, type ImmichAlbum, type PersonAdmin } from "../api";
  import Avatar from "./Avatar.svelte";

  let {
    frame,
    persons,
    albums,
    onChanged,
  }: { frame: FrameAdmin; persons: PersonAdmin[]; albums: ImmichAlbum[] | null; onChanged: () => void } = $props();

  // edited on a local copy and saved explicitly
  const initial = untrack(() => $state.snapshot(frame));
  let name = $state(initial.device.name);
  let albumId = $state(initial.albumId ?? "");
  let settings = $state(initial.settings);
  let nightMode = $state(!!initial.settings.nightStart);
  let contacts = $state<FrameContactEntry[]>(initial.contacts);
  let saved = $state("");
  let error = $state("");
  let confirmRemove = $state(false);

  const hidden = $derived(persons.filter((p) => !contacts.some((c) => c.personId === p.person.id)));
  const personById = $derived(new Map(persons.map((p) => [p.person.id, p.person])));

  function move(index: number, delta: number) {
    const target = index + delta;
    if (target < 0 || target >= contacts.length) return;
    [contacts[index], contacts[target]] = [contacts[target], contacts[index]];
  }

  async function save() {
    error = "";
    try {
      await api.admin.updateFrame(frame.device.id, {
        name,
        albumId: albumId || null,
        settings: {
          ...settings,
          nightStart: nightMode ? settings.nightStart || "22:00" : null,
          nightEnd: nightMode ? settings.nightEnd || "07:00" : null,
        },
      });
      await api.admin.setFrameContacts(frame.device.id, contacts);
      saved = "Gespeichert, der Rahmen übernimmt es sofort.";
      setTimeout(() => (saved = ""), 3000);
      onChanged();
    } catch (e) {
      error = (e as Error).message;
    }
  }

  async function remove() {
    await api.admin.deleteDevice(frame.device.id);
    onChanged();
  }

  function lastSeen(timestamp?: number) {
    return timestamp ? new Date(timestamp).toLocaleString("de-DE", { dateStyle: "short", timeStyle: "short" }) : "nie";
  }
</script>

<article class="card stack">
  <div class="row head">
    <h2>{frame.device.name}</h2>
    <span class="row muted status">
      <span class="dot" class:on={frame.device.online}></span>
      {frame.device.online ? "online" : `zuletzt ${lastSeen(frame.device.lastSeen)}`}
      {#if frame.device.status?.battery != null}· Akku {frame.device.status.battery} %{frame.device.status.charging ? " ⚡" : ""}{/if}
      {#if frame.device.appVersion}· App {frame.device.appVersion}{/if}
    </span>
  </div>

  <div class="columns">
    <div class="stack">
      <label>Name <input type="text" bind:value={name} maxlength="60" /></label>
      <label>
        Album für die Diashow
        <select bind:value={albumId}>
          <option value="">Kein Album</option>
          {#each albums ?? [] as album (album.id)}
            <option value={album.id}>{album.name} ({album.assetCount})</option>
          {/each}
          {#if albumId && albums && !albums.some((a) => a.id === albumId)}
            <option value={albumId}>Unbekanntes Album</option>
          {/if}
        </select>
      </label>
      <div class="row two">
        <label>Bildwechsel (s) <input type="number" min="5" max="600" bind:value={settings.slideshowIntervalSeconds} /></label>
        <label>Diashow nach (s) <input type="number" min="10" max="3600" bind:value={settings.idleTimeoutSeconds} /></label>
      </div>
      <label class="check"><input type="checkbox" bind:checked={nightMode} /> Nachtmodus (Bildschirm dunkel)</label>
      {#if nightMode}
        <div class="row two">
          <label>Von <input type="time" bind:value={settings.nightStart} /></label>
          <label>Bis <input type="time" bind:value={settings.nightEnd} /></label>
        </div>
      {/if}
      <label class="check">
        <input type="checkbox" bind:checked={settings.forcePublicDns} /> Server über öffentliches DNS auflösen
      </label>
    </div>

    <div class="stack">
      <h3>Kontakte auf dem Rahmen</h3>
      {#each contacts as contact, index (contact.personId)}
        {@const person = personById.get(contact.personId)}
        {#if person}
          <div class="contact row">
            <Avatar personId={person.id} version={person.avatarVersion} name={person.displayName ?? person.name} size={36} />
            <span class="grow">{person.displayName ?? person.name}</span>
            <label class="check small" title="Anrufe dieser Person nimmt der Rahmen selbst an">
              <input type="checkbox" bind:checked={contact.autoAnswer} /> Auto-Annahme
            </label>
            <button class="link" onclick={() => move(index, -1)} disabled={index === 0} aria-label="nach oben">↑</button>
            <button class="link" onclick={() => move(index, 1)} disabled={index === contacts.length - 1} aria-label="nach unten">↓</button>
            <button class="link danger" onclick={() => contacts.splice(index, 1)}>Ausblenden</button>
          </div>
        {/if}
      {/each}
      {#if hidden.length}
        <p class="muted small">Nicht auf diesem Rahmen:</p>
        {#each hidden as { person } (person.id)}
          <div class="contact row">
            <Avatar personId={person.id} version={person.avatarVersion} name={person.name} size={36} />
            <span class="grow muted">{person.displayName ?? person.name}</span>
            <button class="link" onclick={() => contacts.push({ personId: person.id, autoAnswer: false })}>Anzeigen</button>
          </div>
        {/each}
      {/if}
    </div>
  </div>

  <div class="row">
    <button class="primary" onclick={save}>Speichern</button>
    {#if saved}<span class="muted">{saved}</span>{/if}
    {#if error}<span class="error">{error}</span>{/if}
    <span class="grow"></span>
    {#if confirmRemove}
      <span>Rahmen wirklich entfernen?</span>
      <button class="danger" onclick={remove}>Ja, entfernen</button>
      <button onclick={() => (confirmRemove = false)}>Nein</button>
    {:else}
      <button class="danger" onclick={() => (confirmRemove = true)}>Rahmen entfernen</button>
    {/if}
  </div>
</article>

<style>
  .head {
    justify-content: space-between;
  }
  .head h2 {
    margin: 0;
  }
  .status {
    gap: 0.4rem;
    font-size: 0.9rem;
  }
  .columns {
    display: grid;
    gap: 1.5rem;
    grid-template-columns: repeat(auto-fit, minmax(18rem, 1fr));
  }
  .two > label {
    flex: 1;
  }
  h3 {
    font-size: 1rem;
  }
  .contact {
    flex-wrap: nowrap;
  }
  .grow {
    flex: 1;
  }
  .small {
    font-size: 0.85rem;
    margin: 0.3rem 0 0;
  }
</style>
