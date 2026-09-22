import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";

import {
  ApiError,
  apiRequest,
  AuthResponse,
  AuthUser,
  ExperimentConfig,
  ExperimentSpec,
  formatBytes,
  formatDate,
  MembershipRole,
  MolecularInput,
  JobDetails,
  JobSummary,
  Project,
  ProjectMember,
} from "./api";
import "./styles.css";

type WorkspaceTab = "jobs" | "inputs" | "configs" | "members";

const TOKEN_KEY = "labflow.accessToken";
const membershipRoles: MembershipRole[] = ["MAINTAINER", "MEMBER", "VIEWER"];

function messageFrom(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  return "Something went wrong. Please try again.";
}

export function App() {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem(TOKEN_KEY));
  const [user, setUser] = useState<AuthUser | null>(null);
  const [projects, setProjects] = useState<Project[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState<number | null>(null);
  const [booting, setBooting] = useState(token !== null);

  const signOut = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    setToken(null);
    setUser(null);
    setProjects([]);
    setSelectedProjectId(null);
  }, []);

  const loadProjects = useCallback(async (activeToken: string, preferredId?: number) => {
    const visibleProjects = await apiRequest<Project[]>("/api/projects", { token: activeToken });
    setProjects(visibleProjects);
    setSelectedProjectId((current) => {
      const requested = preferredId ?? current;
      return visibleProjects.some((project) => project.id === requested)
        ? (requested ?? null)
        : (visibleProjects[0]?.id ?? null);
    });
  }, []);

  useEffect(() => {
    if (!token || user) {
      setBooting(false);
      return;
    }
    let cancelled = false;
    Promise.all([
      apiRequest<AuthUser>("/api/auth/me", { token }),
      apiRequest<Project[]>("/api/projects", { token }),
    ])
      .then(([currentUser, visibleProjects]) => {
        if (cancelled) return;
        setUser(currentUser);
        setProjects(visibleProjects);
        setSelectedProjectId(visibleProjects[0]?.id ?? null);
      })
      .catch(() => {
        if (!cancelled) signOut();
      })
      .finally(() => {
        if (!cancelled) setBooting(false);
      });
    return () => {
      cancelled = true;
    };
  }, [signOut, token, user]);

  function establishSession(response: AuthResponse) {
    localStorage.setItem(TOKEN_KEY, response.accessToken);
    setToken(response.accessToken);
    setUser(response.user);
    void loadProjects(response.accessToken);
  }

  if (booting) return <LoadingScreen />;
  if (!token || !user) return <AuthScreen onAuthenticated={establishSession} />;

  const selectedProject = projects.find((project) => project.id === selectedProjectId) ?? null;
  return (
    <Dashboard
      token={token}
      user={user}
      projects={projects}
      selectedProject={selectedProject}
      onSelectProject={setSelectedProjectId}
      onProjectsChanged={loadProjects}
      onSignOut={signOut}
    />
  );
}

function LoadingScreen() {
  return <main className="loading-screen"><Brand /><div className="spinner" aria-label="Loading LabFlow" /></main>;
}

function Brand() {
  return (
    <div className="brand" aria-label="LabFlow">
      <span className="brand-mark" aria-hidden="true"><i /><i /><i /></span>
      <span>LabFlow</span>
    </div>
  );
}

