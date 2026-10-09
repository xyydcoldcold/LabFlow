import type { JobSummary, JobStatus } from "./api.ts";

export const jobStatuses: JobStatus[] = ["QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"];
export const PAGE_SIZE = 10;
export interface JobFilters { status: JobStatus | "ALL"; query: string; page: number }

export function readJobFilters(search: string): JobFilters {
  const params = new URLSearchParams(search);
  const status = params.get("status") as JobStatus;
  const page = Number(params.get("page") ?? 1);
  return { status: jobStatuses.includes(status) ? status : "ALL", query: params.get("q") ?? "",
    page: Number.isSafeInteger(page) && page > 0 ? page : 1 };
}

export function filterJobs(jobs: JobSummary[], filters: JobFilters): JobSummary[] {
  const query = filters.query.trim().toLowerCase().replace(/^#/, "");
  return jobs.filter((job) => (filters.status === "ALL" || job.status === filters.status)
    && (!query || String(job.id).includes(query) || `job #${job.id}`.includes(query)))
    .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt) || b.id - a.id);
}

export function jobTiming(job: JobSummary, now: number): { waiting: number; running: number } {
  const elapsed = job.timingMeasuredAt ? Math.max(0, (now - Date.parse(job.timingMeasuredAt)) / 1000) : 0;
  return { waiting: (job.waitingSeconds ?? 0) + (job.status === "QUEUED" ? elapsed : 0),
    running: (job.runningSeconds ?? 0) + (job.status === "RUNNING" ? elapsed : 0) };
}

export function formatDuration(seconds: number): string {
  const value = Math.max(0, Math.floor(seconds));
  if (value < 60) return `${value}s`;
  if (value < 3600) return `${Math.floor(value / 60)}m ${value % 60}s`;
  return `${Math.floor(value / 3600)}h ${Math.floor(value % 3600 / 60)}m`;
}
