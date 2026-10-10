import { test, expect, type Page } from "@playwright/test";

// UI contract tests use API fixtures. Real HTTP/database execution remains in week4.spec.ts.
async function workspace(page: Page, role = "OWNER") {
  const user = { id: 1, email: "fixture@example.com", displayName: "UI Scientist", createdAt: "2026-10-08T00:00:00Z" };
  const projects = [1, 2].map((id) => ({ id, name: `Project ${id}`, owner: user, currentUserRole: role, createdAt: user.createdAt }));
  const inputs = [{ id: 1, originalFilename: "h2.xyz", sha256: "a".repeat(64), sizeBytes: 42, createdAt: user.createdAt }];
  const spec = { schemaVersion: 1, taskType: "pyscf.single_point", method: "RHF", basis: "sto-3g", charge: 0, spin: 0, maxMemoryMb: 1024, timeoutSeconds: 30 };
  const configs = [1, 2].map((id) => ({ id, name: "H2 baseline", version: id, spec, createdAt: user.createdAt }));
  const jobs = Array.from({ length: 24 }, (_, i) => ({ id: i + 1, projectId: 1, molecularInputId: 1, experimentConfigId: 1,
    status: i % 2 ? "SUCCEEDED" : "FAILED", createdAt: user.createdAt, updatedAt: user.createdAt,
    waitingSeconds: 40, runningSeconds: 20, timingMeasuredAt: user.createdAt })).reverse();
  await page.addInitScript(() => localStorage.setItem("labflow.accessToken", "ui-fixture-token"));
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    let json: unknown;
    if (path === "/api/auth/me") json = user;
    else if (path === "/api/projects") json = projects;
    else if (path.endsWith("/members")) json = [];
    else if (path.endsWith("/inputs")) json = inputs;
    else if (path.endsWith("/configs")) json = configs;
    else if (/\/projects\/\d+\/jobs$/.test(path)) json = jobs;
    else if (/\/api\/jobs\/\d+$/.test(path)) {
      const id = Number(path.split("/").at(-1));
      json = { ...jobs.find((job) => job.id === id), specSnapshot: {}, attempts: [], logs: [], result: null };
    } else throw new Error(`Unexpected fixture request: ${path}`);
    await route.fulfill({ json });
  });
  return { jobs };
}

test("wizard reviews the immutable version and reuses its visible key after response loss", async ({ page }) => {
  const { jobs } = await workspace(page);
  const submissions: { key: string; body: unknown }[] = [];
  await page.route("**/api/jobs", async (route) => {
    submissions.push({ key: route.request().headers()["idempotency-key"]!, body: route.request().postDataJSON() });
    await new Promise((resolve) => setTimeout(resolve, 250));
    if (submissions.length === 1) await route.abort("failed");
    else {
      const job = { ...jobs[0]!, id: 25, status: "SUCCEEDED" };
      jobs.push(job);
      await route.fulfill({ status: 201, json: job });
    }
  });
  await page.goto("/");
  await page.getByLabel("Configuration version").selectOption("2");
  await page.getByRole("button", { name: "Review submission" }).click();
  await expect(page.locator(".submission-review")).toContainText("H2 baseline · v2");
  const key = await page.getByLabel("Idempotency key").inputValue();
  expect(key).toMatch(/^[0-9a-f-]{36}$/);
  // Two synchronous submit events exercise the ref guard before React has rendered disabled.
  await page.locator(".job-submit-form").evaluate((form: HTMLFormElement) => { form.requestSubmit(); form.requestSubmit(); });
  await expect(page.getByRole("alert")).toContainText("Could not reach");
  expect(submissions).toHaveLength(1);
  await expect(page.getByLabel("Idempotency key")).toHaveValue(key);
  await page.getByRole("button", { name: "Retry submission" }).click();
  await expect(page.getByRole("button", { name: "Review submission" })).toBeVisible();
  expect(submissions).toHaveLength(2);
  expect(submissions[1]).toEqual(submissions[0]);
  expect(submissions[0]).toEqual({ key, body: { projectId: 1, molecularInputId: 1, experimentConfigId: 2 } });
  await page.getByRole("button", { name: "Review submission" }).click();
  expect(await page.getByLabel("Idempotency key").inputValue()).not.toBe(key);
});

