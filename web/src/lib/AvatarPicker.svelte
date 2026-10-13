<script lang="ts">
  import { api } from "../api";
  import { squareJpeg } from "../image";
  import Avatar from "./Avatar.svelte";

  /** Admin: the picture every frame shows for this person. */
  let {
    personId,
    version,
    name,
    size = 48,
    onChanged,
    onError,
  }: {
    personId: string;
    version?: number | null;
    name: string;
    size?: number;
    onChanged: () => void;
    onError: (message: string) => void;
  } = $props();

  let busy = $state(false);

  async function pick(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    input.value = "";
    if (!file) return;
    busy = true;
    try {
      await api.admin.uploadPersonAvatar(personId, await squareJpeg(file, 512));
      onChanged();
    } catch (e) {
      onError((e as Error).message);
    } finally {
      busy = false;
    }
  }
</script>

<label class="picture" title="Bild ändern, das alle Rahmen für {name} zeigen">
  <Avatar {personId} {version} {name} {size} />
  <input type="file" accept="image/*" onchange={pick} disabled={busy} />
  <span class="change">{busy ? "lädt …" : "Bild ändern"}</span>
</label>

<style>
  .picture {
    display: flex;
    flex: none;
    flex-direction: column;
    align-items: center;
    gap: 0.2rem;
    cursor: pointer;
    font-size: 0.8rem;
  }
  .picture input {
    display: none;
  }
  .change {
    color: var(--accent);
    white-space: nowrap;
  }
</style>
