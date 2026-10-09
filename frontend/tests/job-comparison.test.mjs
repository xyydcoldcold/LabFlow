import assert from "node:assert/strict";
import { test } from "node:test";
import { comparisonRows, display } from "../src/job-comparison.ts";
const job = (basis, energy) => ({ specSnapshot: { molecularInput: { sha256: "same-input" }, experimentConfig: { spec: { method: "RHF", basis, charge: 0, spin: 0 } } }, result: { summary: { energyHartree: energy, durationSeconds: 0 }, manifest: { environment: { workerImageDigest: "sha256:image" } } } });
test("comparison highlights underlying differences without rounding away energy precision", () => {
  const rows = comparisonRows([job("sto-3g", -1.1), job("6-31g", -1.10000000001)]);
  assert.equal(rows.find((r) => r.label === "Basis").different, true);
  assert.equal(rows.find((r) => r.label === "Energy").different, true);
  assert.equal(rows.find((r) => r.label === "Input SHA-256").different, false);
  assert.deepEqual(rows.find((r) => r.label === "Wall time").values, [0, 0]);
});
test("missing manifest and summary data stay missing and zero remains a value", () => {
  const rows = comparisonRows([job("sto-3g", 0), { specSnapshot: {}, result: { summary: {}, manifest: {} } }]);
  assert.deepEqual(rows.find((r) => r.label === "Energy").values, [0, undefined]);
  assert.equal(display(undefined), "—");
  assert.equal(display(0), "0");
  assert.equal(rows.find((r) => r.label === "Worker image digest").different, true);
});
