<script lang="ts">
  import { avatarUrl } from "../api";

  let { personId, version, name, size = 48 }: { personId: string; version?: number | null; name: string; size?: number } =
    $props();
  let src = $state<string | null>(null);

  $effect(() => {
    src = null;
    avatarUrl(personId, version).then((url) => (src = url));
  });

  const initials = $derived(
    name
      .split(" ")
      .map((part) => part[0])
      .join("")
      .slice(0, 2)
      .toUpperCase(),
  );
</script>

<span class="avatar" style:width="{size}px" style:height="{size}px" style:font-size="{size * 0.38}px">
  {#if src}<img {src} alt={name} />{:else}{initials}{/if}
</span>

<style>
  .avatar {
    display: inline-grid;
    place-content: center;
    flex: none;
    border-radius: 50%;
    overflow: hidden;
    background: var(--surface-2);
    color: var(--muted);
    font-weight: 600;
  }
  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
</style>
