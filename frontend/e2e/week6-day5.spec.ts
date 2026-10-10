import { test, expect, type Page } from "@playwright/test";
import { demoJobs, demoProject, demoUser } from "../src/demo";

async function signedIn(page: Page, withJobs = false) {
  await page.addInitScript(() => localStorage.setItem("labflow.accessToken", "smoke-token"));
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    let json: unknown;
    if (path === "/api/auth/me") json = demoUser;
    else if (path === "/api/projects") json = [{ ...demoProject, currentUserRole: "OWNER" }];
    else if (path.endsWith("/jobs")) json = withJobs ? [demoJobs[0]] : [];
    else if (/\/api\/jobs\/\d+$/.test(path)) json = demoJobs[0];
    else json = [];
    await route.fulfill({ json });
  });
}

test("demo requires no API, preserves the real session, and explains takeover and comparison", async ({ page }, testInfo) => {
  const apiCalls: string[] = [];
  await page.addInitScript(() => localStorage.setItem("labflow.accessToken", "existing-session"));
  await page.route("**/api/**", async (route) => { apiCalls.push(route.request().url()); await route.abort(); });
  await page.goto("/?demo=1");
  await expect(page.getByLabel("Demo mode")).toContainText("Fictional sample data");
  await expect(page.locator(".attempt-history table")).toContainText("LOST");
  await expect(page.locator(".attempt-history table")).toContainText("sample-worker-b");
  await expect(page.locator(".result-provenance")).toContainText("Attempt 2 on sample-worker-b");
  await expect(page.getByRole("button", { name: "Create project", exact: true })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Cancel job", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Review submission" })).toHaveCount(0);
  for (const id of [1, 2]) await page.getByLabel(`Compare job #${id}`, { exact: true }).check();
  await page.getByRole("button", { name: "Compare selected jobs" }).click();
  await expect(page.locator(".comparison-difference").filter({ hasText: "Basis" })).toBeVisible();
  await page.getByLabel("Job status").selectOption("FAILED");
  await page.getByRole("button", { name: /^Job #3 / }).click();
  await expect(page.locator(".attempt-history")).toContainText("INVALID_BASIS");
  await page.reload();
  await expect(page.getByLabel("Job status")).toHaveValue("FAILED");
  await expect(page.locator(".job-detail-heading")).toContainText("Job #3");
  expect(await page.evaluate(() => localStorage.getItem("labflow.accessToken"))).toBe("existing-session");
  expect(apiCalls).toEqual([]);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: testInfo.outputPath("demo.png"), fullPage: true });
});

test("login offers demo access without an account", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Explore demo" }).click();
  await expect(page.getByLabel("Demo mode")).toBeVisible();
});

test("temporary session failure preserves the token and retry restores the workspace", async ({ page }) => {
  await signedIn(page);
  let failing = true;
  await page.route("**/api/auth/me", (route) => route.fulfill(failing ? { status: 503, json: { message: "API temporarily unavailable" } } : { json: demoUser }));
  await page.goto("/");
  await expect(page.getByRole("alert")).toContainText("temporarily unavailable");
  expect(await page.evaluate(() => localStorage.getItem("labflow.accessToken"))).toBe("smoke-token");
  failing = false;
  await page.getByRole("button", { name: "Retry session" }).click();
  await expect(page.getByRole("heading", { name: demoProject.name, exact: true })).toBeVisible();
});

test("expired session returns to sign in and removes the expired token", async ({ page }) => {
  await signedIn(page);
  await page.route("**/api/auth/me", (route) => route.fulfill({ status: 401, json: { message: "Expired" } }));
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Sign in", exact: true })).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem("labflow.accessToken"))).toBeNull();
});

test("workspace error hides misleading empty content and can be retried", async ({ page }) => {
  await signedIn(page);
  let failing = true;
  await page.route("**/api/projects/1/inputs", (route) => route.fulfill(failing ? { status: 503, json: { message: "Inputs unavailable" } } : { json: [] }));
  await page.goto("/");
  await expect(page.getByRole("alert")).toContainText("Inputs unavailable");
  await expect(page.getByText("No jobs yet", { exact: true })).toHaveCount(0);
  failing = false;
  await page.getByRole("button", { name: "Retry", exact: true }).click();
  await expect(page.getByText("No jobs yet", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: /^Inputs/ }).click();
  await expect(page.getByLabel("Molecular input file")).toBeAttached();
  await expect(page.getByRole("button", { name: /^Inputs/ })).toHaveAttribute("aria-pressed", "true");
});

