<script lang="ts">
  import QRCode from "qrcode";

  let { value, size = 200 }: { value: string; size?: number } = $props();
  let src = $state("");

  $effect(() => {
    QRCode.toDataURL(value, { width: size, margin: 1 }).then((url) => (src = url));
  });
</script>

{#if src}<img {src} width={size} height={size} alt="QR-Code" />{/if}

<style>
  img {
    border-radius: 8px;
    background: #fff;
  }
</style>