function AuthScreen({ onAuthenticated }: { onAuthenticated: (response: AuthResponse) => void }) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      const response = await apiRequest<AuthResponse>(`/api/auth/${mode}`, {
        method: "POST",
        body: mode === "register" ? { email, password, displayName } : { email, password },
      });
      onAuthenticated(response);
    } catch (requestError) {
      setError(messageFrom(requestError));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="auth-shell">
      <section className="auth-story">
        <Brand />
        <div className="story-copy">
          <span className="eyebrow">Reproducible science, by design</span>
          <h1>Every experiment has a history worth preserving.</h1>
          <p>Keep molecular inputs, validated configurations, and your laboratory team in one reliable workspace.</p>
        </div>
        <div className="molecule-graphic" aria-hidden="true">
          <span className="atom atom-a" /><span className="bond bond-a" />
          <span className="atom atom-b" /><span className="bond bond-b" />
          <span className="atom atom-c" /><span className="bond bond-c" />
          <span className="atom atom-d" />
        </div>
        <p className="story-footnote">Built for traceable, team-based computational chemistry.</p>
      </section>
      <section className="auth-panel">
        <div className="auth-card">
          <div className="auth-mobile-brand"><Brand /></div>
          <span className="eyebrow">{mode === "login" ? "Welcome back" : "Create your workspace"}</span>
          <h2>{mode === "login" ? "Sign in to LabFlow" : "Start building reproducible runs"}</h2>
          <p className="muted">{mode === "login" ? "Continue to your laboratory projects." : "Create an account to organize your first project."}</p>
          <form onSubmit={submit} className="stack-form">
            {mode === "register" && <label>Display name<input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required maxLength={100} autoComplete="name" /></label>}
            <label>Email address<input value={email} onChange={(e) => setEmail(e.target.value)} required type="email" maxLength={320} autoComplete="email" placeholder="you@laboratory.org" /></label>
            <label>Password<input value={password} onChange={(e) => setPassword(e.target.value)} required type="password" minLength={8} maxLength={128} autoComplete={mode === "login" ? "current-password" : "new-password"} /></label>
            {error && <div className="inline-error" role="alert">{error}</div>}
            <button className="primary-button wide" disabled={submitting}>{submitting ? "Please wait…" : mode === "login" ? "Sign in" : "Create account"}</button>
          </form>
          <p className="auth-switch">{mode === "login" ? "New to LabFlow?" : "Already have an account?"} <button className="text-button" onClick={() => { setMode(mode === "login" ? "register" : "login"); setError(""); }}>{mode === "login" ? "Create an account" : "Sign in"}</button></p>
        </div>
      </section>
    </main>
  );
}

interface DashboardProps {
  token: string;
  user: AuthUser;
  projects: Project[];
  selectedProject: Project | null;
  onSelectProject: (id: number) => void;
  onProjectsChanged: (token: string, preferredId?: number) => Promise<void>;
  onSignOut: () => void;
}

function Dashboard(props: DashboardProps) {
  const { token, user, projects, selectedProject, onSelectProject, onProjectsChanged, onSignOut } = props;
  const [showCreateProject, setShowCreateProject] = useState(false);
  const [projectName, setProjectName] = useState("");
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState("");

  async function createProject(event: FormEvent) {
    event.preventDefault();
    setCreating(true);
    setError("");
    try {
      const project = await apiRequest<Project>("/api/projects", { token, method: "POST", body: { name: projectName } });
      await onProjectsChanged(token, project.id);
      setProjectName("");
      setShowCreateProject(false);
    } catch (requestError) {
      setError(messageFrom(requestError));
    } finally {
      setCreating(false);
    }
  }

  return (
    <main className="app-shell">
      <aside className="sidebar">
        <Brand />
        <div className="sidebar-section-title"><span>Projects</span><button className="icon-button" aria-label="Create project" onClick={() => setShowCreateProject(true)}>+</button></div>
        <nav className="project-nav" aria-label="Projects">
          {projects.map((project) => (
            <button key={project.id} className={project.id === selectedProject?.id ? "project-link active" : "project-link"} onClick={() => onSelectProject(project.id)}>
              <span className="project-monogram">{project.name.slice(0, 2).toUpperCase()}</span>
              <span><strong>{project.name}</strong><small>{project.currentUserRole.toLowerCase()}</small></span>
            </button>
          ))}
          {projects.length === 0 && <p className="sidebar-empty">No projects yet. Create one to begin.</p>}
        </nav>
        <div className="sidebar-user">
          <span className="avatar">{user.displayName.slice(0, 1).toUpperCase()}</span>
          <span className="user-copy"><strong>{user.displayName}</strong><small>{user.email}</small></span>
          <button className="signout-button" onClick={onSignOut} title="Sign out" aria-label="Sign out">↗</button>
        </div>
      </aside>

      <section className="main-stage">
        {selectedProject ? <ProjectWorkspace token={token} project={selectedProject} /> : <WelcomeEmpty onCreate={() => setShowCreateProject(true)} />}
      </section>

      {showCreateProject && (
        <div className="modal-backdrop" role="presentation" onMouseDown={() => setShowCreateProject(false)}>
          <section className="modal-card" role="dialog" aria-modal="true" aria-labelledby="new-project-title" onMouseDown={(event) => event.stopPropagation()}>
            <button className="modal-close" onClick={() => setShowCreateProject(false)} aria-label="Close">×</button>
            <span className="eyebrow">New workspace</span>
            <h2 id="new-project-title">Create a project</h2>
            <p className="muted">Projects isolate members, inputs, configurations, and future jobs.</p>
            <form className="stack-form" onSubmit={createProject}>
              <label>Project name<input autoFocus value={projectName} onChange={(event) => setProjectName(event.target.value)} required maxLength={200} placeholder="Quantum Materials Study" /></label>
              {error && <div className="inline-error" role="alert">{error}</div>}
              <div className="button-row"><button type="button" className="secondary-button" onClick={() => setShowCreateProject(false)}>Cancel</button><button className="primary-button" disabled={creating}>{creating ? "Creating…" : "Create project"}</button></div>
            </form>
          </section>
        </div>
      )}
    </main>
  );
}

