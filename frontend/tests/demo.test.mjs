import assert from "node:assert/strict";
import { test } from "node:test";
import { demoResponse } from "../src/demo.ts";
test("demo is read-only and cannot return a real resource or mutate fixtures", () => {
  assert.throws(() => demoResponse("/api/jobs", "POST"), /read-only/);
  assert.throws(() => demoResponse("/api/jobs/999"), /not found/);
  const job = demoResponse("/api/jobs/1");
  job.attempts[0].status = "MUTATED";
  assert.equal(demoResponse("/api/jobs/1").attempts[0].status, "LOST");
  assert.equal(demoResponse("/api/jobs/1").result.manifest.sampleData, true);
});
