import { defineConfig } from "@playwright/test";

// The console's end-to-end tests, run against a console and a platform
// already up: `just monolith-start` and `just console-start`. Every run
// is filmed, which is how the README's scenario video is made.
export default defineConfig({
  testDir: "e2e",
  timeout: 20 * 60 * 1000,
  retries: 0,
  workers: 1,
  reporter: "list",
  outputDir: "test-results",
  use: {
    baseURL: process.env.CONSOLE_URL ?? "http://localhost:5173",
    viewport: { width: 1440, height: 900 },
    colorScheme: "light",
    video: { mode: "on", size: { width: 1440, height: 900 } },
    trace: "off",
  },
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
});
