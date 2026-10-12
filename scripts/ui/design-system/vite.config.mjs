import { defineConfig } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";

// One classic script and one stylesheet from entry.js. The recipe passes
// --outDir.
export default defineConfig({
  root: import.meta.dirname,
  plugins: [svelte({ configFile: false })],
  define: { "process.env.NODE_ENV": '"production"' },
  build: {
    emptyOutDir: true,
    minify: true,
    cssCodeSplit: false,
    // Leave the stylesheet unminified: Lightning CSS rewrites light-dark()
    // into variables only its own color-scheme output sets, and every tint
    // a component declares that way drops out.
    cssMinify: false,
    cssTarget: ["chrome125", "safari18", "firefox128"],
    lib: {
      entry: "entry.js",
      name: "Queenswood",
      formats: ["iife"],
      fileName: () => "bundle.js",
      cssFileName: "bundle",
    },
  },
});
