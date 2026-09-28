import assert from "node:assert/strict";
import { test, mock } from "node:test";
import { readLogEvents, streamJobLogs } from "../src/job-stream.ts";

const log = (id, content = "SCF output\n") => ({ id, attemptId: 1, seqNo: id - 1, stream: "STDOUT", emittedAt: "2026-09-28T12:00:00Z", content });
const frame = (value) => `id:${value.id}\r\nevent:log\r\ndata:${JSON.stringify(value)}\r\n\r\n`;

test("SSE handles split UTF-8 characters, CRLF boundaries, comments, and multiple frames", async () => {
  const bytes = new TextEncoder().encode(`:keepalive\r\n\r\n${frame(log(1, "H₂ calculation\n"))}${frame(log(2))}`);
  const response = new Response(new ReadableStream({ start(controller) {
    // Every byte is a separate transport chunk, including inside the H₂ character.
    for (const byte of bytes) controller.enqueue(new Uint8Array([byte]));
    controller.close();
  } }));
  const received = [];
  await readLogEvents(response, (value) => received.push(value));
  assert.deepEqual(received, [log(1, "H₂ calculation\n"), log(2)]);
});

test("SSE reconnect authenticates, resumes after the last event, and suppresses replay duplicates", async () => {
  const controller = new AbortController();
  const headers = [];
  const received = [];
  const fetchMock = mock.method(globalThis, "fetch", async (_url, options) => {
    headers.push(options.headers);
    return new Response(headers.length === 1 ? frame(log(2)) : frame(log(2)) + frame(log(3)));
  });
  try {
    await streamJobLogs(42, "test-token", 1, controller.signal, (value) => {
      received.push(value.id);
      if (value.id === 3) controller.abort();
    }, () => {});
    assert.deepEqual(received, [2, 3]);
    assert.deepEqual(headers.map((value) => value["Last-Event-ID"]), ["1", "2"]);
    assert.equal(headers[0].Authorization, "Bearer test-token");
  } finally { fetchMock.mock.restore(); }
});

test("SSE stops reconnecting when access is denied", async () => {
  const fetchMock = mock.method(globalThis, "fetch", async () => new Response("", { status: 403 }));
  const states = [];
  try {
    await streamJobLogs(42, "test-token", 0, new AbortController().signal, () => assert.fail("unexpected log"), (state) => states.push(state));
    assert.equal(fetchMock.mock.callCount(), 1);
    assert.match(states[0], /unavailable/);
  } finally { fetchMock.mock.restore(); }
});
