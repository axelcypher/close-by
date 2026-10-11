import { defineConfig } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";

// `npm run dev` proxies the API to a local backend (./gradlew :backend:run)
export default defineConfig({
  plugins: [svelte()],
  // livekit-client alone is ~550 kB; one bundle is fine for an installed app
  build: { chunkSizeWarningLimit: 800 },
  server: {
    proxy: {
      "/api": { target: "http://localhost:8080", ws: true },
    },
  },
});
