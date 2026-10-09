import { test, expect } from "@playwright/test";
import { readFileSync, writeFileSync } from "node:fs";

test("Week 6: compare two real successful PySCF jobs and inspect recorded execution history", async ({ page, request }, testInfo) => {
  const suffix = crypto.randomUUID();
  const registration = await request.post("/api/auth/register", { data: { email: `week6-${suffix}@example.com`, password: `Week6-${suffix}`, displayName: "Comparison acceptance" } });
  expect(registration.status()).toBe(201);
  const user = await registration.json();
  const headers = { Authorization: `Bearer ${user.accessToken}` };
  const projectResponse = await request.post("/api/projects", { headers, data: { name: "H2 basis comparison" } });
  expect(projectResponse.status()).toBe(201);
  const project = await projectResponse.json();
  const inputResponse = await request.post(`/api/projects/${project.id}/inputs`, { headers, multipart: { file: { name: "h2.xyz", mimeType: "chemical/x-xyz", buffer: readFileSync(new URL("../../examples/h2.xyz", import.meta.url)) } } });
  expect(inputResponse.status()).toBe(201);
  const input = await inputResponse.json();
  const ids: number[] = [];
  for (const basis of ["sto-3g", "6-31g"]) {
    const configResponse = await request.post(`/api/projects/${project.id}/configs`, { headers, data: { name: `H2 ${basis}`, spec: { schemaVersion: 1, taskType: "pyscf.single_point", method: "RHF", basis, charge: 0, spin: 0, maxMemoryMb: 1024, timeoutSeconds: 30 } } });
    expect(configResponse.status()).toBe(201);
    const config = await configResponse.json();
    const submitted = await request.post("/api/jobs", { headers: { ...headers, "Idempotency-Key": crypto.randomUUID() }, data: { projectId: project.id, molecularInputId: input.id, experimentConfigId: config.id } });
    expect(submitted.status()).toBe(201);
    ids.push((await submitted.json()).id);
  }
  for (const id of ids) {
    await expect.poll(async () => (await (await request.get(`/api/jobs/${id}`, { headers })).json()).status, { timeout: 90_000 }).toBe("SUCCEEDED");
  }
  await page.addInitScript((token) => localStorage.setItem("labflow.accessToken", token), user.accessToken);
  await page.goto(`/?project=${project.id}`);
  await expect(page.locator(".job-timeline")).toContainText("QUEUED → RUNNING");
  await expect(page.locator(".job-timeline")).toContainText("RUNNING → SUCCEEDED");
  await expect(page.locator(".attempt-history table")).toContainText("SUCCEEDED");
  await expect(page.locator(".environment-summary")).toContainText("pyscf");
  await expect(page.getByRole("button", { name: "Cancel job", exact: true })).toHaveCount(0);
  for (const id of ids) await page.getByLabel(`Compare job #${id}`, { exact: true }).check();
  await page.getByRole("button", { name: "Compare selected jobs" }).click();
  await expect(page.locator(".comparison-card tbody tr").filter({ hasText: "Basis" })).toContainText("sto-3g");
  await expect(page.locator(".comparison-card tbody tr").filter({ hasText: "Basis" })).toContainText("6-31g");
  await expect(page.locator(".comparison-difference").filter({ hasText: "Energy" })).toBeVisible();
  await expect(page.locator(".comparison-card")).toContainText("-1.116759307");
  await expect(page.locator(".comparison-warning")).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("real-comparison.png"), fullPage: true });
  const results = await Promise.all(ids.map(async (id) => {
    const details = await (await request.get(`/api/jobs/${id}`, { headers })).json();
    return { jobId: id, status: details.status, basis: details.specSnapshot.experimentConfig.spec.basis, energyHartree: details.result.summary.energyHartree, attemptCount: details.attempts.length, transitions: details.events.map((event: { toStatus: string }) => event.toStatus) };
  }));
  writeFileSync(testInfo.outputPath("comparison-evidence.json"), JSON.stringify(results, null, 2));
});
