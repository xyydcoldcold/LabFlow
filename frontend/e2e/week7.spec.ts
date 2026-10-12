import { test, expect, type APIRequestContext } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { readFileSync, writeFileSync } from "node:fs";

const root = fileURLToPath(new URL("../..", import.meta.url));
const project = process.env.LABFLOW_E2E_PROJECT ?? "";
const backend = "http://127.0.0.1:18087";
const workerAuth = { Authorization: "Bearer week7-isolated-worker-service-token" };

function compose(...args: string[]): string {
  // These tests stop workers and change one test job's retry budget. Never target the development stack.
  if (!/^labflow-w7-[a-z0-9-]+$/.test(project)) throw new Error("Use scripts/week7_acceptance.py with an isolated labflow-w7-* project");
  return execFileSync("docker", ["compose", "--env-file", "/dev/null", "--project-directory", root, "-p", project,
    "-f", `${root}/tests/e2e/compose.yml`, "-f", `${root}/docker-compose.observability.yml`, ...args],
    { encoding: "utf8", timeout: 60_000 });
}

function sql(statement: string): string {
  return compose("exec", "-T", "postgres", "psql", "-U", "labflow", "-d", "labflow", "-At", "-c", statement).trim();
}

async function fixture(request: APIRequestContext, seconds: number) {
  const suffix = crypto.randomUUID();
  const auth = await request.post("/api/auth/register", { data: {
    email: `week7-${suffix}@example.com`, password: `Week7-${suffix}`, displayName: "Week 7 acceptance",
  } });
  expect(auth.status()).toBe(201);
  const owner = await auth.json();
  const headers = { Authorization: `Bearer ${owner.accessToken}` };
  const created = await request.post("/api/projects", { headers, data: { name: "Week 7 reliability" } });
  expect(created.status()).toBe(201);
  const project = await created.json();
  const uploaded = await request.post(`/api/projects/${project.id}/inputs`, { headers, multipart: {
    file: { name: "h2.xyz", mimeType: "chemical/x-xyz", buffer: readFileSync(new URL("../../examples/h2.xyz", import.meta.url)) },
  } });
  expect(uploaded.status()).toBe(201);
  const input = await uploaded.json();
  const configuration = await request.post(`/api/projects/${project.id}/configs`, { headers, data: {
    name: "Bounded demo", spec: { schemaVersion: 1, taskType: "demo.sleep_hash", sleepSeconds: seconds,
      maxMemoryMb: 1024, timeoutSeconds: 60 },
  } });
  expect(configuration.status()).toBe(201);
  const config = await configuration.json();
  return { headers, owner, body: { projectId: project.id, molecularInputId: input.id, experimentConfigId: config.id } };
}

test("Week 7: browser cancellation stops a real running worker and leaves no result", async ({ page, request }, info) => {
  const f = await fixture(request, 30);
  const submitted = await request.post("/api/jobs", { headers: { ...f.headers, "Idempotency-Key": crypto.randomUUID() }, data: f.body });
  expect(submitted.status()).toBe(201);
  const job = await submitted.json();
  await expect.poll(async () => (await (await request.get(`/api/jobs/${job.id}`, { headers: f.headers })).json()).status).toBe("RUNNING");
  await page.addInitScript((token) => localStorage.setItem("labflow.accessToken", token), f.owner.accessToken);
  await page.goto("/");
  await expect(page.locator(".job-detail-heading .status-badge")).toHaveText("RUNNING");
  await page.getByRole("button", { name: "Cancel job", exact: true }).click();
  await expect(page.locator(".job-detail-heading .status-badge")).toHaveText("CANCELLED");
  await expect(page.locator(".attempt-history table")).toContainText("CANCELLED");
  const saved = await (await request.get(`/api/jobs/${job.id}`, { headers: f.headers })).json();
  expect(saved.cancelRequestedAt).toBeTruthy();
  expect(saved.attempts).toHaveLength(1);
  expect(saved.attempts[0].status).toBe("CANCELLED"); // Worker acknowledgement, not lease-loss fallback.
  expect(saved.result).toBeNull();
  expect(sql(`SELECT COUNT(*) FROM job_results WHERE job_id = ${job.id}`)).toBe("0");
  await page.screenshot({ path: info.outputPath("live-cancellation.png"), fullPage: true });
  const evidence = { jobId: job.id, attemptId: saved.attempts[0].id, workerId: saved.attempts[0].workerId,
    status: saved.status, attemptStatus: saved.attempts[0].status, resultRows: 0 };
  writeFileSync(info.outputPath("cancellation.json"), JSON.stringify(evidence, null, 2));
});

