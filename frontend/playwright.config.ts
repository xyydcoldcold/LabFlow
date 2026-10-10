import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  outputDir: "./test-results/e2e",
  testIgnore: ["week6.spec.ts", "week6-day5.spec.ts"],
  timeout: 120_000,
  expect: { timeout: 30_000 },
  workers: 1,
  use: {
    baseURL: process.env.LABFLOW_E2E_URL ?? "http://localhost:3000",
    viewport: { width: 1440, height: 1000 },
    screenshot: "only-on-failure",
    // Traces can contain credentials; keep them disabled for this acceptance flow.
    trace: "off",
  },
});
