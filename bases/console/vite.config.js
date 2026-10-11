import { defineConfig } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";

export default defineConfig({
  plugins: [svelte()],
  server: {
    // Pinned to 5173 — matches the queenswood realm's whitelisted
    // redirect URI for the queenswood-console client. strictPort
    // refuses to auto-shift to 5174+ when 5173 is occupied, so an
    // accidental second Vite surfaces as a clear error instead of a
    // silent "redirect_uri_mismatch" from Keycloak.
    port: 5173,
    strictPort: true,
    // Allow Vite to serve `?raw` imports from the repo's docs/ tree
    // (one level above this brick's project root).
    fs: { allow: ["../.."] },
    proxy: {
      "/v1": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/oauth": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/.well-known": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/openapi.json": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
