# 0010 — Structured download event stream for progress reporting

- **Status:** Accepted
- **Date:** 2026-07-08
- **Revises:** [ADR-0003](0003-streaming-download-contract.md)

## Context

The CLI's `download` runs silently between a header line and a final summary. For a
book of hundreds of chapters that is a long, opaque wait, so we want a **live,
determinate progress display**: an `X/Y` bar for total chapters plus an `X/Y` page
bar per chapter currently being fetched by a worker.

[ADR-0003](0003-streaming-download-contract.md) defined `download` as a cold
`Flow<Page>` and anticipated progress as a *future* concern it could host — by
"observing the flow (count emitted/fetched pages)". But observing a flat page
stream only yields **counts, not fractions**: nothing in a bare `Flow<Page>` tells a
consumer how many chapters exist or how many pages a chapter holds, so no
denominator — and therefore no determinate bar — is possible.

The denominators, crucially, **already exist at the source**. `MangaDexCatalog`
fetches the full chapter feed (so it knows the chapter count) and each chapter's
at-home server (so it knows that chapter's page count) *before* it streams any page
— then flattens all of it away into loose `Page`s. The structure a determinate
display needs is discarded at the one place it is known.

## Decision

Promote `Catalog.download` from `Flow<Page>` to a **structured event stream**:

```kotlin
fun download(ref: BookRef): Flow<DownloadEvent>

sealed interface DownloadEvent {
    data class Manifest(val chapters: Int) : DownloadEvent
    data class ChapterStarted(val chapterId: String, val label: String, val pages: Int) : DownloadEvent
    data class PageReady(val chapterId: String, val page: Page) : DownloadEvent
}
```

Ordering: a single `Manifest` first, then per chapter a `ChapterStarted` before its
`PageReady`s. Chapters may resolve concurrently, so events interleave across
chapters — consumers key per-chapter state by `chapterId` (a stable source id, not
the display `label`, which can collide). The full producer/consumer coroutine
mechanics are written up in the design note
[docs/design/download-pipeline.md](../design/download-pipeline.md).

`Downloader.download` gains an optional `progress: DownloadProgress = NONE`
parameter — a listener (`onStart` / `onChapterStart` / `onPageWritten` /
`onChapterComplete`) it drives as it consumes the stream. This mirrors the existing
`Bundler.onArchive` callback: reusable capability in `core`, rendering in the
delivery module. Because pages are written on concurrent coroutines, `Downloader`
**serializes** the listener callbacks so a sink needs no locking.

**What carries over from ADR-0003 unchanged:** the flow is still *cold*; `Page`
stays a pure domain interface with `suspend fun bytes()` fetched **lazily** (the
chapter identity rides on `PageReady`, not on `Page`); "absent vs. unreachable"
still holds (an unresolvable ref yields an *empty* flow with no `Manifest`; source
failures surface as `CatalogException` during collection); `kotlinx-coroutines`
remains public API of `core`.

## Consequences

- **Easier:** determinate progress — the CLI renders a total-chapters bar and
  per-chapter page bars (hand-rolled ANSI, no new dependency), and any future
  consumer gets the same structure for free.
- **Easier:** chapter accounting is now keyed by a stable `chapterId` rather than by
  distinct written labels, so duplicate chapter labels no longer merge in the
  `Summary`.
- **Contained blast radius:** `Downloader` is the *only* direct consumer of the
  flow. The interop facade (`BlockingCatalog`) and the server go through
  `Downloader.Summary` and are untouched (see
  [ADR-0007](0007-java-web-front-end-and-interop-facade.md)).
- **Cost:** the contract is richer — a new adapter must emit `Manifest` /
  `ChapterStarted` / `PageReady` (with page counts known before streaming) rather
  than just `send` pages. For a source that cannot cheaply pre-count a chapter's
  pages this is more work, though it can emit a best-effort count.

## Alternatives considered

- **Keep `Flow<Page>`, observe it for progress (ADR-0003's plan).** Gives counters,
  not determinate bars — no totals. Rejected: it doesn't meet the requirement.
- **Keep `Flow<Page>`, add `chapterPages`/`totalChapters` fields to `Page`.**
  Achieves determinate bars with a smaller signature change, but bolts
  progress-reporting metadata onto the pure content type and repeats `totalChapters`
  on every page; `Manifest`-first also can't make the chapters bar determinate
  before the first page arrives. Rejected in favour of keeping `Page` pure.
- **A separate `Catalog.manifest(ref)` call for totals, `Flow<Page>` unchanged.**
  Adds a second network round-trip and a second port method covering the same walk
  the download already does. Rejected as redundant.
