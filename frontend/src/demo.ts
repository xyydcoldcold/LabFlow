import type { AuthUser, Project, MolecularInput, ExperimentConfig, JobDetails, JobSummary } from "./api.ts";

export function isDemoMode(): boolean {
  return typeof window !== "undefined" && new URLSearchParams(window.location.search).get("demo") === "1";
}
export const demoUser: AuthUser = { id: 1, email: "scientist@example.invalid", displayName: "Demo Scientist", createdAt: "2026-01-01T12:00:00Z" };
export const demoProject: Project = { id: 1, name: "H₂ sample experiments", owner: demoUser, currentUserRole: "VIEWER", createdAt: demoUser.createdAt, updatedAt: demoUser.createdAt };
const input: MolecularInput = { id: 1, originalFilename: "h2.xyz", sha256: "a".repeat(64), sizeBytes: 41, createdAt: demoUser.createdAt };
const configs: ExperimentConfig[] = ["sto-3g", "6-31g", "sample-invalid-basis"].map((basis, i) => ({ id: i + 1, name: `H₂ ${basis}`, version: 1, createdBy: 1, createdAt: demoUser.createdAt, spec: { schemaVersion: 1, taskType: "pyscf.single_point", method: "RHF", basis, charge: 0, spin: 0, maxMemoryMb: 1024, timeoutSeconds: 300 } }));
function makeJob(id: number, recovered: boolean, failed = false): JobDetails {
  const config = configs[id - 1]!;
  const start = "2026-01-01T12:00:10Z", lost = "2026-01-01T12:00:40Z", takeover = "2026-01-01T12:00:50Z", end = "2026-01-01T12:01:00Z";
  const finalStatus = failed ? "FAILED" : "SUCCEEDED";
  return { id, projectId: 1, molecularInputId: 1, experimentConfigId: config.id, status: finalStatus, createdAt: demoUser.createdAt, updatedAt: end,
    canCancel: false, cancelRequestedAt: null, waitingSeconds: recovered ? 20 : 10, runningSeconds: recovered ? 40 : 50, timingMeasuredAt: end,
    specSnapshot: { molecularInput: input, experimentConfig: config },
    attempts: [...(recovered ? [{ id: id * 10, attemptNo: 1, status: "LOST", workerId: 1, workerInstance: "sample-worker-a", startedAt: start, finishedAt: lost, failure: { code: "LEASE_EXPIRED", message: "Sample worker stopped renewing its lease." } }] : []),
      { id: id * 10 + 1, attemptNo: recovered ? 2 : 1, status: finalStatus, workerId: recovered ? 2 : 1, workerInstance: recovered ? "sample-worker-b" : "sample-worker-a", startedAt: recovered ? takeover : start, finishedAt: end, failure: failed ? { code: "INVALID_BASIS", message: "Fictional example of a permanent configuration failure.", retryable: false } : null }],
    events: [{ id: 1, fromStatus: null, toStatus: "QUEUED", eventType: "JOB_CREATED", details: {}, createdAt: demoUser.createdAt },
      { id: 2, fromStatus: "QUEUED", toStatus: "RUNNING", eventType: "JOB_CLAIMED", details: { workerId: 1 }, createdAt: start },
      ...(recovered ? [{ id: 3, fromStatus: "RUNNING" as const, toStatus: "QUEUED" as const, eventType: "ATTEMPT_LOST", details: { attemptId: id * 10, reason: "LEASE_EXPIRED" }, createdAt: lost },
        { id: 4, fromStatus: "QUEUED" as const, toStatus: "RUNNING" as const, eventType: "JOB_CLAIMED", details: { workerId: 2 }, createdAt: takeover }] : []),
      { id: 5, fromStatus: "RUNNING", toStatus: finalStatus, eventType: failed ? "JOB_FAILED" : "JOB_SUCCEEDED", details: {}, createdAt: end }],
    logs: [{ id: id * 100, attemptId: id * 10 + 1, seqNo: 0, stream: "SYSTEM", emittedAt: end, content: failed ? "Fictional sample: configuration validation failed.\n" : "Fictional sample: calculation completed; one saved result.\n" }],
    result: failed ? null : { attemptId: id * 10 + 1, completedAt: end, summary: { energyHartree: id === 2 ? -1.1267553172 : -1.1167593074, converged: true, durationSeconds: recovered ? 10 : 50 }, manifest: { sampleData: true, environment: { python: "sample-version", workerImageDigest: "sample-image", packages: { pyscf: "sample-version" }, specSha256: "b".repeat(64) }, artifacts: [{ role: "input", sha256: input.sha256 }] } },
  };
}
export const demoJobs: JobDetails[] = [makeJob(1, true), makeJob(2, false), makeJob(3, false, true)];
export function demoResponse(path: string, method = "GET"): unknown {
  if (method !== "GET") throw new Error("Demo mode is read-only.");
  if (path === "/api/auth/me") return structuredClone(demoUser);
  if (path === "/api/projects") return structuredClone([demoProject]);
  if (path === "/api/projects/1/members") return [];
  if (path === "/api/projects/1/inputs") return structuredClone([input]);
  if (path === "/api/projects/1/configs") return structuredClone(configs);
  if (path === "/api/projects/1/jobs") return demoJobs.map(({ id, projectId, molecularInputId, experimentConfigId, status, createdAt, updatedAt, waitingSeconds, runningSeconds, timingMeasuredAt }): JobSummary => ({ id, projectId, molecularInputId, experimentConfigId, status, createdAt, updatedAt, waitingSeconds, runningSeconds, timingMeasuredAt }));
  const match = /^\/api\/jobs\/(\d+)$/.exec(path);
  const job = match && demoJobs.find((item) => item.id === Number(match[1]));
  if (job) return structuredClone(job);
  throw new Error("Sample resource not found.");
}
