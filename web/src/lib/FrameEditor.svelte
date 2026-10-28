<script lang="ts">
  import { untrack } from "svelte";
  import { api, type FrameAdmin, type FrameContactEntry, type ImmichAlbum, type PersonAdmin } from "../api";
  import AvatarPicker from "./AvatarPicker.svelte";

  let {
    frame,
    persons,
    albums,
    onChanged,
  }: { frame: FrameAdmin; persons: PersonAdmin[]; albums: ImmichAlbum[] | null; onChanged: () => void } = $props();

  // edited on a local copy and saved explicitly; untouched parts follow the server
  // (a new person joins every frame, a person may be merged away meanwhile)
  const initial = untrack(() => $state.snapshot(frame));
  let name = $state(initial.device.name);
  let albumId = $state(initial.albumId ?? "");
  let settings = $state(initial.settings);
  let nightMode = $state(!!initial.settings.nightStart);
  let contacts = $state<FrameContactEntry[]>(initial.contacts);
  /** The server's contacts the local list started from. */
  let baseContacts = initial.contacts.map((c) => c.personId);
  let settingsDirty = false;
  let contactsDirty = false;
  let saved = $state("");
  let error = $state("");
  let confirmRemove = $state(false);

  $effect(() => {
    const latest = $state.snapshot(frame);
    untrack(() => {
      if (!settingsDirty) {
        name = latest.device.name;
        albumId = latest.albumId ?? "";
        settings = latest.settings;
        nightMode = !!latest.settings.nightStart;
      }
      if (!contactsDirty) {
        contacts = latest.contacts;
        baseContacts = latest.contacts.map((c) => c.personId);
      }
    });
  });

  const hidden = $derived(persons.filter((p) => !contacts.some((c) => c.personId === p.person.id)));
  const personById = $derived(new Map(persons.map((p) => [p.person.id, p.person])));

  function move(index: number, delta: number) {
    const target = index + delta;
    if (target < 0 || target >= contacts.length) return;
    contactsDirty = true;
    [contacts[index], contacts[target]] = [contacts[target], contacts[index]];
  }

  /** Same limits as the Android admin. */
  function invalidSettings(): string | null {
    const between = (value: number | null | undefined, min: number, max: number) =>
      typeof value === "number" && Number.isInteger(value) && value >= min && value <= max;
    if (!between(settings.slideshowIntervalSeconds, 5, 600)) return "Bildwechsel: 5 bis 600 Sekunden.";
    if (!between(settings.idleTimeoutSeconds, 10, 3600)) return "Diashow nach: 10 bis 3600 Sekunden.";
    if (settings.volume != null && !between(settings.volume, 0, 100)) return "Lautstärke: 0 bis 100 % oder leer.";
    return null;
  }

  async function save() {
    error = invalidSettings() ?? "";
    if (error) return;
    try {
      if (settingsDirty) {
        await api.admin.updateFrame(frame.device.id, {
          name,
          albumId: albumId || null,
          settings: {
            ...settings,
            nightStart: nightMode ? settings.nightStart || "22:00" : null,
            nightEnd: nightMode ? settings.nightEnd || "07:00" : null,
          },
        });
      }
      if (contactsDirty) {
        // keep persons added on the server since editing began, drop persons that no longer exist
        const added = frame.contacts.filter((c) => !baseContacts.includes(c.personId) && !contacts.some((own) => own.personId === c.personId));
        const next = [...contacts, ...added].filter((c) => personById.has(c.personId));
        await api.admin.setFrameContacts(frame.device.id, next);
      }
      settingsDirty = false;
      contactsDirty = false;
      saved = "Gespeichert, der Rahmen übernimmt es sofort.";
      setTimeout(() => (saved = ""), 3000);
      onChanged();
    } catch (e) {
      error = (e as Error).message;
    }
  }

  let confirmRelease = $state(false);

  const commandMessages = {
    maintenance: "Kiosk ist für 10 Minuten aufgehoben, am Rahmen gibt es jetzt einen Knopf für die Android-Einstellungen.",
    update: "Der Rahmen sucht jetzt nach einem Update.",
    release: "Device Owner ist aufgehoben, das Tablet ist wieder ein normales Android-Gerät.",
  };

  async function command(kind: "maintenance" | "update" | "release") {
    error = "";
    confirmRelease = false;
    try {
      await api.admin.frameCommand(frame.device.id, kind);
      saved = commandMessages[kind];
      setTimeout(() => (saved = ""), 5000);
    } catch (e) {
      error = (e as Error).message === "frame offline" ? "Der Rahmen ist offline." : (e as Error).message;
    }
  }

  async function remove() {
    error = "";
    try {
      await api.admin.deleteDevice(frame.device.id);
      onChanged();
    } catch (e) {
      error = (e as Error).message;
    }
  }

  function lastSeen(timestamp?: number) {
    return timestamp ? new Date(timestamp).toLocaleString("de-DE", { dateStyle: "short", timeStyle: "short" }) : "nie";
  }
