# Core library (`pixerion-core`)

The library where all of Pixerion's behaviour lives: the `Catalog` contract, the
MangaDex adapter, the downloader, the `.cbz` bundler, and the Java-interop facade.
Read this before changing anything under `pixerion-core/`. The traps are collected in
[`ARCHITECTURE.md` §4](../../ARCHITECTURE.md#4-pitfalls--gotchas). This page
explains the structure and links into it.

- [Package map](#package-map)
- [The contract](#the-contract)
- [Source registry](#source-registry)
- [The MangaDex adapter](#the-mangadex-adapter)
- [Downloader and layout](#downloader-and-layout)
- [Bundler](#bundler)
- [Java interop](#java-interop)
- [Adding a source](#adding-a-source)
- [Publishing](#publishing)

## Package map

| Package | Responsibility | Key types |
|---|---|---|
| [`domain`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/domain/) | The port and vocabulary. No I/O, imports no implementation. | `Catalog`, `Book`, `BookRef`, `BookId`, `SourceRef`, `Page`, `DownloadEvent`, `CatalogException` |
| [`source`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/source/) | Composition point for adapters | `CatalogRegistry` |
| [`source.mangadex`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/source/mangadex/) | The only adapter today | `MangaDexCatalog`, `MangaDexClient`, `MangaDexDto` |
| [`download`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/download/) | Streaming parallel writes to disk | `Downloader`, `Layout`/`StandardLayout`, `DownloadProgress` |
| [`bundle`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/bundle/) | Packs a downloaded book into `.cbz` | `Bundler` |
| [`interop`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/interop/) | Blocking, Java-friendly facade | `BlockingCatalog`, `Refs` |

`core` is **coroutine-first**: `find` and `search` are `suspend` functions, and
`download` returns a `Flow`. That is why `kotlinx-coroutines` is an `api`
dependency (it is part of the public signature), while OkHttp and serialization
stay `implementation`.

## The contract

See the [glossary](../domain/glossary.md) for the types. The rules every adapter
must keep:

| Rule | Concretely |
|---|---|
| Absence ≠ failure | A missing book returns `null` or an empty list. A timeout, a non-2xx status, or a bad JSON payload throws `CatalogException`. `MangaDexClient` maps 404 → `null` and any other non-2xx → `CatalogException`. |
| Resolve only what you own | Return `null`/empty for a `SourceRef` with a foreign scheme. Don't throw. |
| Mint round-trippable ids | A leaf adapter builds `BookId` from the source's native id, so `find(book.id)` works. |
| Cold, lazy download | Nothing runs until the flow is collected. One `Manifest` comes first, then `ChapterStarted` + `PageReady`s per chapter. Chapters may interleave, so key by `chapterId`. `Page.bytes()` fetches on demand. An empty flow means "nothing to download". |

> **Why a flow of events and not a list of pages:** large books must stream
> instead of being held in memory, and front-ends need the chapter and page counts
> up front to draw determinate progress bars. A bare `Flow<Page>` threw that
> structure away ([ADR-0003](../adr/0003-streaming-download-contract.md) →
> superseded by [ADR-0010](../adr/0010-structured-download-event-stream.md)).

## Source registry

[`CatalogRegistry`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/source/CatalogRegistry.kt)
is an immutable `scheme → factory` map. It is the **only** registration point:
the CLI's `--source`, the server's `/api/catalog`, and every "known: …" error hint
are all derived from it.

- `CatalogRegistry["mangadex"]` **constructs a new adapter** on each call, including a new HTTP client and a new rate limiter. Short-lived callers like the CLI don't care. Long-lived callers like the server must cache the instance (see [Server → Catalog API](server.md#catalog-api)).
- No reflection and no runtime registration: the native image has to see the whole table at build time.
- It sits in `source`, not `domain`, so that `domain` never imports an implementation ([ADR-0014](../adr/0014-single-catalog-registry-in-core.md)).

## The MangaDex adapter

The split is **`MangaDexCatalog` = mapping** and **`MangaDexClient` = transport**.
This is the file where mistakes are most expensive; read
[`ARCHITECTURE.md` §4.3](../../ARCHITECTURE.md#43-mangadex-transport-rate-limiting--retries-mangadexclient)
before editing it. Summary:

| Mechanism | Value | Guards against |
|---|---|---|
| `RateLimiter` (smooth, global per client) | 5 req/s across API **and** image CDN | MangaDex per-IP limits and bans |
| `RetryInterceptor` (outermost) | Retries only HTTP 429, honouring `Retry-After` / `X-RateLimit-Retry-After`, else exponential backoff | Transient throttling. Retries re-enter the limiter. |
| `maxRequestsPerHost` | 8 (OkHttp default is 5) | OkHttp throttling parallel downloads before the limiter does |
| Separate `reportClient` | No limiter, no retry, fire-and-forget | MangaDex@Home telemetry slowing or failing a download |
| `User-Agent` | `pixerion/0.1.0` | Stricter abuse handling of default agents |
| Chapter fan-out | `DOWNLOAD_WORKER = 4` | Serial at-home server resolution |
| One upload per chapter label | `distinctBy` label, first one wins | Two scanlation groups' "chapter 1" overwriting each other on disk |
| Language | hard-coded `"en"` | — (a known limitation, not a design rule) |

> **TODO (owner to confirm):** whether translated-language selection (`LANGUAGE = "en"`
> in `MangaDexCatalog`) is meant to become a parameter, and the
> `User-Agent` version string (`pixerion/0.1.0`), which is hard-coded and won't
> follow `version` in `build.gradle.kts`.

## Downloader and layout

[`Downloader`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/download/Downloader.kt)
runs the whole download, so front-ends only pass parameters:
`Downloader(root, workers = 4, layout = StandardLayout()).download(catalog, ref, progress)`.

1. `find(ref)`. If it returns `null`, the result is `null`, which means "no such book".
2. Collect the event flow. For each `PageReady`, `gate.acquire()` (the write pool), then `launch` a write. The semaphore also **back-pressures the source**, so pages are never produced faster than they are written.
3. `coroutineScope` waits for every write. The result is a `Summary(book, directory, stats)`, where `stats` holds the page and chapter counts.
4. `DownloadProgress` callbacks are serialized under a lock, so a listener needs no synchronization of its own.

On-disk layout ([`StandardLayout`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/download/Layout.kt)):

```
~/.pixerion/<source>/<book-title-slug>/chapters/ch-<label>/<NNN>.<ext>
~/.pixerion/<source>/<book-title-slug>/cbz/<book> - ch-<label>.cbz
```

> **Warning:** the `chapters/` layer (`StandardLayout.CHAPTERS_DIR`) is what the
> `Bundler` reads. Change the layout without changing the bundler and bundling
> silently finds nothing.

The concurrency model, ordering guarantees, and cancellation behaviour are
covered in depth in the [download pipeline design note](../design/download-pipeline.md).

## Bundler

[`Bundler`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/bundle/Bundler.kt)
is a pure filesystem step: it makes no network calls and doesn't use a catalog. It
writes one `.cbz` per `ch-*` directory, ordered numerically, with a minimal
`ComicInfo.xml`. The unusual parts:

- **`STORED` entries:** images are already compressed, so entries are stored
  uncompressed. This requires setting CRC-32 and the sizes by hand before
  `putNextEntry`. Get it wrong and the archive is corrupt.
- **Atomic writes:** each archive goes to a unique temp file, then
  `ATOMIC_MOVE`. A failure never leaves a partial `.cbz` behind.
- **Idempotent:** existing archives are skipped unless `overwrite = true`.

## Java interop

`core` can't be called from Java directly: Java can't call `suspend` functions, and
`Book.id` is a `@JvmInline value class` whose getter is name-mangled. The single
seam is [`interop`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/interop/):

```java
var catalog = new BlockingCatalog(CatalogRegistry.get("mangadex"));
Book book = catalog.find(new SourceRef("mangadex", id));   // blocks the calling thread
String id  = Refs.idOf(book);                               // not book.getId(): won't compile
String ref = Refs.render(book.getRef());                    // "mangadex:<uuid>"
```

If Java needs a new `core` capability, add a blocking method or projection to `interop`.
Don't create a second bridge ([`ARCHITECTURE.md` §4.1](../../ARCHITECTURE.md#41-the-java--kotlin-seam-java-callers--core)).

## Adding a source

The checklist, in short (the full walkthrough with code is in
[`CONTRIBUTING.md`](../../CONTRIBUTING.md#adding-a-new-catalog)):

1. Create `source/<name>/` with `<Name>Catalog` (mapping), `<Name>Client` (transport), DTOs, and a `SCHEME` constant.
2. Keep the contract rules above, especially translating transport errors into `CatalogException`.
3. Add **one** entry to `CatalogRegistry`. No CLI, server, or webapp change is needed.
4. Test against `MockWebServer`: search mapping, a clean miss, and failure → `CatalogException`.
5. Give it its own rate-limit and retry policy appropriate to that source. The MangaDex numbers aren't universal.
6. Write an ADR only if it brings a new dependency or a notable trade-off.

## Publishing

Coordinates: `io.modernia.pixerion:pixerion-core:0.1.0`, with sources and javadoc
jars. See [Release & artifacts](../workflows/release.md#publishing-the-core-library).

## Related

- [Architecture overview](overview.md) · [CLI](cli.md) · [Server](server.md)
- [Glossary](../domain/glossary.md)
- [Back to the hub](../index.md)
