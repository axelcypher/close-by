<script lang="ts">
  import { session } from "../session.svelte";
  import Avatar from "./Avatar.svelte";
  import CallFrames from "./CallFrames.svelte";
  import FramesAdmin from "./FramesAdmin.svelte";
  import PersonsAdmin from "./PersonsAdmin.svelte";
  import Profile from "./Profile.svelte";

  type Tab = "call" | "frames" | "persons" | "profile";
  let tab = $state<Tab>("call");

  const me = $derived(session.me!);
  const isAdmin = $derived(me.person?.isAdmin ?? false);
  const tabs = $derived<[Tab, string][]>([
    ["call", "Anrufen"],
    ...(isAdmin ? ([["frames", "Rahmen"], ["persons", "Personen"]] as [Tab, string][]) : []),
    ["profile", "Profil"],
  ]);
</script>

<div class="layout">
  <header>
    <div class="brand">
      <img src="/icon.svg" alt="" width="32" height="32" />
      <strong>CloseBy</strong>
    </div>
    <nav>
      {#each tabs as [id, label] (id)}
        <button class:active={tab === id} onclick={() => (tab = id)}>{label}</button>
      {/each}
    </nav>
    <div class="me">
      <span class="dot" class:on={session.connected} title={session.connected ? "Verbunden" : "Keine Verbindung"}></span>
      {#if me.person}
        <Avatar personId={me.person.id} version={me.person.avatarVersion} name={me.person.name} size={32} />
        <span>{me.person.name}</span>
      {/if}
    </div>
  </header>

  <main>
    {#if tab === "call"}
      <CallFrames />
    {:else if tab === "frames"}
      <FramesAdmin />
    {:else if tab === "persons"}
      <PersonsAdmin />
    {:else}
      <Profile />
    {/if}
  </main>
</div>

<style>
  .layout {
    min-height: 100vh;
  }
  header {
    position: sticky;
    top: 0;
    z-index: 5;
    display: flex;
    align-items: center;
    gap: 1rem;
    flex-wrap: wrap;
    padding: 0.7rem 1.2rem;
    background: var(--surface);
    border-bottom: 1px solid var(--line);
  }
  .brand,
  .me {
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }
  .brand img {
    border-radius: 8px;
  }
  nav {
    display: flex;
    gap: 0.3rem;
    flex: 1;
  }
  nav button {
    border: none;
    background: none;
    color: var(--muted);
  }
  nav button.active {
    background: var(--surface-2);
    color: var(--text);
    font-weight: 600;
  }
  main {
    max-width: 60rem;
    margin: 0 auto;
    padding: 1.5rem 1.2rem 3rem;
  }
</style>
