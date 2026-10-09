import { useEffect, useState } from "react";
import { apiRequest, ApiError, formatDate, type JobDetails } from "./api";
import { comparisonRows, display, record } from "./job-comparison";

export function JobHistory({ job }: { job: JobDetails }) {
  const environment = record(job.result?.manifest.environment);
  return <>
    <section className="job-timeline" aria-labelledby="timeline-heading">
      <h3 id="timeline-heading">Status timeline</h3>
      {job.events?.length ? <ol>{job.events.map((event) => <li key={event.id}>
        <strong>{event.fromStatus ? `${event.fromStatus} → ` : ""}{event.toStatus}</strong>
        <time dateTime={event.createdAt}>{formatDate(event.createdAt)}</time>
        <span>{event.eventType.replaceAll("_", " ").toLowerCase()}</span>
        {Object.keys(event.details).length > 0 && <details><summary>Transition details</summary><pre>{JSON.stringify(event.details, null, 2)}</pre></details>}
      </li>)}</ol> : <p className="muted">No recorded transitions.</p>}
      {job.cancelRequestedAt && <p className="cancellation-note">Cancellation requested {formatDate(job.cancelRequestedAt)}{job.status === "RUNNING" ? " · Waiting for the worker to stop." : ""}</p>}
    </section>
    {job.attempts.length > 0 && <details className="manifest attempt-history" open><summary>Attempt history and failures</summary>
      <div className="table-scroll"><table><caption className="sr-only">Execution attempts</caption><thead><tr><th>Attempt</th><th>Status</th><th>Worker</th><th>Started</th><th>Finished</th><th>Failure</th></tr></thead>
        <tbody>{job.attempts.map((attempt) => <tr key={attempt.id}><th scope="row">{attempt.attemptNo}</th><td>{attempt.status}</td><td>{attempt.workerInstance} · #{attempt.workerId}</td><td>{formatDate(attempt.startedAt)}</td><td>{attempt.finishedAt ? formatDate(attempt.finishedAt) : "—"}</td><td>{attempt.failure ? `${display(attempt.failure.code)}: ${display(attempt.failure.message)}` : "—"}</td></tr>)}</tbody>
      </table></div>
    </details>}
    {job.result && <section className="environment-summary" aria-labelledby="environment-heading"><h3 id="environment-heading">Execution environment</h3>
      <dl><div><dt>Worker image digest</dt><dd>{display(environment.workerImageDigest)}</dd></div><div><dt>Python</dt><dd>{display(environment.python)}</dd></div><div><dt>Platform</dt><dd>{display(environment.platform)}</dd></div><div><dt>Packages</dt><dd>{display(environment.packages)}</dd></div><div><dt>Spec SHA-256</dt><dd>{display(environment.specSha256)}</dd></div></dl>
    </section>}
  </>;
}

export function JobComparison({ token, ids, onClose }: { token: string; ids: number[]; onClose: () => void }) {
  const [jobs, setJobs] = useState<JobDetails[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [retry, setRetry] = useState(0);
  const identity = ids.join(",");
  useEffect(() => {
    let cancelled = false;
    setLoading(true); setError(""); setJobs([]);
    Promise.all(identity.split(",").map((id) => apiRequest<JobDetails>(`/api/jobs/${id}`, { token })))
      .then((results) => {
        if (cancelled) return;
        if (results.some((job) => job.status !== "SUCCEEDED" || !job.result)) throw new Error("Every selected job must have a successful saved result.");
        setJobs(results);
      })
      .catch((err: unknown) => { if (!cancelled) setError(err instanceof ApiError || err instanceof Error ? err.message : "Comparison could not be loaded."); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [identity, token, retry]);
  const rows = comparisonRows(jobs);
  const incompatible = rows.slice(0, 5).some((row) => row.different || row.values.some((value) => value === undefined || value === null));
  return <section className="content-card comparison-card" aria-labelledby="comparison-heading">
    <div className="card-heading"><div><span className="eyebrow">Saved results</span><h2 id="comparison-heading">Compare experiments</h2></div><button className="secondary-button" onClick={onClose}>Close comparison</button></div>
    {loading && <p role="status">Loading selected results…</p>}
    {error && <div className="inline-error" role="alert">{error} <button className="text-button" onClick={() => setRetry((value) => value + 1)}>Retry comparison</button></div>}
    {!loading && !error && <>
      <p className="muted">Highlighted rows contain different saved values. Missing values appear as —.</p>
      {incompatible && <p className="comparison-warning">Inputs or calculation settings differ or are missing. Check these differences before interpreting energies.</p>}
      <div className="table-scroll" tabIndex={0} aria-label="Experiment comparison table"><table><caption className="sr-only">Comparison of {jobs.length} successful jobs</caption><thead><tr><th scope="col">Field</th>{jobs.map((job) => <th scope="col" key={job.id}>Job #{job.id}<small>{job.specSnapshot.experimentConfig?.name} · v{job.specSnapshot.experimentConfig?.version}</small></th>)}</tr></thead>
        <tbody>{rows.map((row) => <tr key={row.label} className={row.different ? "comparison-difference" : ""}><th scope="row">{row.label}{row.different && <small>Different</small>}</th>{row.values.map((value, index) => <td key={jobs[index]!.id}>{display(value)}{value !== undefined && value !== null && row.unit ? ` ${row.unit}` : ""}</td>)}</tr>)}</tbody>
      </table></div>
    </>}
  </section>;
}
