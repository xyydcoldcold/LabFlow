import assert from "node:assert/strict";
import { test } from "node:test";
import { filterJobs, readJobFilters, jobTiming, formatDuration } from "../src/job-list.ts";

test("URL filters reject invalid status and page values", () => {
  assert.deepEqual(readJobFilters("?status=FAILED&q=%2342&page=2"), { status: "FAILED", query: "#42", page: 2 });
  for (const page of ["0", "-1", "1.5", "NaN", "Infinity", "9007199254740992"]) {
    assert.deepEqual(readJobFilters(`?status=LOST&page=${page}`), { status: "ALL", query: "", page: 1 });
  }
});

test("filter and newest-first ordering are stable when creation times tie", () => {
  const jobs = [1, 2, 42, 43].map((id) => ({ id, status: id === 43 ? "FAILED" : "SUCCEEDED", createdAt: "2026-10-08T00:00:00Z" }));
  assert.deepEqual(filterJobs(jobs, { status: "SUCCEEDED", query: "#4", page: 1 }).map((job) => job.id), [42]);
  assert.deepEqual(filterJobs(jobs, { status: "ALL", query: "", page: 1 }).map((job) => job.id), [43, 42, 2, 1]);
});

test("live timing advances only the current phase and terminal durations remain fixed", () => {
  const job = { waitingSeconds: 40, runningSeconds: 20, timingMeasuredAt: "2026-10-08T00:00:00Z" };
  const now = Date.parse("2026-10-08T00:00:05Z");
  assert.deepEqual(jobTiming({ ...job, status: "QUEUED" }, now), { waiting: 45, running: 20 });
  assert.deepEqual(jobTiming({ ...job, status: "RUNNING" }, now), { waiting: 40, running: 25 });
  assert.deepEqual(jobTiming({ ...job, status: "SUCCEEDED" }, now), { waiting: 40, running: 20 });
  assert.equal(formatDuration(3661), "1h 1m");
});
