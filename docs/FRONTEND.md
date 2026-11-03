# Web workspace and demo

The React workspace provides registration and login, visible-project selection, owner-only membership management, validated XYZ upload, immutable configuration creation, and job submission using a selected input and configuration version. The job view shows status, attempt, live SSE logs, result energy, failures, and a reproducibility manifest. Log connections authenticate with the Bearer token and reconnect using `Last-Event-ID`; status and result metadata refresh every two seconds. Submission retries retain the same idempotency key after an uncertain network response. Controls reflect the current project role, while the backend remains the authorization boundary.

For frontend development, start the backend on port `8080`, then run:

```bash
cd frontend
npm run dev
```

Vite proxies `/api` to the backend. The production Nginx image uses the same paths inside Docker Compose.

## Workflow and demo

Use **Explore demo** on the sign-in page, or open `http://localhost:3000/?demo=1`. The demo runs in the browser with clearly labeled fictional, read-only data. It needs no API, does not submit calculations, and leaves any existing login token unchanged. Inspect Job #1 to see a lost attempt followed by a takeover; select Jobs #1 and #2 to compare their configurations and sample results. Exit demo to return to the real workspace.

In a real workspace, the two-step wizard displays the project, input, immutable configuration version, and idempotency key before submission. Retries after a lost response reuse that key. The job list preserves status, job-ID search, and page in the URL; details show recorded transitions, attempts, Workers, logs, execution environment, and the accepted result. Authorized contributors can request cancellation; running jobs remain running until the worker acknowledges the request. Select 2–5 successful jobs to compare saved method, basis, input hash, energy, wall time, and image metadata.

Run the browser smoke tests without Docker or a backend:

```bash
npm --prefix frontend ci
cd frontend
npx playwright install chromium
npm run test:ui
```

The smoke suite covers the demo, failed-load recovery, keyboard dialog navigation, SSE cursor resume and duplicate suppression, submission retries, filtering/pagination, cancellation UI, and result comparison. CI runs this suite separately from `npm run test:e2e`, which needs the real Compose stack. Frontend builds and unit tests run independently.

Filtering and pagination currently operate on all downloaded project job summaries. The recorded image field can contain a local image tag; an immutable digest is shown only when supplied by the deployment. Demo data is illustrative and is not test or benchmark evidence.
