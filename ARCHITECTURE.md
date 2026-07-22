# Architecture

> High-level map of Pixerion, its data flow, and — most importantly — the
> **pitfalls** that are easy to break when changing the code. Read this before
> touching the `Catalog` contract, the Java↔Kotlin seam, or the
> download/rate-limit path.
>
> Keep this file in sync with the code: when a change alters a contract invariant,
> a module seam, the concurrency/rate-limit model, or the build/security wiring,
> update the relevant section here (and the README) in the same change.

## 1. The big picture

Pixerion queries book catalogs from multiple sources behind one provider-agnostic
interface, downloads books, and bundles them into `.cbz`. It is a four-module
Gradle build with a strict, one-directional dependency graph:

```
        ┌────────────┐        ┌────────────┐  embeds  ┌────────────┐
        │ pixerion-  │        │ pixerion-  │◄─────────│ pixerion-  │
        │   cli      │        │  server    │ (static  │  webapp    │
        │ (Kotlin,   │        │ (Java,     │  bundle) │ (Angular   │
        │  picocli)  │        │  Spring)   │          │  SPA)      │
        └─────┬──────┘        └─────┬──────┘          └────────────┘
              │  depends on         │  depends on (via interop facade)
              └──────────┬──────────┘
                         ▼
                 ┌───────────────┐
                 │ pixerion-core │   the only publishable library
                 │  (Kotlin,     │   io.modernia.pixerion:pixerion-core
                 │ coroutine-1st)│
                 └───────────────┘
```

- **`core`** — domain model, the `Catalog` port, source adapters, the downloader,
  the cbz bundler, and the Java-interop facade. **All reusable logic lives here.**
  Coroutine-first (`suspend` / `Flow`). The only module published to Maven.
- **`cli`** — thin picocli front-end. Parses args → calls `core` → renders. Ships
  as a GraalVM native binary. Not published.
- **`server`** — thin **Java** Spring Boot (Spring MVC) web front-end over `core`
  (through the blocking interop facade). Currently a bare skeleton — just the
  application bootstrap — being built by hand. Not published.
- **`webapp`** — the Angular SPA, packaged by Gradle into a jar of static resources
  embedded into the backend's bootJar. No JVM code; it never touches `core`.

**Rule:** dependencies only ever point *inward* to `core`. `cli` and `server` never
depend on each other, and `core` never depends on either. Anything reusable belongs
in `core`; the front-ends stay dumb (args/HTTP → `core` call → render). Do not add
speculative abstractions.

> Directory names are `pixerion-core` / `pixerion-cli` / `pixerion-server`; Gradle
> project paths match (`:pixerion-core`, …). In prose and older ADRs the modules are
> called `core`/`cli`/`server` (and `core` was once `shared` — see ADR-0005).

## 2. The central contract: `Catalog`

`Catalog` (`pixerion-core`, `io.modernia.pixerion.domain`) is the port every source
adapter implements and every caller depends on. Three operations:

```kotlin
suspend fun find(ref: BookRef): Book?              // one book, or null if absent
suspend fun search(query: Map<String, String>): List<Book>
fun download(ref: BookRef): Flow<DownloadEvent>    // cold stream: manifest → chapters → pages
```

A `BookRef` is one of two variants (sealed): a portable **`BookId`** or a
source-scoped **`SourceRef(scheme, value)`**. An adapter resolves only the schemes
it owns and returns `null`/empty for anything else.

### Contract invariants — **do not break these**

These are the load-bearing rules. Every adapter, and every layer that forwards
these calls, must preserve them:

1. **Absence vs. failure are distinct.**
   - A genuinely missing book → a *successful* `null` (`find`) / empty result
     (`search`, `download`).
   - A source-level failure (timeout, non-2xx, malformed payload) → **thrown**
     `domain.CatalogException`.
   - Adapters must **translate transport failures into `CatalogException`** and
     never leak `IOException`, `SerializationException`, HTTP codes, etc. See
     `MangaDexClient.fetch` (`404` → `null`, any other non-2xx → `CatalogException`).