test("status, search and pagination survive reload and browser Back", async ({ page }) => {
  await workspace(page);
  await page.goto("/?project=2&status=SUCCEEDED&page=2");
  await expect(page.getByRole("heading", { name: "Project 2", exact: true })).toBeVisible();
  await expect(page.locator(".job-list .job-row")).toHaveCount(2);
  await expect(page.getByText("Page 2 of 2")).toBeVisible();
  await expect(page.locator(".job-row").first()).toContainText("Waiting 40s · Running 20s");
  await page.reload();
  await expect(page.getByLabel("Job status")).toHaveValue("SUCCEEDED");
  await expect(page.getByText("Page 2 of 2")).toBeVisible();
  await page.getByRole("button", { name: "Previous", exact: true }).click();
  await expect(page.locator(".job-list .job-row")).toHaveCount(10);
  await page.goBack();
  await expect(page.getByText("Page 2 of 2")).toBeVisible();
  await page.getByLabel("Search job ID").fill("#24");
  await expect(page.locator(".job-list .job-row")).toHaveCount(1);
  await expect(page).toHaveURL(/q=%2324/);
  await page.reload();
  await expect(page.getByLabel("Search job ID")).toHaveValue("#24");
  await page.getByLabel("Search job ID").fill("999");
  await expect(page.getByText("No matching jobs", { exact: true })).toBeVisible();
  await expect(page.getByText("Select a job", { exact: true })).toBeVisible();
  await expect(page.locator(".job-detail-card .skeleton-card")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Next", exact: true })).toBeDisabled();
});

test("changing project clears list filters and browser Back restores them", async ({ page }) => {
  await workspace(page);
  await page.goto("/?project=1&status=FAILED&page=2");
  await page.getByRole("combobox", { name: "Project", exact: true }).selectOption("2");
  await expect(page.getByRole("heading", { name: "Project 2", exact: true })).toBeVisible();
  await expect(page.getByLabel("Job status")).toHaveValue("ALL");
  await page.goBack();
  await expect(page.getByRole("heading", { name: "Project 1", exact: true })).toBeVisible();
  await expect(page.getByLabel("Job status")).toHaveValue("FAILED");
  await expect(page.getByText("Page 2 of 2")).toBeVisible();
});

test("viewer can filter and page jobs without submission controls", async ({ page }) => {
  await workspace(page, "VIEWER");
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Review submission" })).toHaveCount(0);
  await page.getByLabel("Job status").selectOption("FAILED");
  await expect(page.locator(".job-list .job-row")).toHaveCount(10);
  await page.getByRole("button", { name: "Next", exact: true }).click();
  await expect(page.locator(".job-list .job-row")).toHaveCount(2);
});

test("mobile wizard and filters fit the viewport", async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await workspace(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Review submission" }).click();
  await expect(page.getByLabel("Idempotency key")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.screenshot({ path: testInfo.outputPath("week6-mobile.png"), fullPage: true });
});

function savedDetails(job: { id: number; status: string }, basis = "sto-3g") {
  return { ...job, canCancel: false, cancelRequestedAt: null,
    specSnapshot: { molecularInput: { originalFilename: "h2.xyz", sha256: "a".repeat(64) }, experimentConfig: { name: "H2 baseline", version: 1, spec: { method: "RHF", basis, charge: 0, spin: 0 } } },
    attempts: [{ id: 1, attemptNo: 1, status: "LOST", workerId: 1, workerInstance: "worker-a", startedAt: "2026-10-08T00:00:00Z", finishedAt: "2026-10-08T00:00:20Z", failure: { code: "LEASE_EXPIRED", message: "Worker stopped renewing" } },
      { id: 2, attemptNo: 2, status: "SUCCEEDED", workerId: 2, workerInstance: "worker-b", startedAt: "2026-10-08T00:00:40Z", finishedAt: "2026-10-08T00:01:00Z", failure: null }],
    events: [{ id: 1, fromStatus: null, toStatus: "QUEUED", eventType: "JOB_SUBMITTED", details: {}, createdAt: "2026-10-08T00:00:00Z" },
      { id: 2, fromStatus: "RUNNING", toStatus: "QUEUED", eventType: "ATTEMPT_LOST", details: { attemptId: 1 }, createdAt: "2026-10-08T00:00:20Z" },
      { id: 3, fromStatus: "RUNNING", toStatus: "SUCCEEDED", eventType: "JOB_COMPLETED", details: {}, createdAt: "2026-10-08T00:01:00Z" }],
    logs: [], result: { summary: { energyHartree: -1.1167593074, durationSeconds: 0.7 }, manifest: { environment: { python: "3.13", packages: { pyscf: "2.14" }, workerImageDigest: "sha256:test", specSha256: "b".repeat(64) } } } };
}

test("detail shows recorded timeline, takeover attempts and environment", async ({ page }) => {
  const { jobs } = await workspace(page);
  await page.route("**/api/jobs/24", (route) => route.fulfill({ json: savedDetails(jobs[0]!) }));
  await page.goto("/");
  await expect(page.locator(".job-timeline")).toContainText("RUNNING → QUEUED");
  await expect(page.locator(".attempt-history table")).toContainText("worker-a");
  await expect(page.locator(".attempt-history table")).toContainText("worker-b");
  await expect(page.locator(".attempt-history table")).toContainText("LEASE_EXPIRED");
  await expect(page.locator(".environment-summary")).toContainText("sha256:test");
  await expect(page.getByRole("button", { name: "Cancel job", exact: true })).toHaveCount(0);
});

test("comparison selection spans pages, enforces 2–5, and highlights saved differences", async ({ page }, testInfo) => {
  const { jobs } = await workspace(page);
  await page.route(/\/api\/jobs\/\d+$/, (route) => {
    const id = Number(new URL(route.request().url()).pathname.split("/").at(-1));
    return route.fulfill({ json: savedDetails(jobs.find((job) => job.id === id)!, id === 24 ? "6-31g" : "sto-3g") });
  });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Compare selected jobs" })).toBeDisabled();
  await expect(page.getByLabel("Compare job #23", { exact: true })).toBeDisabled();
  for (const id of [24, 22, 20, 18, 16]) await page.getByLabel(`Compare job #${id}`, { exact: true }).check();
  await page.getByRole("button", { name: "Next", exact: true }).click();
  await expect(page.getByLabel("Compare job #14", { exact: true })).toBeDisabled();
  await page.getByRole("button", { name: "Compare selected jobs" }).click();
  await expect(page.locator(".comparison-card thead th")).toHaveCount(6);
  await expect(page.locator(".comparison-difference").filter({ hasText: "Basis" })).toContainText("6-31g");
  await expect(page.locator(".comparison-warning")).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("week6-comparison.png"), fullPage: true });
  await page.getByRole("button", { name: "Clear selection" }).click();
  await expect(page.locator(".comparison-card")).toHaveCount(0);
  await expect(page.getByLabel("Compare job #14", { exact: true })).toBeEnabled();
});

