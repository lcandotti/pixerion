# 0003 — Streaming download contract on Catalog

- **Status:** Superseded by [ADR-0010](0010-structured-download-event-stream.md)
- **Date:** 2026-06-24

## Context

Beyond search and lookup, Pixerion must **download** a book's content (for manga,
the page images). Two forces shape the contract:

- **Mechanism independence.** Content may be obtained very differently per source
  — image URLs from a JSON API (MangaDex), or HTML scraping of a website. The
  existing [Catalog](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/domain/Catalog.kt) contract
  forbids leaking transport/vendor details, so callers must not see *how* bytes
  are fetched (see [ADR-0001](0001-http-and-json-stack-for-source-adapters.md)).
- **Scale.** A book can be hundreds of chapters / thousands of images. Pulling it
  all into memory before returning is not acceptable.

A future requirement — **tracking** a download's progress — is anticipated but
explicitly deferred until a good pattern emerges; the contract should not block it.

## Decision

Add to `Catalog`:

```kotlin
fun download(ref: BookRef): Flow<Page>
```

- The result is a **cold `Flow<Page>`**: collecting it drives the download,
  emitting one [`Page`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/domain/Page.kt) per image in
  reading order, so a large book streams rather than materializing at once.
- `Page` is a **domain interface** carrying lightweight metadata (`chapter`,
  `number`, `filename`) plus `suspend fun bytes(): ByteArray`. Image bytes are
  fetched **lazily**, on demand, keeping them out of value types and letting the
  caller control fetch timing/concurrency. The fetch mechanism lives entirely in
  the adapter.
- An unresolvable `ref` yields an **empty flow** (nothing to download), mirroring
  the "absent vs. unreachable" rule: source failures surface as
  `CatalogException` *during collection*.

Because `Flow` now appears in the contract, `kotlinx-coroutines` becomes part of
the `shared` module's public API (`api`, not `implementation`).

## Consequences

- **Easier:** large downloads stream with bounded memory; the caller (e.g. the
  CLI) decides how to persist pages and at what concurrency.
- **Easier:** the mechanism stays hidden — a future HTML-scraping adapter
  produces the same `Flow<Page>`, and callers are none the wiser.
- **Future-friendly:** progress tracking can later observe the flow (count
  emitted/fetched pages) or wrap `Page.bytes()` without reshaping the contract —
  satisfying the deferred requirement without committing to a pattern now.
- **Cost:** coroutines is now a public, transitive dependency of `shared`.
- **Cost / current limitation:** the MangaDex implementation downloads all
  English chapters of a manga; language selection and chapter-range filtering are
  not yet exposed and would extend the signature later.

## Alternatives considered

- **`suspend fun download(ref): List<Page>`** — simpler, but forces enumerating
  every chapter up front (many API calls before the first byte) and reads less
  naturally as a stream. Rejected for scale.
- **Eager `Page` with a `ByteArray` field** — would hold whole images in the
  emitted value (memory pressure) and puts a mutable array in a value type
  (`equals` footgun). Rejected in favour of lazy `bytes()`.
- **Returning page URLs (`List<PageRef>`)** — leaks transport (URLs, and the auth
  / referer semantics needed to fetch them, especially for scraped sources) to
  the caller, violating the Catalog contract. Rejected.
- **Sink/callback API (`download(ref, sink)`)** — natural for progress, but adds
  ceremony now for a feature that's deferred; the cold-flow shape can host
  tracking later without it. Rejected for now.