2. **`download` is a *cold* `Flow<DownloadEvent>`.** Nothing happens until the flow
   is collected; collecting drives the download. The stream carries the book's
   *structure* so a caller can report determinate progress: a single
   `Manifest(chapters)` first, then per chapter a `ChapterStarted(chapterId, label,
   pages)` followed by one `PageReady(chapterId, page)` per page. Each
   `PageReady.page.bytes()` is still fetched **lazily**, on demand, so a large book
   streams rather than materializing in memory. An empty flow (not even a
   `Manifest`) means "nothing to download", not "download failed"; failures surface
   as `CatalogException` *during collection*. See
   [ADR-0010](docs/adr/0010-structured-download-event-stream.md).

3. **A leaf adapter mints its own `BookId`s** from the source's native id, so an
   id it issued round-trips back through `find`. It returns `null` for refs it
   doesn't own (`MangaDexCatalog` resolves only the `mangadex` scheme + its own
   ids).

## 3. Data flow

- **search / find** — front-end parses a source name + query/ref → picks the
  `Catalog` for that source → maps DTOs → domain `Book`s. `SourceRef(scheme, value)`
  is the reference type used across the wire.
- **download** — `Downloader` (in `core`) resolves the book via `find`, collects
  the adapter's cold `Flow<DownloadEvent>`, and fans page writes out to a bounded
  worker pool, writing under `root` per the `Layout`. It reports live progress to an
  optional `DownloadProgress` listener (default no-op; callbacks serialized so the
  sink needs no locking) — the seam front-ends render from.
  Orchestration lives in `core`, not in callers.
  - *CLI:* renders live bars in the terminal (`DownloadProgressView`).
- **bundle** — `Bundler` (in `core`) is a **pure filesystem** step over an
  already-downloaded tree; no catalog or network. Produces one `.cbz` per chapter.

### On-disk layout (StandardLayout)

```
<root>/<source>/<book>/chapters/ch-<chapter>/<NNN>.<ext>   # raw pages (download)
<root>/<source>/<book>/cbz/<book> - ch-<chapter>.cbz        # archives (bundle)
```

