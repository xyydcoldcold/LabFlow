import { test, expect } from "@playwright/test";
import { readFileSync, writeFileSync } from "node:fs";

test("Week 4: upload H2, select a config, safely retry submission, and inspect a real PySCF result", async ({ page, request }, testInfo) => {
  const suffix = crypto.randomUUID();
  const streamRequests: string[] = [];
  page.on("request", (request) => { if (/\/api\/jobs\/\d+\/events$/.test(request.url())) streamRequests.push(request.url()); });
  await page.goto("/");
  await page.getByRole("button", { name: "Create an account", exact: true }).click();
  await page.getByLabel("Display name").fill("Week 4 Scientist");
  await page.getByLabel("Email address").fill(`week4-${suffix}@example.com`);
  await page.getByLabel("Password", { exact: true }).fill(`Week4-${suffix}`);
  await page.getByRole("button", { name: "Create account", exact: true }).click();
  await page.getByRole("button", { name: "Create your first project" }).click();
  await page.getByLabel("Project name").fill(`H2 acceptance ${suffix.slice(0, 8)}`);
  await page.getByRole("dialog").getByRole("button", { name: "Create project", exact: true }).click();
  await expect(page.getByText("Upload a molecular input in Inputs", { exact: false })).toBeVisible();

  await page.getByRole("button", { name: /^Inputs/ }).click();
  await page.locator('input[type="file"]').setInputFiles({ name: "h2.xyz", mimeType: "text/plain", buffer: readFileSync(new URL("../../examples/h2.xyz", import.meta.url)) });
  await page.getByRole("button", { name: "Upload input", exact: true }).click();
  await expect(page.locator(".data-row")).toContainText("h2.xyz");
  await page.getByRole("button", { name: /^Configurations/ }).click();
  await page.getByLabel("Configuration name").fill("H2 baseline");
  await page.getByRole("button", { name: "Create immutable version" }).click();
  await expect(page.locator(".config-card")).toContainText("H2 baseline");
  await page.getByRole("button", { name: /^Jobs/ }).click();
  await expect(page.getByLabel("Configuration version")).toContainText("v1 · RHF/sto-3g");

  const submissions: { key: string; job: { id: number; projectId: number; status: string } }[] = [];
  await page.route("**/api/jobs", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    const response = await route.fetch();
    expect(response.status()).toBe(201);
    submissions.push({ key: route.request().headers()["idempotency-key"]!, job: await response.json() });
    // Simulate losing the first response after the backend already committed the job.
    if (submissions.length === 1) await route.abort("failed");
    else await route.fulfill({ response });
  });
  await page.getByRole("button", { name: "Review submission", exact: true }).click();
  await page.getByRole("button", { name: "Submit job", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Could not reach");
  await page.getByRole("button", { name: "Retry submission", exact: true }).click();
  await expect(page.locator(".job-detail-heading .status-badge")).toHaveText(/SUCCEEDED|FAILED|CANCELLED/, { timeout: 90_000 });
  await expect(page.locator(".job-detail-heading .status-badge")).toHaveText("SUCCEEDED");
  expect(submissions).toHaveLength(2);
  expect(submissions[0]!.job.status).toBe("QUEUED");
  expect(submissions[1]!.job.id).toBe(submissions[0]!.job.id);
  expect(submissions[1]!.key).toBe(submissions[0]!.key);
  expect(streamRequests.length).toBeGreaterThan(0);
  await expect(page.locator(".result-panel")).toContainText("-1.1167593074 Eh");
  await expect(page.locator(".job-log")).toContainText("SCF finished: converged=True");
  await expect(page.locator(".job-log")).not.toContainText("def main()");
  await page.getByText("Reproducibility manifest", { exact: true }).click();
  await expect(page.locator(".manifest pre")).toContainText('"pyscf"');
  await expect(page.locator(".manifest pre")).toContainText('"specSha256"');

  const token = await page.evaluate(() => localStorage.getItem("labflow.accessToken"));
  const auth = { Authorization: `Bearer ${token}` };
  const jobId = submissions[0]!.job.id;
  const detailResponse = await request.get(`/api/jobs/${jobId}`, { headers: auth });
  expect(detailResponse.ok()).toBeTruthy();
  const detail = await detailResponse.json();
  expect(detail.attempts).toHaveLength(1);
  expect(detail.attempts[0].status).toBe("SUCCEEDED");
  expect(detail.result.summary.converged).toBe(true);
  expect(detail.result.summary.energyHartree).toBeCloseTo(-1.1167593074, 8);
  expect(detail.result.manifest.artifacts[0].sha256).toBe(detail.specSnapshot.molecularInput.sha256);
  const list = await request.get(`/api/projects/${detail.projectId}/jobs`, { headers: auth });
  expect(await list.json()).toHaveLength(1);

  // Verify resumable SSE through the same production Nginx proxy as the browser.
  const cursor = detail.logs[0].id;
  const controller = new AbortController();
  const stream = await fetch(`${testInfo.project.use.baseURL}/api/jobs/${jobId}/events`, {
    headers: { ...auth, "Last-Event-ID": String(cursor) }, signal: AbortSignal.any([controller.signal, AbortSignal.timeout(15_000)]),
  });
  expect(stream.ok).toBeTruthy();
  expect(stream.headers.get("content-type")).toContain("text/event-stream");
  const reader = stream.body!.getReader();
  let replay = "";
  const decoder = new TextDecoder();
  try {
    while (!replay.includes("\n\n")) {
      const chunk = await reader.read();
      if (chunk.done) break;
      replay += decoder.decode(chunk.value, { stream: true }).replace(/\r\n/g, "\n");
    }
    const replayId = Number(replay.match(/^id:\s*(\d+)/m)?.[1]);
    expect(replayId).toBe(detail.logs[1].id);
    expect(replayId).toBeGreaterThan(cursor);
  } finally { controller.abort(); await reader.cancel().catch(() => undefined); }
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: testInfo.outputPath("h2-result.png"), fullPage: true });
  const evidencePath = testInfo.outputPath("acceptance.json");
  writeFileSync(evidencePath, JSON.stringify({ jobId, status: detail.status, energyHartree: detail.result.summary.energyHartree, attemptCount: detail.attempts.length, logChunks: detail.logs.length, sseResumedAfter: cursor, browserStreamRequests: streamRequests.length }, null, 2));
  await testInfo.attach("acceptance", { path: evidencePath, contentType: "application/json" });
});

