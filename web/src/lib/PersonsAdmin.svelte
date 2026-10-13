<script lang="ts">
  import { api, type DeviceInfo, type InviteResponse, type PersonAdmin } from "../api";
  import { session } from "../session.svelte";
  import AvatarPicker from "./AvatarPicker.svelte";
  import Qr from "./Qr.svelte";

  let persons = $state<PersonAdmin[]>([]);
  let invite = $state<InviteResponse | null>(null);
  let error = $state("");
  let confirmDelete = $state<string | null>(null);

  async function load() {
    persons = await api.admin.persons();
  }

  $effect(() => {
    session.revision;
    load();
  });

  async function createInvite() {
    invite = await api.admin.invite();
  }

  async function run(action: () => Promise<void>) {
    error = "";
    try {
      await action();
      await load();
      session.revision++;
    } catch (e) {
      error = (e as Error).message === "last admin" ? "Es muss mindestens einen Admin geben." : (e as Error).message;
    }
  }

  function setDisplayName(entry: PersonAdmin, value: string) {
    return run(() => api.admin.updatePerson(entry.person.id, { displayName: value.trim() || null, isAdmin: entry.person.isAdmin }));
  }

  function setAdmin(entry: PersonAdmin, isAdmin: boolean) {
    return run(() => api.admin.updatePerson(entry.person.id, { displayName: entry.person.displayName, isAdmin }));
  }

  /** Hands the device to another person; a person left without devices is merged into the target. */
  function moveDevice(device: DeviceInfo, event: Event) {
    const select = event.currentTarget as HTMLSelectElement;
    const personId = select.value;
    select.value = "";
    if (personId) run(() => api.admin.moveDevice(device.id, personId));
  }

  const kindLabel: Record<DeviceInfo["kind"], string> = { phone: "Handy", desktop: "PC", frame: "Rahmen" };
</script>

<section class="stack">
  <div class="card stack">
    <h2>Person einladen</h2>
    <p class="muted">Die Person installiert CloseBy, öffnet den Link und legt Name und Bild selbst fest. Danach erscheint sie auf allen Rahmen.</p>
    {#if invite}
      <div class="row invite">
        <Qr value={invite.url} size={140} />
        <div class="stack">
          <span class="code">{invite.code}</span>
          <a href={invite.url} target="_blank" rel="noreferrer">{invite.url}</a>
          <span class="muted">Gültig bis {new Date(invite.expiresAt).toLocaleDateString("de-DE")}, einmal verwendbar.</span>
        </div>
      </div>
    {/if}
    <div class="row"><button class="primary" onclick={createInvite}>{invite ? "Neue Einladung" : "Einladung erstellen"}</button></div>
  </div>

  {#if error}<p class="error">{error}</p>{/if}

  {#each persons as entry (entry.person.id)}
    <article class="card stack">
      <div class="row">
        <AvatarPicker
          personId={entry.person.id}
          version={entry.person.avatarVersion}
          name={entry.person.name}
          size={48}
          onChanged={() => session.revision++}
          onError={(message) => (error = message)}
        />
        <div class="grow">
          <h2>{entry.person.name}{entry.person.id === session.me?.person?.id ? " (du)" : ""}</h2>
          <span class="muted">{entry.person.isAdmin ? "Admin" : "Kontakt"}</span>
        </div>
      </div>
      <div class="row">
        <label class="grow">
          Name auf den Rahmen (leer = eigener Name)
          <input
            type="text"
            value={entry.person.displayName ?? ""}
            placeholder={entry.person.name}
            maxlength="60"
            onchange={(e) => setDisplayName(entry, e.currentTarget.value)}
          />
        </label>
        <label class="check">
          <input type="checkbox" checked={entry.person.isAdmin} onchange={(e) => setAdmin(entry, e.currentTarget.checked)} /> Admin
        </label>
      </div>
      <div class="devices">
        {#each entry.devices as device (device.id)}
          <div class="row device">
            <span class="dot" class:on={device.online}></span>
            <span class="grow">{device.name} <span class="muted">· {kindLabel[device.kind]}</span></span>
            {#if persons.length > 1}
              <select class="move" value="" onchange={(e) => moveDevice(device, e)} title="Gerät einer anderen Person zuordnen">
                <option value="">Gehört zu …</option>
                {#each persons.filter((other) => other.person.id !== entry.person.id) as other (other.person.id)}
                  <option value={other.person.id}>{other.person.name}</option>
                {/each}
              </select>
            {/if}
            {#if device.id !== session.me?.device.id}
              <button class="link danger" onclick={() => run(() => api.admin.deleteDevice(device.id))}>Abmelden</button>
            {:else}
              <span class="muted">dieses Gerät</span>
            {/if}
          </div>
        {/each}
      </div>
      <div class="row">
        {#if confirmDelete === entry.person.id}
          <span>{entry.person.name} mit allen Geräten entfernen?</span>
          <button class="danger" onclick={() => run(() => api.admin.deletePerson(entry.person.id))}>Ja, entfernen</button>
          <button onclick={() => (confirmDelete = null)}>Nein</button>
        {:else}
          <button class="danger" onclick={() => (confirmDelete = entry.person.id)}>Person entfernen</button>
        {/if}
      </div>
    </article>
  {/each}
</section>

<style>
  .grow {
    flex: 1;
  }
  h2 {
    margin: 0;
  }
  .invite {
    align-items: flex-start;
  }
  .invite a {
    word-break: break-all;
  }
  .devices {
    display: grid;
    gap: 0.3rem;
  }
  .device {
    flex-wrap: nowrap;
  }
  .move {
    max-width: 11rem;
  }
</style>