function WelcomeEmpty({ onCreate }: { onCreate: () => void }) {
  return (
    <div className="welcome-empty">
      <div className="empty-orbit"><span /><span /><span /></div>
      <span className="eyebrow">Your laboratory workspace</span>
      <h1>Start with a project.</h1>
      <p>Bring together collaborators, validated molecular inputs, and immutable experiment configurations.</p>
      <button className="primary-button" onClick={onCreate}>Create your first project</button>
    </div>
  );
}

function ProjectWorkspace({ token, project }: { token: string; project: Project }) {
  const [tab, setTab] = useState<WorkspaceTab>("jobs");
  const [members, setMembers] = useState<ProjectMember[]>([]);
  const [inputs, setInputs] = useState<MolecularInput[]>([]);
  const [configs, setConfigs] = useState<ExperimentConfig[]>([]);
  const [jobs, setJobs] = useState<JobSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [refreshKey, setRefreshKey] = useState(0);
  const refresh = useCallback(() => setRefreshKey((key) => key + 1), []);
  const updateJob = useCallback((updated: JobDetails) => {
    setJobs((current) => current.map((job) => job.id === updated.id
      ? { ...job, status: updated.status, updatedAt: updated.updatedAt }
      : job));
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError("");
    Promise.all([
      apiRequest<ProjectMember[]>(`/api/projects/${project.id}/members`, { token }),
      apiRequest<MolecularInput[]>(`/api/projects/${project.id}/inputs`, { token }),
      apiRequest<ExperimentConfig[]>(`/api/projects/${project.id}/configs`, { token }),
      apiRequest<JobSummary[]>(`/api/projects/${project.id}/jobs`, { token }),
    ])
      .then(([loadedMembers, loadedInputs, loadedConfigs, loadedJobs]) => {
        if (cancelled) return;
        setMembers(loadedMembers);
        setInputs(loadedInputs);
        setConfigs(loadedConfigs);
        setJobs(loadedJobs);
      })
      .catch((requestError) => { if (!cancelled) setError(messageFrom(requestError)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [project.id, refreshKey, token]);

  const canContribute = project.currentUserRole !== "VIEWER";
  const canManageMembers = project.currentUserRole === "OWNER";
  const namedConfigCount = useMemo(() => new Set(configs.map((config) => config.name)).size, [configs]);

  return (
    <div className="workspace">
      <header className="workspace-header">
        <div><span className="eyebrow">Project workspace</span><h1>{project.name}</h1><p>Owned by {project.owner.displayName} · Created {formatDate(project.createdAt)}</p></div>
        <span className={`role-pill role-${project.currentUserRole.toLowerCase()}`}>{project.currentUserRole}</span>
      </header>
      <div className="metrics-row">
        <Metric value={inputs.length} label="Molecular inputs" />
        <Metric value={namedConfigCount} label="Named configs" />
        <Metric value={jobs.length} label="Jobs" />
        <Metric value={members.length + 1} label="Team members" />
      </div>
      <nav className="tabs" aria-label="Project sections">
        <TabButton name="jobs" current={tab} onSelect={setTab}>Jobs <span>{jobs.length}</span></TabButton>
        <TabButton name="inputs" current={tab} onSelect={setTab}>Inputs <span>{inputs.length}</span></TabButton>
        <TabButton name="configs" current={tab} onSelect={setTab}>Configurations <span>{configs.length}</span></TabButton>
        <TabButton name="members" current={tab} onSelect={setTab}>Members <span>{members.length + 1}</span></TabButton>
      </nav>
      {error && <div className="page-error" role="alert"><strong>Workspace could not be loaded.</strong><span>{error}</span><button onClick={refresh}>Retry</button></div>}
      {loading ? <ContentSkeleton /> : (
        <>
          {tab === "jobs" && <JobsPanel token={token} jobs={jobs} onJobUpdated={updateJob} />}
          {tab === "inputs" && <InputsPanel token={token} projectId={project.id} inputs={inputs} canContribute={canContribute} onChanged={refresh} />}
          {tab === "configs" && <ConfigsPanel token={token} projectId={project.id} configs={configs} canContribute={canContribute} onChanged={refresh} />}
          {tab === "members" && <MembersPanel token={token} project={project} members={members} canManage={canManageMembers} onChanged={refresh} />}
        </>
      )}
    </div>
  );
}

function JobsPanel({ token, jobs, onJobUpdated }: { token: string; jobs: JobSummary[]; onJobUpdated: (job: JobDetails) => void }) {
  const [selectedId, setSelectedId] = useState<number | null>(jobs[0]?.id ?? null);
  const [details, setDetails] = useState<JobDetails | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (jobs.length === 0) { setSelectedId(null); setDetails(null); return; }
    if (!jobs.some((job) => job.id === selectedId)) setSelectedId(jobs[0]!.id);
  }, [jobs, selectedId]);

  useEffect(() => {
    if (selectedId === null) return;
    let cancelled = false;
    let timer: number | undefined;
    const load = async () => {
      try {
        const job = await apiRequest<JobDetails>(`/api/jobs/${selectedId}`, { token });
        if (cancelled) return;
        setDetails(job); setError(""); onJobUpdated(job);
        if (job.status === "QUEUED" || job.status === "RUNNING") timer = window.setTimeout(load, 2_000);
      } catch (requestError) { if (!cancelled) setError(messageFrom(requestError)); }
    };
    void load();
    return () => { cancelled = true; if (timer !== undefined) window.clearTimeout(timer); };
  }, [onJobUpdated, selectedId, token]);

  if (jobs.length === 0) return <section className="content-card main-card"><EmptyList title="No jobs yet" text="Submitted calculations will appear here with live status, logs, and results." /></section>;
  const summary = details?.result?.summary;
  const latestAttempt = details?.attempts.at(-1);
  return <section className="jobs-layout">
    <div className="content-card job-list-card">
      <div className="card-heading"><div><span className="eyebrow">Execution history</span><h2>Jobs</h2></div></div>
      <div className="job-list">{jobs.map((job) => <button key={job.id} className={job.id === selectedId ? "job-row active" : "job-row"} onClick={() => setSelectedId(job.id)}>
        <span><strong>Job #{job.id}</strong><small>{formatDate(job.createdAt)}</small></span><StatusBadge status={job.id === details?.id ? details.status : job.status} />
      </button>)}</div>
    </div>
    <div className="content-card job-detail-card">
      {error && <div className="inline-error">{error}</div>}
      {!details || details.id !== selectedId ? <ContentSkeleton /> : <>
        <div className="job-detail-heading"><div><span className="eyebrow">Job #{details.id}</span><h2>{details.specSnapshot.experimentConfig?.name ?? "Calculation"}</h2><p>{details.specSnapshot.molecularInput?.originalFilename ?? `Input #${details.molecularInputId}`}</p></div><StatusBadge status={details.status} /></div>
        <dl className="job-facts"><div><dt>Attempt</dt><dd>{latestAttempt?.attemptNo ?? "Waiting"}</dd></div><div><dt>Worker</dt><dd>{latestAttempt?.workerInstance ?? "Unassigned"}</dd></div><div><dt>Method</dt><dd>{details.specSnapshot.experimentConfig?.spec?.method ?? "—"}</dd></div><div><dt>Basis</dt><dd>{details.specSnapshot.experimentConfig?.spec?.basis ?? "—"}</dd></div></dl>
        {summary && <div className="result-panel"><span className="eyebrow">Result</span><div className="result-grid">
          {summary.energyHartree !== undefined && <div><small>Energy</small><strong>{Number(summary.energyHartree).toFixed(10)} Eh</strong></div>}
          {summary.converged !== undefined && <div><small>Converged</small><strong>{summary.converged ? "Yes" : "No"}</strong></div>}
          {summary.durationSeconds !== undefined && <div><small>Runtime</small><strong>{Number(summary.durationSeconds).toFixed(2)}s</strong></div>}
        </div></div>}
        {latestAttempt?.failure && <div className="inline-error"><strong>{String(latestAttempt.failure.code ?? "Task failed")}</strong><br />{String(latestAttempt.failure.message ?? "The worker reported a failure.")}</div>}
        <div className="log-heading"><span className="eyebrow">Worker log</span><small>{details.logs.length} chunks</small></div>
        <pre className="job-log">{details.logs.length ? details.logs.map((log) => `[${log.stream}] ${log.content}`).join("") : "Waiting for worker output…"}</pre>
        {details.result && <details className="manifest"><summary>Reproducibility manifest</summary><pre>{JSON.stringify(details.result.manifest, null, 2)}</pre></details>}
      </>}
    </div>
  </section>;
}

function StatusBadge({ status }: { status: JobSummary["status"] }) {
  return <span className={`status-badge status-${status.toLowerCase()}`}><i />{status}</span>;
}

function Metric({ value, label }: { value: number; label: string }) {
  return <div className="metric"><strong>{value.toString().padStart(2, "0")}</strong><span>{label}</span></div>;
}

function TabButton({ name, current, onSelect, children }: { name: WorkspaceTab; current: WorkspaceTab; onSelect: (tab: WorkspaceTab) => void; children: React.ReactNode }) {
  return <button className={name === current ? "active" : ""} onClick={() => onSelect(name)}>{children}</button>;
}

function ContentSkeleton() {
  return <div className="content-card skeleton-card"><i /><i /><i /></div>;
}

function InputsPanel({ token, projectId, inputs, canContribute, onChanged }: { token: string; projectId: number; inputs: MolecularInput[]; canContribute: boolean; onChanged: () => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState<{ kind: "error" | "success"; text: string } | null>(null);

  async function upload(event: FormEvent) {
    event.preventDefault();
    if (!file) return;
    setBusy(true);
    setFeedback(null);
    const form = new FormData();
    form.append("file", file);
    try {
      await apiRequest<MolecularInput>(`/api/projects/${projectId}/inputs`, { token, method: "POST", body: form });
      setFeedback({ kind: "success", text: "Input validated and stored." });
      setFile(null);
      onChanged();
    } catch (error) {
      setFeedback({ kind: "error", text: messageFrom(error) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="content-grid">
      <div className="content-card main-card">
        <div className="card-heading"><div><span className="eyebrow">Artifact registry</span><h2>Molecular inputs</h2></div><p>SHA-256 deduplicated within this project</p></div>
        {inputs.length === 0 ? <EmptyList title="No molecular inputs" text={canContribute ? "Upload an XYZ file to establish the first immutable input." : "A contributor has not uploaded an input yet."} /> : (
          <div className="data-list">{inputs.map((input) => (
            <article className="data-row" key={input.id}>
              <span className="file-icon">XYZ</span>
              <div className="row-main"><strong>{input.originalFilename}</strong><code title={input.sha256}>{input.sha256.slice(0, 14)}…{input.sha256.slice(-6)}</code></div>
              <div className="row-meta"><strong>{formatBytes(input.sizeBytes)}</strong><small>{formatDate(input.createdAt)}</small></div>
            </article>
          ))}</div>
        )}
      </div>
      <aside className="content-card side-card">
        <span className="eyebrow">Add input</span><h3>Upload molecule</h3>
        {canContribute ? (
          <form onSubmit={upload} className="stack-form compact-form">
            <label className="drop-zone"><input type="file" accept=".xyz" onChange={(event) => setFile(event.target.files?.[0] ?? null)} /><span className="upload-arrow">↑</span><strong>{file?.name ?? "Choose an XYZ file"}</strong><small>{file ? formatBytes(file.size) : "Maximum 1 MB · validated before storage"}</small></label>
            {feedback && <div className={`form-feedback ${feedback.kind}`}>{feedback.text}</div>}
            <button className="primary-button wide" disabled={!file || busy}>{busy ? "Validating…" : "Upload input"}</button>
          </form>
        ) : <ReadOnlyNote />}
      </aside>
    </section>
  );
}

function ConfigsPanel({ token, projectId, configs, canContribute, onChanged }: { token: string; projectId: number; configs: ExperimentConfig[]; canContribute: boolean; onChanged: () => void }) {
  const [name, setName] = useState("");
  const [method, setMethod] = useState("RHF");
  const [basis, setBasis] = useState("sto-3g");
  const [charge, setCharge] = useState(0);
  const [spin, setSpin] = useState(0);
  const [memory, setMemory] = useState(1024);
  const [timeout, setTimeoutValue] = useState(300);
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState("");

  async function create(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setFeedback("");
    const spec: ExperimentSpec = { schemaVersion: 1, taskType: "pyscf.single_point", method, basis, charge, spin, maxMemoryMb: memory, timeoutSeconds: timeout };
    try {
      await apiRequest<ExperimentConfig>(`/api/projects/${projectId}/configs`, { token, method: "POST", body: { name, spec } });
      setFeedback(`Created the next “${name}” version.`);
      onChanged();
    } catch (error) {
      setFeedback(messageFrom(error));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="content-grid config-layout">
      <div className="content-card main-card">
        <div className="card-heading"><div><span className="eyebrow">Immutable history</span><h2>Experiment configurations</h2></div><p>Newest version shown first</p></div>
        {configs.length === 0 ? <EmptyList title="No configurations" text="Create a validated PySCF single-point configuration." /> : (
          <div className="config-list">{configs.map((config) => (
            <article className="config-card" key={config.id}>
              <div className="config-title"><span><strong>{config.name}</strong><small>Created {formatDate(config.createdAt)}</small></span><b>v{config.version}</b></div>
              <dl><div><dt>Method</dt><dd>{config.spec.method}</dd></div><div><dt>Basis</dt><dd>{config.spec.basis}</dd></div><div><dt>Charge / spin</dt><dd>{config.spec.charge} / {config.spec.spin}</dd></div><div><dt>Memory</dt><dd>{config.spec.maxMemoryMb} MB</dd></div><div><dt>Timeout</dt><dd>{config.spec.timeoutSeconds}s</dd></div></dl>
            </article>
          ))}</div>
        )}
      </div>
      <aside className="content-card side-card config-form-card">
        <span className="eyebrow">Schema v1</span><h3>Create a version</h3>
        {canContribute ? (
          <form onSubmit={create} className="stack-form compact-form">
            <label>Configuration name<input value={name} onChange={(e) => setName(e.target.value)} required maxLength={200} placeholder="Baseline" /></label>
            <div className="field-pair"><label>Method<input value={method} onChange={(e) => setMethod(e.target.value)} required maxLength={50} /></label><label>Basis<input value={basis} onChange={(e) => setBasis(e.target.value)} required maxLength={100} /></label></div>
            <div className="field-pair"><label>Charge<input type="number" min={-20} max={20} value={charge} onChange={(e) => setCharge(e.target.valueAsNumber)} required /></label><label>Spin<input type="number" min={0} max={20} value={spin} onChange={(e) => setSpin(e.target.valueAsNumber)} required /></label></div>
            <div className="field-pair"><label>Memory (MB)<input type="number" min={128} max={65536} value={memory} onChange={(e) => setMemory(e.target.valueAsNumber)} required /></label><label>Timeout (sec)<input type="number" min={1} max={86400} value={timeout} onChange={(e) => setTimeoutValue(e.target.valueAsNumber)} required /></label></div>
            {feedback && <div className="form-feedback">{feedback}</div>}
            <button className="primary-button wide" disabled={busy}>{busy ? "Creating…" : "Create immutable version"}</button>
            <small className="form-hint">Using an existing name creates version +1.</small>
          </form>
        ) : <ReadOnlyNote />}
      </aside>
    </section>
  );
}

function MembersPanel({ token, project, members, canManage, onChanged }: { token: string; project: Project; members: ProjectMember[]; canManage: boolean; onChanged: () => void }) {
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<MembershipRole>("MEMBER");
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState("");

  async function addMember(event: FormEvent) {
    event.preventDefault(); setBusy(true); setFeedback("");
    try { await apiRequest<ProjectMember>(`/api/projects/${project.id}/members`, { token, method: "POST", body: { email, role } }); setEmail(""); onChanged(); }
    catch (error) { setFeedback(messageFrom(error)); } finally { setBusy(false); }
  }
  async function updateRole(userId: number, nextRole: MembershipRole) {
    setFeedback("");
    try { await apiRequest<ProjectMember>(`/api/projects/${project.id}/members/${userId}`, { token, method: "PATCH", body: { role: nextRole } }); onChanged(); }
    catch (error) { setFeedback(messageFrom(error)); }
  }
  async function removeMember(member: ProjectMember) {
    if (!window.confirm(`Remove ${member.user.displayName} from this project?`)) return;
    setFeedback("");
    try { await apiRequest<void>(`/api/projects/${project.id}/members/${member.user.id}`, { token, method: "DELETE" }); onChanged(); }
    catch (error) { setFeedback(messageFrom(error)); }
  }

  return (
    <section className="content-grid">
      <div className="content-card main-card">
        <div className="card-heading"><div><span className="eyebrow">Access control</span><h2>Project members</h2></div><p>{members.length + 1} people with access</p></div>
        <div className="member-list">
          <article className="member-row"><span className="avatar warm">{project.owner.displayName[0]?.toUpperCase()}</span><div><strong>{project.owner.displayName}</strong><small>{project.owner.email}</small></div><span className="member-role owner">OWNER</span></article>
          {members.map((member) => (
            <article className="member-row" key={member.user.id}><span className="avatar">{member.user.displayName[0]?.toUpperCase()}</span><div><strong>{member.user.displayName}</strong><small>{member.user.email}</small></div>{canManage ? <select aria-label={`Role for ${member.user.displayName}`} value={member.role} onChange={(e) => void updateRole(member.user.id, e.target.value as MembershipRole)}>{membershipRoles.map((value) => <option key={value}>{value}</option>)}</select> : <span className="member-role">{member.role}</span>}{canManage && <button className="remove-button" onClick={() => void removeMember(member)} aria-label={`Remove ${member.user.displayName}`}>×</button>}</article>
          ))}
        </div>
      </div>
      <aside className="content-card side-card">
        <span className="eyebrow">Invite collaborator</span><h3>Add an existing user</h3>
        {canManage ? <form onSubmit={addMember} className="stack-form compact-form"><label>Email address<input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required placeholder="colleague@laboratory.org" /></label><label>Project role<select value={role} onChange={(e) => setRole(e.target.value as MembershipRole)}>{membershipRoles.map((value) => <option key={value}>{value}</option>)}</select></label>{feedback && <div className="form-feedback error">{feedback}</div>}<button className="primary-button wide" disabled={busy}>{busy ? "Adding…" : "Add member"}</button></form> : <ReadOnlyNote text="Only the project owner can add or change members." />}
      </aside>
    </section>
  );
}

function EmptyList({ title, text }: { title: string; text: string }) {
  return <div className="empty-list"><span>◇</span><strong>{title}</strong><p>{text}</p></div>;
}

function ReadOnlyNote({ text = "Your viewer role provides read-only access to this project." }: { text?: string }) {
  return <div className="read-only-note"><strong>Read-only access</strong><p>{text}</p></div>;
}