test("Viewers can inspect the workspace but cannot submit a job", async ({ page, request }) => {
  const suffix = crypto.randomUUID();
  async function register(role: string) {
    const response = await request.post("/api/auth/register", { data: {
      email: `week4-${role}-${suffix}@example.com`, password: `Week4-${suffix}`, displayName: `Week 4 ${role}`,
    } });
    expect(response.status()).toBe(201);
    return response.json();
  }
  const owner = await register("owner");
  const viewer = await register("viewer");
  const ownerAuth = { Authorization: `Bearer ${owner.accessToken}` };
  const projectResponse = await request.post("/api/projects", { headers: ownerAuth, data: { name: "Viewer acceptance" } });
  expect(projectResponse.status()).toBe(201);
  const project = await projectResponse.json();
  const membership = await request.post(`/api/projects/${project.id}/members`, { headers: ownerAuth, data: { email: viewer.user.email, role: "VIEWER" } });
  expect(membership.status()).toBe(201);
  await page.addInitScript((token) => localStorage.setItem("labflow.accessToken", token), viewer.accessToken);
  await page.goto("/");
  await expect(page.getByText("Your viewer role can inspect jobs and results.", { exact: false })).toBeVisible();
  await expect(page.getByRole("button", { name: "Submit job", exact: true })).toHaveCount(0);
  const forbidden = await request.post("/api/jobs", {
    headers: { Authorization: `Bearer ${viewer.accessToken}`, "Idempotency-Key": crypto.randomUUID() },
    data: { projectId: project.id, molecularInputId: 1, experimentConfigId: 1 },
  });
  expect(forbidden.status()).toBe(403);
});
