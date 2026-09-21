import { defineConfig } from "@playwright/test";

// The app's end-to-end walk, run against an app, its bank and a platform
// already up: `just monolith-start`, `just demo-digital-bank-start` and
// `just demo-digital-bank-app-start`. Every run is filmed, which is how
// the README's walkthrough video is made. The viewport is tall enough
// for the app to draw its phone frame on the stage.
export default defineConfig({
  testDir: "e2e",
  timeout: 20 * 60 * 1000,
  retries: 0,
  workers: 1,
  reporter: "list",
  outputDir: "test-results",
  use: {
    baseURL: process.env.DEMO_APP_URL ?? "http://localhost:5174",
    viewport: { width: 1200, height: 1000 },
    video: { mode: "on", size: { width: 1200, height: 1000 } },
    trace: "off",
  },
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
});