`root` defaults to `~/.pixerion`. Every segment is reduced to filesystem-safe
characters (`slug`), pages are zero-padded (`%03d`). Note the **`chapters/`
purpose layer**: raw images live under `chapters/` so sibling purposes (the
bundler's `cbz/`) never mix in with image folders. **The `Bundler` depends on this
exact tree** (`StandardLayout.CHAPTERS_DIR`) — changing the layout without updating
the bundler breaks bundling silently (it just finds no chapters).

## 4. Pitfalls & gotchas

The rest of this document is the important part: concrete traps, grouped by area.

### 4.1 The Java ↔ Kotlin seam (Java callers → `core`)

`core` is coroutine-first; **Java cannot call `suspend` functions or `runBlocking`
directly.** Everything Java touches goes through the single interop seam in
`io.modernia.pixerion.interop`:

- **`BlockingCatalog`** — the *only* bridge. Wraps a `Catalog` and exposes plain
  blocking `find`/`search`/`download` via `runBlocking`. It is Kotlin because only
  Kotlin can enter the `suspend` world. **Do not** add another `runBlocking` seam
  elsewhere; keep the coroutine-first contract intact and add Java-friendliness
  only here.
  - *Consequence:* each call **blocks the calling (servlet) thread** until the
    coroutine completes. Fine for Spring MVC; would need rethinking under WebFlux.
  - `download(ref, root?, progress?)` takes an optional `DownloadProgress` — the
    same listener the CLI renders from — so Java can observe live progress without
    touching coroutines.
- **`Refs`** — projects Kotlin-only shapes to plain strings. **`Book.id` is a
  `@JvmInline value class`, so its getter is name-mangled (`getId-<hash>()`) and
  *uncallable* from Java.** Use `Refs.idOf(book)` and `Refs.render(ref)` instead of
  reaching for `book.getId()`/branching the sealed `BookRef` from Java — the latter
  won't even compile.

**Trap:** exposing any new `core` type to Java that involves a value class, sealed
type, or `suspend`/`Flow` signature. Add a projection/blocking method to the
interop seam rather than trying to consume it from Java directly.

### 4.2 Registering a new source

A new adapter must be wired into every front-end, each in exactly one spot.
Today that is the CLI: the `catalogFor(source)` `when` in
`pixerion-cli/src/main/kotlin/command/CatalogCommand.kt` — and add the scheme to
the "known:" hint in the unknown-source error message.

See the README "Adding a new catalog" walkthrough for the full checklist (adapter
package, sibling transport client, `SCHEME` constant, `MockWebServer` test).

### 4.3 MangaDex transport, rate limiting & retries (`MangaDexClient`)

This is the most subtle file in the codebase. Watch for:

- **Adapter = mapping, client = transport.** `MangaDexCatalog` only maps DTOs;
  *all* OkHttp/serialization lives in `MangaDexClient`. New adapters must keep this
  split.
- **Interceptor order is load-bearing.** The client is built with
  `RetryInterceptor` **outermost** and `RateLimitInterceptor` below it, so a
  retried request **re-enters** the rate limiter (rather than bypassing it). Swap
  the order and retries will burst past the rate cap and trip 429s.
- **The policy travels with every client.** `MangaDexClient.withDefaultPolicy`
  augments a client with the retry/rate-limit interceptors and the raised
  per-host cap; the default client and the `MangaDexCatalog(baseUrl, httpClient)`
  constructor (proxies, mirrors, custom timeouts) both go through it — a
  caller-supplied client must never end up talking to the API unpaced.
- **One global rate governor.** A single `RateLimiter` (smoothed to
  `MAX_REQUESTS_PER_SECOND = 5.0`) paces *all* traffic — API calls *and* image-CDN
  fetches — because the MangaDex@Home image nodes are rate-limited at least as
  strictly as the API. `maxRequestsPerHost` is raised to 8 (OkHttp default 5) so
  parallel downloads aren't throttled *by OkHttp* — but the global limiter is what
  actually keeps us under the per-IP cap.
- **Only 429 is retried.** `RetryInterceptor` honours `Retry-After` /
  `X-RateLimit-Retry-After`, else exponential backoff. Other non-2xx (e.g. 503)
  pass through unchanged so the "unreachable → `CatalogException`" policy still
  applies. It blocks an OkHttp dispatcher thread (not a coroutine) during backoff —
  intentional and harmless there.
- **MangaDex@Home reporting is fire-and-forget telemetry.** Sent on a *separate*,
  deliberately plain `reportClient` (no rate limiter, no retry) so it never slows
  or fails a download. Only sent for `*.mangadex.network` hosts (the canonical
  `uploads.mangadex.org` and anything else is skipped, per spec). Its outcome is
  ignored. **Don't route reports through the main client** — they'd compete with
  image fetches for rate-limit permits.
- **User-Agent matters.** MangaDex applies stricter abuse handling to bare/default
  agents; the client sends a descriptive `pixerion/<version>` UA.

### 4.4 Download concurrency (two independent layers)

There are **two** concurrency limits, easy to confuse:

1. **Inside the adapter** — `MangaDexCatalog.download` fans out across *chapters*
   (`DOWNLOAD_WORKER = 4` via a `Semaphore`), resolving each chapter's at-home
   server in parallel and `send`ing that chapter's `ChapterStarted` + its
   `PageReady`s into a `channelFlow` (a single `Manifest` is sent first, before the
   fan-out). Because chapters resolve concurrently, events from different chapters
   interleave — consumers key per-chapter state by `chapterId`. The feed is first
   collapsed to **one upload per chapter label** (`distinctBy`, first upload wins):
   MangaDex returns a separate entry per scanlation-group upload of the same
   chapter, and since the on-disk layout keys chapter directories by label,
   downloading two versions of "chapter 1" would have concurrent workers
   overwriting each other's files.
2. **Inside `Downloader`** — a separate bounded pool (`workers`, default 4) of
   coroutines writes *pages* to disk. Its `Semaphore` gate both bounds write
   concurrency **and back-pressures the collector** (`gate.acquire()` before
   `launch`), so pages aren't produced faster than they're written.

The enclosing `coroutineScope` joins every write before returning (structured
concurrency = built-in wait-group). Chapter count in the `Summary` is the number of
distinct `chapterId`s the manifest announced (a lock-free `ConcurrentHashMap` keyed
by id — robust even when two chapters share a display label). A chapter is complete
once its written-page count reaches the `pages` its `ChapterStarted` declared, which
also drives the `DownloadProgress` completion callback.

For the full mechanics — event ordering guarantees, how the rendezvous channel
propagates backpressure from the write gate all the way back to the source, the
progress-callback threading model, and failure/cancellation flow — see the design
note [docs/design/download-pipeline.md](docs/design/download-pipeline.md).

### 4.5 Bundler (`.cbz`)

- **Stored, not deflated.** Images are already compressed, so entries use
  `ZipEntry.STORED` (uncompressed). STORED entries require the CRC-32, `size`, and
  `compressedSize` to be set *by hand* before `putNextEntry` — see `storeEntry`. Get
  this wrong and the archive is corrupt.
- **Atomic writes.** Each archive is written to a uniquely named temp sibling
  (`Files.createTempFile`, so concurrent bundles can't collide) then moved into
  place with `ATOMIC_MOVE` (falling back to a plain move where unsupported); a
  mid-write failure deletes the temp file, never leaving a half-formed `.cbz`
  or `.tmp` litter behind.
- **Idempotent by default.** Existing archives are skipped unless `overwrite=true`.
- Emits a minimal `ComicInfo.xml` at the archive root (Series/Number/PageCount)
  from purely local info — no network. A stray chapter file with that exact name
  is *not* treated as a page (it would collide as a duplicate ZIP entry).

### 4.6 The embedded SPA (`webapp`)

- **One artifact serves UI + API.** The `pixerion-webapp` jar carries the compiled
  Angular bundle under `META-INF/resources/` (a Spring Boot classpath
  static-resource root), embedded into the backend's bootJar.
- **npm never runs for JVM tests.** The backend consumes the webapp jar through a
  dedicated `webapp` Gradle configuration wired into `bootJar`/`bootRun` **only** —
  deliberately not `implementation` — so `:pixerion-server:test` has no frontend on
  its classpath and never triggers a Node/npm build. Don't "simplify" it into a
  normal dependency.
- **The webapp module is inert until scaffolded.** Its npm tasks skip while
  `pixerion-webapp/package.json` doesn't exist. The Angular app is created with
  `ng new pixerion-webapp --directory . --skip-git` in that directory (the project
  name is load-bearing: Gradle packages `dist/pixerion-webapp/browser`). Node itself
  is pinned in the version catalog and auto-downloaded by the `node-gradle` plugin.
- **Frontend dev loop doesn't build jars**: `./gradlew :pixerion-webapp:run` (or a
  plain `ng serve --proxy-config proxy.conf.json`) starts the hot-reloading dev
  server on `:4200`, proxying the backend prefixes to `localhost:8080`. Gradle
  builds the bundle only for `bootJar`/`bootRun`.

### 4.7 Build, native image & tooling

- **Configuration cache is enabled globally** (`gradle.properties`), but the
  **GraalVM native tasks are not compatible** with it. They are
  marked `notCompatibleWithConfigurationCache` (in `pixerion-cli/build.gradle.kts`),
  so those runs degrade gracefully with a warning
  instead of failing — no manual `--no-configuration-cache` needed.
- **GraalVM toolchain selection.** When `GRAALVM_HOME` is set (CI), Gradle toolchain
  detection is turned **off** so the real GraalVM is used. Otherwise Gradle
  auto-provisions GraalVM via Foojay — but that distribution ships a **broken 0-byte
  `bin/native-image` symlink**. Fix by installing a real GraalVM
  (`sdk install java 25.0.2-graalce`) or recreating the symlink. (ADR-0004; project
  memory `graalvm_native_build`.)
- **picocli command classes must stay reflection-free.** `picocli-codegen` (kapt)
  emits GraalVM reachability metadata at build time; runtime reflection in a command
  would break the native image. `Main.kt` lives in the default package so the
  entrypoint is `MainKt`.
- **`pixerion-server`'s build applies the Boot BOM via Gradle-native
  `platform(SpringBootPlugin.BOM_COORDINATES)`** rather than the legacy
  `io.spring.dependency-management` plugin. (This was originally
  motivated by strict dependency verification, since removed, but the Gradle-native
  platform remains the cleaner approach.)
- **JaCoCo is pinned to a version that understands JDK 25 bytecode** (`0.8.13`) in
  the `kotlin-jvm` convention plugin *and* directly in `cli`/`server` builds (they
  don't use the convention plugin). Bump all three together. Each module emits an XML
  coverage report under `build/reports/jacoco/`. In CI the `verify` job runs
  `./gradlew test` and hands the XML reports to the `qodana` job as an artifact
  (restored at the same paths), then `qodana.yaml`'s `bootstrap` stages them into
  `.qodana/code-coverage/` (the directory Qodana reads coverage from); **fresh code**
  is enabled (`pr-mode: true`) so PRs are gated on their changed code. The `qodana`
  job sits between `verify` (ktlint → test) and the artifact builds in
  `.github/workflows/ci.yml` — a failed scan stops the pipeline before anything is
  built. Qodana also enforces dependency licenses (`CheckDependencyLicenses`:
  LGPL-3.0 project license, Apache-2.0 dependencies allowed — see `qodana.yaml`).
  `scripts/local/ci.sh` reproduces the CI `verify` job locally (ktlint → test).
- **Library choices favor mature JVM incumbents** (OkHttp, kotlinx.serialization for
  its no-reflection, native-image-friendly codegen) over Kotlin-first newcomers.
  GraalVM native-image compatibility is a real constraint on library choice.
- **`core` exposes coroutines as `api`** (the `Catalog` contract is `suspend` +
  `Flow`, so coroutines is part of its public API), while okhttp/serialization stay
  `implementation`. The POM mirrors this split.

## 5. Where things live (quick index)

| Concern | Location |
|---|---|
| The port | `pixerion-core/.../domain/Catalog.kt` |
| Refs / identity | `domain/BookRef.kt`, `BookId.kt`, `SourceRef.kt`, `Book.kt` |
| MangaDex adapter | `pixerion-core/.../mangadex/` (`MangaDexCatalog`, `MangaDexClient`, `MangaDexDto`) |
| Download + layout | `pixerion-core/.../download/` (`Downloader`, `Layout`) |
| Bundler | `pixerion-core/.../bundle/Bundler.kt` |
| Java interop seam | `pixerion-core/.../interop/` (`BlockingCatalog`, `Refs`) |
| CLI source registry | `pixerion-cli/.../command/CatalogCommand.kt` (`catalogFor`) |
| Web backend bootstrap | `pixerion-server/.../Application.java` (skeleton, built by hand) |
| Angular app + npm bridge | `pixerion-webapp/` (`build.gradle.kts`, `proxy.conf.json`) |
| Shared build logic | `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts` |
| Dependencies (version catalog) | `gradle/libs.versions.toml` |
| Decisions (ADRs) | `docs/adr/` |

For the rationale behind these choices, see the ADRs in
[`docs/adr/`](docs/adr/) (index in [`docs/adr/README.md`](docs/adr/README.md)).
