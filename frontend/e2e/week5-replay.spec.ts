import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";

test("Week 5: inspect a real permanent failure, safely replay after response loss, and restrict replay to maintainers", async ({ page, request }, testInfo) => {
  const suffix = crypto.randomUUID();
  const authResponse = await request.post("/api/auth/register", { data: {
    email: `week5-${suffix}@example.com`, password: `Week5-${suffix}`, displayName: "Retry acceptance",
  } });
  expect(authResponse.status()).toBe(201);
  const owner = await authResponse.json();
  const headers = { Authorization: `Bearer ${owner.accessToken}` };
  const projectResponse = await request.post("/api/projects", { headers, data: { name: "Week 5 failures" } });
  const project = await projectResponse.json();
  const inputResponse = await request.post(`/api/projects/${project.id}/inputs`, { headers, multipart: {
    file: { name: "h2.xyz", mimeType: "chemical/x-xyz", buffer: readFileSync(new URL("../../examples/h2.xyz", import.meta.url)) },
  } });
  expect(inputResponse.status()).toBe(201);
  const input = await inputResponse.json();
  const configResponse = await request.post(`/api/projects/${project.id}/configs`, { headers, data: {
    name: "Invalid basis acceptance", spec: { schemaVersion: 1, taskType: "pyscf.single_point", method: "RHF",
      basis: "labflow-invalid-basis", charge: 0, spin: 0, maxMemoryMb: 1024, timeoutSeconds: 30 },
  } });
  expect(configResponse.status()).toBe(201);
  const config = await configResponse.json();
  const jobResponse = await request.post("/api/jobs", { headers: { ...headers, "Idempotency-Key": suffix }, data: {
    projectId: project.id, molecularInputId: input.id, experimentConfigId: config.id,
  } });
  expect(jobResponse.status()).toBe(201);
  const original = await jobResponse.json();
  await page.addInitScript((token) => localStorage.setItem("labflow.accessToken", token), owner.accessToken);
  await page.goto("/");
  await expect(page.locator(".job-detail-heading .status-badge")).toHaveText("FAILED", { timeout: 90_000 });
  await expect(page.locator(".attempt-history table")).toContainText("FAILED");
  await page.screenshot({ path: testInfo.outputPath("week5-replay.png"), fullPage: true });
  const responses: { id: number; key: string }[] = [];
  await page.route(`**/api/jobs/${original.id}/replay`, async (route) => {
    const response = await route.fetch();
    expect(response.status()).toBe(201);
    responses.push({ id: (await response.json()).id, key: route.request().headers()["idempotency-key"]! });
    if (responses.length === 1) await route.abort("failed");
    else await route.fulfill({ response });
  });
  await page.getByRole("button", { name: "Replay as a new job" }).click();
  await expect(page.locator(".job-detail-card .inline-error").first()).toContainText("Could not reach");
  await page.getByRole("button", { name: "Replay as a new job" }).click();
  await expect(page.locator(".job-list .job-row")).toHaveCount(2);
  expect(responses).toHaveLength(2);
  expect(responses[0]).toEqual(responses[1]);
  expect(responses[0]!.id).not.toBe(original.id);
  const saved = await (await request.get(`/api/jobs/${original.id}`, { headers })).json();
  expect(saved.status).toBe("FAILED");
  expect(saved.attempts).toHaveLength(1);
  expect(saved.attempts[0].failure.retryable).toBe(false);

  const viewer = await (await request.post("/api/auth/register", { data: {
    email: `viewer-${suffix}@example.com`, password: `Week5-${suffix}`, displayName: "Failure viewer",
  } })).json();
  expect((await request.post(`/api/projects/${project.id}/members`, { headers, data: { email: viewer.user.email, role: "VIEWER" } })).status()).toBe(201);
  expect((await request.post(`/api/jobs/${original.id}/replay`, { headers: {
    Authorization: `Bearer ${viewer.accessToken}`, "Idempotency-Key": "denied",
  } })).status()).toBe(403);
  await page.unrouteAll({ behavior: "ignoreErrors" });
  await page.evaluate((token) => localStorage.setItem("labflow.accessToken", token), viewer.accessToken);
  await page.goto("/");
  await expect(page.locator(".job-list .job-row")).toHaveCount(2);
  await expect(page.getByRole("button", { name: "Replay as a new job" })).toHaveCount(0);
});
