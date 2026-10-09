import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  testMatch: "week6.spec.ts",
  timeout: 30_000,
  workers: 1,
  use: { baseURL: "http://127.0.0.1:5173", viewport: { width: 1440, height: 1000 }, trace: "off", screenshot: "only-on-failure" },
  webServer: { command: "npm run dev -- --host 127.0.0.1 --port 5173 --strictPort", url: "http://127.0.0.1:5173", reuseExistingServer: false },
});
