export type ProjectRole = "OWNER" | "MAINTAINER" | "MEMBER" | "VIEWER";
export type MembershipRole = Exclude<ProjectRole, "OWNER">;

export interface UserSummary {
  id: number;
  email: string;
  displayName: string;
}

export interface AuthUser extends UserSummary {
  createdAt: string;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: "Bearer";
  expiresIn: number;
  user: AuthUser;
}

export interface Project {
  id: number;
  name: string;
  owner: UserSummary;
  currentUserRole: ProjectRole;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectMember {
  user: UserSummary;
  role: MembershipRole;
  joinedAt: string;
  updatedAt: string;
}

export interface MolecularInput {
  id: number;
  originalFilename: string;
  sha256: string;
  sizeBytes: number;
  createdAt: string;
}

export interface ExperimentSpec {
  schemaVersion: 1;
  taskType: "pyscf.single_point";
  method: string;
  basis: string;
  charge: number;
  spin: number;
  maxMemoryMb: number;
  timeoutSeconds: number;
}

export interface ExperimentConfig {
  id: number;
  name: string;
  version: number;
  spec: ExperimentSpec;
  createdBy: number;
  createdAt: string;
}

export type JobStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";

export interface JobSummary {
  id: number; projectId: number; molecularInputId: number; experimentConfigId: number;
  status: JobStatus; createdAt: string; updatedAt: string;
}

export interface JobAttempt {
  id: number; attemptNo: number; status: string; workerId: number; workerInstance: string;
  startedAt: string; finishedAt: string | null; failure: Record<string, unknown> | null;
}

export interface JobLogChunk {
  id: number; attemptId: number; seqNo: number; stream: "STDOUT" | "STDERR" | "SYSTEM";
  emittedAt: string; content: string;
}

export interface JobResult {
  attemptId: number; summary: Record<string, unknown>; manifest: Record<string, unknown>; completedAt: string;
}

export interface JobDetails extends JobSummary {
  specSnapshot: {
    molecularInput?: { originalFilename?: string; sha256?: string };
    experimentConfig?: { name?: string; version?: number; spec?: ExperimentSpec };
  };
  attempts: JobAttempt[];
  logs: JobLogChunk[];
  result: JobResult | null;
}

interface ApiErrorBody {
  status?: number;
  code?: string;
  message?: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

interface RequestOptions extends Omit<RequestInit, "body"> {
  token?: string;
  body?: unknown;
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.token) {
    headers.set("Authorization", `Bearer ${options.token}`);
  }

  let body: BodyInit | undefined;
  if (options.body instanceof FormData) {
    body = options.body;
  } else if (options.body !== undefined) {
    headers.set("Content-Type", "application/json");
    body = JSON.stringify(options.body);
  }

  let response: Response;
  try {
    response = await fetch(path, { ...options, headers, body });
  } catch {
    throw new ApiError(0, "NETWORK_ERROR", "Could not reach the LabFlow API.");
  }

  if (!response.ok) {
    let error: ApiErrorBody = {};
    try {
      error = (await response.json()) as ApiErrorBody;
    } catch {
      // The status text is used when an upstream proxy does not return JSON.
    }
    throw new ApiError(
      response.status,
      error.code ?? "REQUEST_FAILED",
      error.message ?? response.statusText ?? "Request failed",
    );
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export function formatDate(value: string): string {
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
  }).format(new Date(value));
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
