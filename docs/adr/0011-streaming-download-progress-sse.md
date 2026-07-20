# 0011 — Streaming download progress over SSE (job model)

- **Status:** Accepted
- **Date:** 2026-07-08
- **Builds on:** [ADR-0007](0007-java-web-front-end-and-interop-facade.md),
  [ADR-0010](0010-structured-download-event-stream.md)

## Context

The CLI renders live download progress ([ADR-0010]: `Downloader` drives a
`DownloadProgress` listener). The server couldn't: `POST /downloads` ran the whole
download **synchronously** inside one request and returned only the final
`DownloadResponse` ([ADR-0007] §4, "downloads to disk and returns a `Summary` …
HTTP byte-streaming can come later"). A frontend saw nothing until it finished.

We want the same live tracking over HTTP. Forces:

- The progress source already exists — the `DownloadProgress` callback seam
  ([ADR-0010]) — and it is a plain, Java-implementable interface with **serialized,
  must-not-block** callbacks (they run on `core`'s download coroutines).
- The server is Spring **MVC/servlet**, not WebFlux ([ADR-0007]) — so no reactive
  streaming primitives; `SseEmitter` is the servlet-native option.
- Auth is stateless **JWT Bearer-in-header**, `/downloads` is admin-only. Browser
  `EventSource` cannot set an `Authorization` header.
- Don't reshape `core` beyond the one seam already added for the CLI.

## Decision

**Stream progress as Server-Sent Events, behind a job resource.**

1. **Job model.** `POST /downloads` starts a background download on a bounded
   `downloadExecutor` and returns **`202 Accepted` + `{ id }`**. `GET
   /downloads/{id}/events` is the **SSE** stream; `GET /downloads/{id}` reports
   status and, once done, the summary. The job (an in-memory registry entry) outlives
   any single connection, so the download survives client reconnects and multiple
   viewers, and `GET` (events) stays a safe read while `POST` remains the mutation.

2. **Snapshot + periodic push** (the server analog of the CLI's repaint loop). The
   job **is** the `DownloadProgress` sink; each callback only mutates an in-memory
   snapshot (honouring must-not-block), and a `@Scheduled` flusher pushes each
   running job's snapshot to its subscribers as a `state` event on a fixed cadence —
   coalescing per-page churn for free. Terminal `completed`/`error` events close the
   stream. Snapshot mutation and emitter sends use **separate locks** so a slow SSE
   client can never stall the download threads.

3. **One `core` change, via the existing seam.** `BlockingCatalog.download` gains an
   optional `DownloadProgress` parameter (forwarded to `Downloader.download`). The
   coroutine-first contract and the facade's role ([ADR-0007]) are unchanged.

4. **Auth unchanged: fetch-based SSE + Bearer.** The events endpoint stays
   `@PreAuthorize("hasRole('ADMIN')")` and authenticates via the normal
   `Authorization: Bearer` header; the frontend uses a fetch-streaming SSE client
   (e.g. `@microsoft/fetch-event-source`). **`SecurityConfig` is untouched.**

5. **Absence becomes an async outcome.** With no synchronous download, a missing book
   is no longer an immediate `404`; the job reaches terminal status `NOT_FOUND`
   (reported by `GET /downloads/{id}` and an `error` event). An **unknown source** is
   still rejected synchronously with `400`.

## Consequences

- **Easier:** a frontend renders live bars from `state` snapshots and reacts to the
  terminal event; progress reuses the exact `DownloadProgress` seam the CLI uses.
- **Contained blast radius:** one additive `core` change; `SecurityConfig` untouched;
  everything else is server-only (job service, DTOs, executor, three endpoints).
- **Behaviour change:** `POST /downloads` now returns `202` + a job handle instead of
  the final summary, and "book absent" is a terminal `NOT_FOUND` status rather than a
  synchronous `404`. Existing server tests updated accordingly.
- **Cost / limitation:** the flusher runs on Spring's single-threaded scheduler and
  sends synchronously, so a very slow SSE client can delay other jobs' ticks —
  acceptable for an admin tool on a fast network; hardening (a per-job bounded send
  queue, or a pooled scheduler) is a noted follow-up. Jobs live in memory (swept after
  a retention window), so they don't survive a restart and don't span instances.

## Alternatives considered

- **WebSocket.** Bidirectional and heavier; progress is strictly server→client, so
  SSE is the simpler fit and needs no new protocol handling. Rejected.
- **Client polling `GET /downloads/{id}`.** Trivial, no streaming infra, but laggy and
  chatty for smooth bars. Kept as the *status* endpoint; SSE is the live channel.
- **Single-request stream** (one endpoint streams the whole download, then closes).
  Less infra, but couples the download's lifetime to one connection, is single-viewer,
  and a `POST`+SSE stream needs a fetch-based client anyway (native `EventSource` is
  GET-only). Rejected in favour of the job model's decoupling.
- **Send on every `DownloadProgress` callback** instead of a periodic snapshot. Simpler
  to write, but risks blocking a `core` download thread on a slow client (violating the
  must-not-block contract) and floods the wire with per-page events. Rejected for the
  snapshot-push model.
- **`EventSource` + token query param.** Simplest browser API, but leaks the JWT into
  logs/history and needs a `SecurityConfig` change to read it. Rejected for
  fetch-based SSE keeping the Bearer-header model intact.
- **Reactive (WebFlux) `Flow<DownloadEvent>` → `Publisher` streamed end-to-end.** The
  "purest" streaming, but pulls a coroutine→Publisher bridge into `core` and a reactive
  rewrite of the server — the same trade-off [ADR-0007] already deferred. Rejected.
