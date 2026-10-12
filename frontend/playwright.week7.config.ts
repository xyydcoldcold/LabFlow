import { defineConfig } from "@playwright/test";
import base from "./playwright.config";

export default defineConfig(base, {
  outputDir: "./test-results/week7",
  testIgnore: [],
  testMatch: ["week4.spec.ts", "week5-replay.spec.ts", "week6-real.spec.ts", "week7.spec.ts"],
  reporter: [["list"], ["json", { outputFile: "test-results/week7/report.json" }]],
  use: { baseURL: "http://127.0.0.1:13087" },
});