test("Week 7: exhausted transient failure reaches the real DLQ and replay executes exactly once", async ({ page, request }, info) => {
  compose("stop", "worker-a", "worker-b");
  try {
    const f = await fixture(request, 0);
    const submitted = await request.post("/api/jobs", { headers: { ...f.headers, "Idempotency-Key": crypto.randomUUID() }, data: f.body });
    expect(submitted.status()).toBe(201);
    const original = await submitted.json();
    expect(Number.isSafeInteger(original.id)).toBe(true);
    // Test-only fault injection: exercise exhaustion without waiting through 15+60+300 second tiers.
    sql(`UPDATE jobs SET max_attempts = 1 WHERE id = ${original.id}`);
    const registration = await request.post(`${backend}/internal/workers/register`, { headers: workerAuth,
      data: { instanceName: `week7-failure-driver-${original.id}`, imageDigest: "test-driver", capabilities: ["demo.sleep_hash"] } });
    expect(registration.ok()).toBe(true);
    const worker = await registration.json();
    const claimed = await request.post(`${backend}/internal/jobs/${original.id}/claim`, { headers: workerAuth, data: { workerId: worker.workerId } });
    expect(claimed.ok()).toBe(true);
    const attempt = await claimed.json();
    await page.addInitScript((token) => {
      localStorage.setItem("labflow.accessToken", token);
      const actualFetch = window.fetch.bind(window);
      const events: { cursor: string; authenticated: boolean }[] = [];
      (window as unknown as { week7Streams: typeof events }).week7Streams = events;
      window.fetch = async (input, init) => {
        const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
        if (!/\/api\/jobs\/\d+\/events$/.test(url)) return actualFetch(input, init);
        const headers = new Headers(init?.headers);
        events.push({ cursor: headers.get("Last-Event-ID") ?? "0", authenticated: headers.has("Authorization") });
        const response = await actualFetch(input, init);
        if (events.length !== 1 || !response.ok || !response.body) return response;
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let frame = "";
        // Drop the real transport after one actual event. Never manufacture API responses or log data.
        return new Response(new ReadableStream({
          async pull(controller) {
            const chunk = await reader.read();
            if (chunk.done) { controller.close(); return; }
            controller.enqueue(chunk.value);
            frame += decoder.decode(chunk.value, { stream: true }).replace(/\r\n/g, "\n");
            if (frame.includes("\n\n")) { await reader.cancel(); controller.close(); }
          },
          cancel() { return reader.cancel(); },
        }), { status: response.status, headers: response.headers });
      };
    }, f.owner.accessToken);
    await page.goto("/");
    await expect(page.locator(".job-detail-heading .status-badge")).toHaveText("RUNNING");
    const attemptHeaders = { ...workerAuth, "X-Attempt-Token": attempt.attemptToken };
    const logUrl = `${backend}/internal/attempts/${attempt.attemptId}/logs`;
    const firstLogResponse = await request.post(logUrl, { headers: attemptHeaders,
      data: { seqNo: 0, stream: "STDOUT", content: "before real SSE disconnect\n", emittedAt: new Date().toISOString() } });
    expect(firstLogResponse.status()).toBe(201);
    const firstLog = await firstLogResponse.json();
    await expect(page.locator(".job-log")).toContainText("before real SSE disconnect");
    await expect.poll(async () => page.evaluate(() =>
      (window as unknown as { week7Streams: { cursor: string; authenticated: boolean }[] }).week7Streams
        .some((stream, index) => index > 0 && stream.authenticated && stream.cursor !== "0")))
      .toBe(true);
    const streamRequests = await page.evaluate(() =>
      (window as unknown as { week7Streams: { cursor: string; authenticated: boolean }[] }).week7Streams);
    expect(streamRequests[1]!.cursor).toBe(String(firstLog.id));
    expect((await request.post(logUrl, { headers: attemptHeaders,
      data: { seqNo: 1, stream: "STDOUT", content: "after real SSE reconnect\n", emittedAt: new Date().toISOString() } })).status()).toBe(201);
    await expect(page.locator(".job-log")).toContainText("after real SSE reconnect");
    expect((await page.locator(".job-log").innerText()).match(/before real SSE disconnect/g)).toHaveLength(1);
    const failure = await request.post(`${backend}/internal/attempts/${attempt.attemptId}/fail`, {
      headers: { ...workerAuth, "X-Attempt-Token": attempt.attemptToken },
      data: { error: { code: "NETWORK_ERROR", message: "Injected transient failure at the attempt limit" } },
    });
    expect(failure.ok()).toBe(true);
    const rabbitHeaders = { Authorization: `Basic ${Buffer.from("labflow:week7-isolated-test-password").toString("base64")}` };
    const queueUrl = "http://127.0.0.1:15687/api/queues/%2F/labflow.jobs.dlq";
    // Management statistics update asynchronously; inspect queue content without removing its signal.
    await expect.poll(async () => {
      const response = await request.post(`${queueUrl}/get`, { headers: rabbitHeaders,
        data: { count: 100, ackmode: "ack_requeue_true", encoding: "auto", truncate: 10000 } });
      expect(response.ok()).toBe(true);
      const messages = await response.json();
      return messages.some((message: { payload: string }) => JSON.parse(message.payload).jobId === original.id);
    }).toBe(true);

    await page.goto("/");
    await expect(page.locator(".job-detail-heading .status-badge")).toHaveText("FAILED");
    await expect(page.locator(".attempt-history table")).toContainText("NETWORK_ERROR");
    const replays: { id: number; key: string }[] = [];
    await page.route(`**/api/jobs/${original.id}/replay`, async (route) => {
      const response = await route.fetch();
      expect(response.status()).toBe(201);
      replays.push({ id: (await response.json()).id, key: route.request().headers()["idempotency-key"]! });
      if (replays.length === 1) await route.abort("failed"); else await route.fulfill({ response });
    });
    await page.getByRole("button", { name: "Replay as a new job" }).click();
    await expect(page.locator(".job-detail-card .inline-error").first()).toContainText("Could not reach");
    await page.getByRole("button", { name: "Replay as a new job" }).click();
    await expect(page.locator(".job-list .job-row")).toHaveCount(2);
    expect(replays).toHaveLength(2);
    expect(replays[0]).toEqual(replays[1]);
    const replayId = replays[0]!.id;
    expect(replayId).not.toBe(original.id);
    compose("start", "worker-a", "worker-b");
    await expect(page.locator(".job-detail-heading .status-badge")).toHaveText("SUCCEEDED");
    const replay = await (await request.get(`/api/jobs/${replayId}`, { headers: f.headers })).json();
    const failed = await (await request.get(`/api/jobs/${original.id}`, { headers: f.headers })).json();
    expect(replay.attempts).toHaveLength(1);
    expect(replay.result.summary.sha256).toBe(replay.specSnapshot.molecularInput.sha256);
    expect(failed.status).toBe("FAILED");
    expect(failed.attempts).toHaveLength(1);
    expect(failed.attempts[0].failure.retryable).toBe(true);
    expect(failed.result).toBeNull();
    expect(sql(`SELECT COUNT(*) FROM job_results WHERE job_id = ${replayId}`)).toBe("1");
    expect(sql(`SELECT replayed_from_job_id FROM jobs WHERE id = ${replayId}`)).toBe(String(original.id));
    expect(sql(`SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ${original.id} AND event_type = 'JOB_DEAD'`)).toBe("1");
    await page.screenshot({ path: info.outputPath("dlq-replay.png"), fullPage: true });
    writeFileSync(info.outputPath("dlq-replay.json"), JSON.stringify({ failedJobId: original.id, replayJobId: replayId,
      dlqSignalObserved: true, sameKeyReplayDeduplicated: true, replayStatus: replay.status, resultRows: 1,
      browserSseResumedAfter: firstLog.id, browserReconnectAuthenticated: true }, null, 2));
  } finally {
    compose("start", "worker-a", "worker-b");
  }
});

