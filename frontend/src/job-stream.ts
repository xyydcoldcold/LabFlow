import type { JobLogChunk } from "./api";

// Fetch supports the Bearer header that native EventSource cannot send.
export async function readLogEvents(response: Response, onLog: (log: JobLogChunk) => void): Promise<void> {
  if (!response.body) throw new Error("Log stream has no response body");
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      buffer = buffer.replace(/\r\n/g, "\n");
      let end: number;
      while ((end = buffer.indexOf("\n\n")) !== -1) {
        const frame = buffer.slice(0, end);
        buffer = buffer.slice(end + 2);
        let event = "message";
        const data: string[] = [];
        for (const line of frame.split("\n")) {
          if (line.startsWith("event:")) event = line.slice(6).trim();
          if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
        }
        if (event === "log" && data.length) onLog(JSON.parse(data.join("\n")) as JobLogChunk);
      }
      if (done) return;
    }
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}

function reconnectDelay(signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const finish = () => { clearTimeout(timer); signal.removeEventListener("abort", finish); resolve(); };
    const timer = setTimeout(finish, 1_000);
    signal.addEventListener("abort", finish, { once: true });
    if (signal.aborted) finish();
  });
}

export async function streamJobLogs(
  jobId: number, token: string, lastEventId: number, signal: AbortSignal,
  onLog: (log: JobLogChunk) => void, onState: (state: string) => void,
): Promise<void> {
  while (!signal.aborted) {
    try {
      const response = await fetch(`/api/jobs/${jobId}/events`, {
        headers: { Authorization: `Bearer ${token}`, "Last-Event-ID": String(lastEventId), Accept: "text/event-stream" },
        signal,
      });
      if (!response.ok) {
        if ([401, 403, 404].includes(response.status)) {
          onState("Live logs unavailable. Refresh your session.");
          return;
        }
        throw new Error("Log stream unavailable");
      }
      onState("Live logs connected");
      await readLogEvents(response, (log) => {
        if (log.id <= lastEventId) return;
        onLog(log);
        lastEventId = log.id;
      });
    } catch {
      if (signal.aborted) return;
    }
    if (!signal.aborted) {
      onState("Reconnecting live logs…");
      await reconnectDelay(signal);
    }
  }
}
