import { defineConfig } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";

// Minimal Vite config — the showcase is a self-contained dev surface
// that imports directly from ../src/. Run with `yarn vite` (or
// `yarn dev` if you wire a script into ui's package.json).
export default defineConfig({
  root: import.meta.dirname,
  plugins: [svelte()],
  server: {
    // Pinned to 5176 so the showcase doesn't compete with console
    // (5173) or bank-app (5174) when run alongside. strictPort fails
    // fast if something's squatting — easier to spot than a silent
    // shift onto the next free port.
    port: 5176,
    strictPort: true,
    fs: {
      // Showcase is at ui/showcase/, components at ui/src/.
      // Allow Vite to reach the sibling src tree.
      allow: [".."],
    },
  },
});