</script>

<article class="card stack">
  <div class="row head">
    <div class="row title">
      <AvatarPicker
        frameId={frame.device.id}
        version={frame.avatarVersion}
        name={frame.device.name}
        size={56}
        {onChanged}
        onError={(message) => (error = message)}
      />
      <div>
        <h2>{frame.device.name}</h2>
        <span class="muted small">Kontaktbild: erscheint bei allen, die der Rahmen anruft</span>
      </div>
    </div>
    <span class="row muted status">
      <span class="dot" class:on={frame.device.online}></span>
      {frame.device.online ? "online" : `zuletzt ${lastSeen(frame.device.lastSeen)}`}
      {#if frame.device.status?.battery != null}· Akku {frame.device.status.battery} %{frame.device.status.charging ? " ⚡" : ""}{/if}
      {#if frame.device.appVersion}· App {frame.device.appVersion}{/if}
      {#if frame.device.status?.deviceOwner}· {frame.device.status.kiosk ? "Kiosk" : "Kiosk aufgehoben"}{:else if frame.device.status}· kein Device Owner{/if}
    </span>
  </div>

  <div class="columns">
    <div class="stack" oninput={() => (settingsDirty = true)}>
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
      <label>
        Gesprächslautstärke in % (leer = Gerät bestimmt)
        <input type="number" min="0" max="100" bind:value={settings.volume} placeholder="z. B. 90" />
      </label>
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
      <p class="muted small">Ein Bild gilt für alle Rahmen, die die Person zeigen.</p>
      {#each contacts as contact, index (contact.personId)}
        {@const person = personById.get(contact.personId)}
        {#if person}
          <div class="contact row">
            <AvatarPicker
              personId={person.id}
              version={person.avatarVersion}
              name={person.displayName ?? person.name}
              size={36}
              {onChanged}
              onError={(message) => (error = message)}
            />
            <span class="grow">{person.displayName ?? person.name}</span>
            <label class="check small" title="Anrufe dieser Person nimmt der Rahmen selbst an">
              <input type="checkbox" bind:checked={contact.autoAnswer} onchange={() => (contactsDirty = true)} /> Auto-Annahme
            </label>
            <button class="link" onclick={() => move(index, -1)} disabled={index === 0} aria-label="nach oben">↑</button>
            <button class="link" onclick={() => move(index, 1)} disabled={index === contacts.length - 1} aria-label="nach unten">↓</button>
            <button
              class="link danger"
              onclick={() => {
                contactsDirty = true;
                contacts.splice(index, 1);
              }}>Ausblenden</button
            >
          </div>
        {/if}
      {/each}
      {#if hidden.length}
        <p class="muted small">Nicht auf diesem Rahmen:</p>
        {#each hidden as { person } (person.id)}
          <div class="contact row">
            <AvatarPicker
              personId={person.id}
              version={person.avatarVersion}
              name={person.displayName ?? person.name}
              size={36}
              {onChanged}
              onError={(message) => (error = message)}
            />
            <span class="grow muted">{person.displayName ?? person.name}</span>
            <button
              class="link"
              onclick={() => {
                contactsDirty = true;
                contacts.push({ personId: person.id, autoAnswer: false });
              }}>Anzeigen</button
            >
          </div>
        {/each}
      {/if}
    </div>
  </div>

  <div class="row">
    <button class="primary" onclick={save}>Speichern</button>
    {#if saved}<span class="muted">{saved}</span>{/if}
    {#if error}<span class="error">{error}</span>{/if}
    <button onclick={() => command("update")} disabled={!frame.device.online}>Nach Update suchen</button>
    {#if frame.device.status?.deviceOwner}
      <button onclick={() => command("maintenance")} disabled={!frame.device.online}>Wartungsmodus (10 min)</button>
      {#if confirmRelease}
        <span>Kiosk, Rechte und Startbildschirm zurücksetzen?</span>
        <button class="danger" onclick={() => command("release")}>Ja, Device Owner aufheben</button>
        <button onclick={() => (confirmRelease = false)}>Nein</button>
      {:else}
        <button onclick={() => (confirmRelease = true)} disabled={!frame.device.online}>Device Owner aufheben</button>
      {/if}
    {/if}
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
  .title {
    flex-wrap: nowrap;
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