test("Week 7: authenticated scraping, correlated JSON logs and provisioned monitoring work", async ({ page, request }, info) => {
  expect((await request.get(`${backend}/actuator/prometheus`)).status()).toBe(401);
  expect((await request.get(`${backend}/actuator/prometheus`, { headers: workerAuth })).status()).toBe(401);
  const f = await fixture(request, 0);
  expect((await request.get(`${backend}/actuator/prometheus`, { headers: f.headers })).status()).toBe(401);
  const metrics = await request.get(`${backend}/actuator/prometheus`, { headers: { Authorization: "Bearer labflow-local-metrics-token-change-me" } });
  expect(metrics.ok()).toBe(true);
  const text = await metrics.text();
  expect(text).toContain("labflow_job_events_total");
  expect(text).toContain("labflow_jobs{status=");
  expect(text).toContain("labflow_outbox_pending");
  expect(text).toContain("http_server_requests_seconds_bucket");
  expect(text).not.toMatch(/\{[^}]*\b(jobId|attemptId|workerId|requestId)=/);
  await expect.poll(async () => {
    const response = await request.get('http://127.0.0.1:19090/api/v1/query?query=up%7Bjob%3D%22labflow-backend%22%7D');
    const data = await response.json();
    return data.data?.result?.[0]?.value?.[1];
  }).toBe("1");
  const id = "week7-correlation-check";
  const unauthorized = await request.get(`${backend}/api/auth/me`, { headers: { "X-Request-ID": id } });
  expect(unauthorized.status()).toBe(401);
  expect(unauthorized.headers()["x-request-id"]).toBe(id);
  expect((await unauthorized.json()).requestId).toBe(id);
  const logs = compose("logs", "--no-color", "backend").split("\n").flatMap((line) => {
    const index = line.indexOf("{");
    if (index < 0) return [];
    try { return [JSON.parse(line.slice(index))]; } catch { return []; }
  });
  expect(logs.some((log) => log.requestId === id)).toBe(true);
  expect(logs.some((log) => log.event === "CLAIMED" && log.jobId && log.attemptId && log.workerId && log.requestId)).toBe(true);
  await page.goto("http://127.0.0.1:13000/login");
  await page.getByLabel("Email or username").fill("admin");
  await page.getByLabel("Password", { exact: true }).fill("labflow-local-admin-change-me");
  const login = page.waitForResponse((response) => response.url().endsWith("/login") && response.request().method() === "POST");
  await page.getByRole("button", { name: "Log in", exact: true }).click();
  expect((await login).status()).toBe(200);
  await expect(page).not.toHaveURL(/\/login$/);
  await page.setViewportSize({ width: 1440, height: 2200 });
  const jobsQuery = page.waitForResponse((response) => {
    if (!response.url().includes("/api/ds/query") || response.request().method() !== "POST") return false;
    const body = response.request().postDataJSON();
    return body?.queries?.some((query: { expr?: string }) => query.expr === "labflow_jobs");
  });
  await page.goto("http://127.0.0.1:13000/d/labflow-operations");
  await expect(page.getByText("LabFlow operations", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("Jobs by state", { exact: true })).toBeVisible();
  const queried = await jobsQuery;
  expect(queried.ok()).toBe(true);
  expect((await queried.json()).results.A.frames.length).toBeGreaterThan(0);
  await expect(page.getByRole("button", { name: "Cancel", exact: true })).toHaveCount(0);
  await page.screenshot({ path: info.outputPath("monitoring.png"), fullPage: true });
  writeFileSync(info.outputPath("observability.json"), JSON.stringify({ scrapeUp: true, metricsAuthenticated: true,
    idsAbsentFromLabels: true, correlationVerified: true, dashboardUid: "labflow-operations" }, null, 2));
});