test("refresh failure retains jobs and a dedicated retry clears the warning", async ({ page }) => {
  await signedIn(page, true);
  let failing = false;
  await page.route("**/api/projects/1/jobs", (route) => route.fulfill(failing ? { status: 503, json: { message: "Refresh unavailable" } } : { json: [demoJobs[0]] }));
  await page.goto("/");
  await expect(page.locator(".job-list .job-row")).toHaveCount(1);
  failing = true;
  await expect(page.getByText("Job list could not refresh.", { exact: true })).toBeVisible({ timeout: 10_000 });
  await expect(page.locator(".job-list .job-row")).toHaveCount(1);
  failing = false;
  await page.getByRole("button", { name: "Retry job list" }).click();
  await expect(page.getByText("Job list could not refresh.", { exact: true })).toHaveCount(0);
});

test("unavailable job details show an explicit retry without an endless skeleton", async ({ page }) => {
  await signedIn(page, true);
  let failing = true;
  await page.route("**/api/jobs/1", (route) => route.fulfill(failing ? { status: 404, json: { message: "Job not found" } } : { json: demoJobs[0] }));
  await page.goto("/");
  await expect(page.locator(".job-detail-card [role=alert]")).toContainText("Job not found");
  await expect(page.locator(".job-detail-card .skeleton-card")).toHaveCount(0);
  failing = false;
  await page.getByRole("button", { name: "Retry job details" }).click();
  await expect(page.locator(".job-timeline")).toContainText("RUNNING → SUCCEEDED");
});

test("project dialog supports keyboard focus containment, Escape and focus restoration", async ({ page }) => {
  await signedIn(page);
  await page.goto("/");
  const trigger = page.getByRole("button", { name: "Create project", exact: true });
  await trigger.click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByLabel("Project name")).toBeFocused();
  await dialog.getByRole("button", { name: "Create project", exact: true }).focus();
  await page.keyboard.press("Tab");
  await expect(dialog.getByRole("button", { name: "Close", exact: true })).toBeFocused();
  await page.keyboard.press("Shift+Tab");
  await expect(dialog.getByRole("button", { name: "Create project", exact: true })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();
});

test("browser reconnect resumes SSE cursor and suppresses duplicate logs", async ({ page }) => {
  await signedIn(page, true);
  const running = { ...demoJobs[0]!, status: "RUNNING", result: null, logs: [], canCancel: false };
  await page.route("**/api/jobs/1", (route) => route.fulfill({ json: running }));
  const cursors: string[] = [];
  const auth: string[] = [];
  const frame = (id: number) => `id:${id}\nevent:log\ndata:${JSON.stringify({ id, attemptId: 11, seqNo: id, stream: "STDOUT", emittedAt: "2026-01-01T12:00:00Z", content: `unique-chunk-${id}\n` })}\n\n`;
  await page.route("**/api/jobs/1/events", async (route) => {
    cursors.push(route.request().headers()["last-event-id"]!);
    auth.push(route.request().headers().authorization!);
    await route.fulfill({ contentType: "text/event-stream", body: cursors.at(-1) === "0" ? frame(101) : frame(101) + frame(102) });
  });
  await page.goto("/");
  await expect(page.getByLabel("Worker log", { exact: true })).toContainText("unique-chunk-102");
  expect(cursors).toContain("101");
  expect(auth.every((value) => value === "Bearer smoke-token")).toBe(true);
  const logs = await page.getByLabel("Worker log", { exact: true }).textContent();
  expect(logs?.match(/unique-chunk-101/g)).toHaveLength(1);
  expect(logs?.match(/unique-chunk-102/g)).toHaveLength(1);
  await expect(page.getByLabel("Worker log", { exact: true })).toHaveAttribute("tabindex", "0");
});

test("mobile demo keeps its banner and comparison inside the viewport", async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/?demo=1");
  await expect(page.getByLabel("Demo mode")).toBeVisible();
  for (const id of [1, 2]) await page.getByLabel(`Compare job #${id}`, { exact: true }).check();
  await page.getByRole("button", { name: "Compare selected jobs" }).click();
  await expect(page.locator(".comparison-card tbody")).toContainText("sample-image");
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.screenshot({ path: testInfo.outputPath("mobile-demo.png"), fullPage: true });
});
