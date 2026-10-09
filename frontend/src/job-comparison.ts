import type { JobDetails } from "./api.ts";

export function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}
export function display(value: unknown): string {
  if (value === null || value === undefined || value === "") return "—";
  return typeof value === "object" ? JSON.stringify(value) : String(value);
}
export interface ComparisonRow { label: string; values: unknown[]; different: boolean; unit?: string }
export function comparisonRows(jobs: JobDetails[]): ComparisonRow[] {
  const fields: { label: string; get: (job: JobDetails) => unknown; unit?: string }[] = [
    { label: "Method", get: (j) => j.specSnapshot.experimentConfig?.spec?.method },
    { label: "Basis", get: (j) => j.specSnapshot.experimentConfig?.spec?.basis },
    { label: "Input SHA-256", get: (j) => j.specSnapshot.molecularInput?.sha256 },
    { label: "Charge", get: (j) => j.specSnapshot.experimentConfig?.spec?.charge },
    { label: "Spin", get: (j) => j.specSnapshot.experimentConfig?.spec?.spin },
    { label: "Energy", unit: "Eh", get: (j) => j.result?.summary.energyHartree },
    { label: "Wall time", unit: "s", get: (j) => j.result?.summary.durationSeconds ?? record(j.result?.manifest.task).durationSeconds },
    { label: "Worker image digest", get: (j) => record(j.result?.manifest.environment).workerImageDigest },
  ];
  return fields.map(({ label, get, unit }) => {
    const values = jobs.map(get);
    return { label, unit, values, different: new Set(values.map((value) => JSON.stringify(value ?? null))).size > 1 };
  });
}
