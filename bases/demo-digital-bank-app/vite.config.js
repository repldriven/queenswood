import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    // 5174: the console holds 5173. strictPort refuses to shift to the
    // next free port, so an accidental second Vite surfaces as an error
    // rather than an app quietly served somewhere else.
    port: 5174,
    strictPort: true,
  },
});