test("comparison request errors can be retried and missing values are explicit", async ({ page }) => {
  const { jobs } = await workspace(page);
  let fail = true;
  await page.route("**/api/jobs/22", (route) => {
    if (fail) return route.fulfill({ status: 503, json: { message: "Result temporarily unavailable" } });
    return route.fulfill({ json: { ...savedDetails(jobs.find((job) => job.id === 22)!), specSnapshot: {}, result: { summary: {}, manifest: {} } } });
  });
  await page.route("**/api/jobs/24", (route) => route.fulfill({ json: savedDetails(jobs[0]!) }));
  await page.goto("/");
  await page.getByLabel("Compare job #24", { exact: true }).check();
  await page.getByLabel("Compare job #22", { exact: true }).check();
  await page.getByRole("button", { name: "Compare selected jobs" }).click();
  await expect(page.locator(".comparison-card [role=alert]")).toContainText("temporarily unavailable");
  fail = false;
  await page.getByRole("button", { name: "Retry comparison" }).click();
  await expect(page.locator(".comparison-card tbody")).toContainText("—");
});

test("running cancellation retries safely and waits for worker acknowledgement", async ({ page }) => {
  const { jobs } = await workspace(page);
  let requested = false;
  let requests = 0;
  const running = { ...savedDetails(jobs[0]!), status: "RUNNING", canCancel: true, result: null };
  await page.route("**/api/jobs/24", (route) => route.fulfill({ json: { ...running, cancelRequestedAt: requested ? "2026-10-08T00:02:00Z" : null } }));
  await page.route("**/api/jobs/24/events", (route) => route.fulfill({ contentType: "text/event-stream", body: ":keepalive\n\n" }));
  await page.route("**/api/jobs/24/cancel", async (route) => {
    requests++;
    if (requests === 1) return route.abort("failed");
    requested = true;
    await route.fulfill({ json: { jobId: 24, status: "RUNNING", cancelRequestedAt: "2026-10-08T00:02:00Z" } });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Cancel job", exact: true }).click();
  await expect(page.locator(".job-detail-card [role=alert]")).toContainText("Could not reach");
  await page.getByRole("button", { name: "Cancel job", exact: true }).click();
  await expect(page.locator(".cancellation-note")).toContainText("Waiting for the worker to stop");
  await expect(page.getByRole("button", { name: "Cancel job", exact: true })).toHaveCount(0);
  expect(requests).toBe(2);
});

test("mobile comparison scrolls inside its table without widening the page", async ({ page }, testInfo) => {
  const { jobs } = await workspace(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.route(/\/api\/jobs\/\d+$/, (route) => {
    const id = Number(new URL(route.request().url()).pathname.split("/").at(-1));
    return route.fulfill({ json: savedDetails(jobs.find((job) => job.id === id)!) });
  });
  await page.goto("/");
  for (const id of [24, 22]) await page.getByLabel(`Compare job #${id}`, { exact: true }).check();
  await page.getByRole("button", { name: "Compare selected jobs" }).click();
  await expect(page.locator(".comparison-card tbody")).toContainText("sha256:test");
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.screenshot({ path: testInfo.outputPath("mobile-comparison.png"), fullPage: true });
});
