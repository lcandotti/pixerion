# 0004 — Parallel download and on-disk layout

- **Status:** Accepted
- **Date:** 2026-06-24

## Context

[ADR-0003](0003-streaming-download-contract.md) established `Catalog.download`
as a cold `Flow<Page>` with lazily-fetched bytes, but left two things open that
turning it into a usable feature forces us to decide:

1. **Concurrency.** A manga is many chapters of many images; downloading them one
   at a time is unacceptably slow. The work is IO-bound, so it wants the
   goroutine-style "fan out, bound, join" pattern.
2. **Where files go.** Something must decide the destination directory and the
   on-disk structure, and it must be swappable.

## Decision

**Concurrency — two cooperating layers:**

- *Producer (adapter).* `MangaDexCatalog.download` is a **`channelFlow`** that,
  after the (serially paginated) chapter feed, `launch`es one coroutine per
  chapter — bounded by a `Semaphore` — to resolve each chapter's image server in
  parallel and `send` its pages. `channelFlow` joins all children (structured
  concurrency = a built-in wait-group).
- *Engine (`shared`).* A `Downloader` resolves the book, collects the page
  flow, and fans the writes out to a bounded pool of coroutines (`Semaphore(workers)`
  + `launch(Dispatchers.IO)` inside a `coroutineScope`), each calling `Page.bytes()`
  and writing the file. It lives in `shared` alongside `Catalog` and `Layout` so
  **every** delivery module reuses the whole capability by passing parameters.
- *Transport.* OkHttp's `Dispatcher.maxRequestsPerHost` is raised from its
  default of **5** to 8, so per-host concurrency doesn't silently throttle the
  pool (all images come from one at-home host).

**Layout (`shared`) + rooting (a downloader parameter):**

- A **`Layout`** interface in `shared` carries the naming/organisation convention
  as *relative* path segments (`StandardLayout` →
  `<source>/<book>/ch-<chapter>/<NNN>.<ext>`, filesystem-safe).
- `Downloader` writes those segments to disk under a `root` directory it takes
  as a parameter (default **`~/.pixerion`**). Concretely filesystem-based — no
  speculative storage abstraction.

**The CLI stays dumb.** It parses arguments and passes parameters into the shared
downloader (`--output` → `root`, `--workers`), then renders the returned
`Summary`. It contains no download or layout logic of its own.

## Consequences

- **Easier:** downloads run concurrently end-to-end with bounded resource use;
  `--workers` tunes it, and `Layout` is a single seam for changing storage.
- **Easier:** the `Flow` contract stays as ADR-0003 defined it — concurrency is an
  implementation detail on both sides, not part of the interface.
- **Future-friendly:** progress tracking can wrap `Downloader`'s per-page
  completion without touching the catalog contract.
- **Reusable:** `Catalog`, `Downloader`, and `Layout` all live in `shared`, so
  a future module gets the whole download capability by calling it with parameters
  — the CLI dictates nothing to `shared` beyond the arguments it passes.
- **Cost / limitation:** the downloader is concretely filesystem-based; a non-disk
  target (object storage, HTTP stream) would mean introducing a storage seam
  *then* — deliberately deferred rather than abstracted speculatively now.
- **Cost / limitation:** `~/.pixerion` is a flat home-dir convention rather than
  XDG (`$XDG_DATA_HOME`); revisit if cross-platform conventions matter.
- **Cost:** `maxRequestsPerHost` is a fixed tuning constant, not yet configurable.

## Alternatives considered

- **Single flat worker pool only (no per-chapter fan-out in the adapter).** Pages
  would only start flowing after each chapter resolved serially. Resolving
  chapters in parallel (`channelFlow`) gets bytes moving sooner. Kept both layers.
- **Writing files inside the `Catalog` adapter** (the `MangaDexCatalog` itself
  persisting to disk). Rejected: the catalog port stays a pure data source; the
  reusable *orchestration* of fetch-and-write is `Downloader`'s job.
- **A `PageSink` storage abstraction** (pluggable filesystem / object-store / stream
  targets). Rejected as speculative — it pushed concrete logic into the CLI for a
  use case that doesn't exist yet. Keep the downloader concretely disk-based;
  introduce a seam when a second target actually appears.
- **Putting `Layout`/`Downloader` in the CLI.** Rejected — they're reusable
  capability, so they belong in `shared`; the CLI only passes parameters.
- **XDG base directories / current working directory as the default root.**
  `~/.pixerion` is simpler and predictable for a single-user CLI; `--output`
  covers the rest. XDG can supersede this later via a new ADR.
