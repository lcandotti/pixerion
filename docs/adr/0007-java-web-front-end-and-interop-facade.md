# 0007 — Java web front-end and a blocking interop facade in core

- **Status:** Accepted
- **Date:** 2026-06-29

## Context

Pixerion has so far been `core` (the publishable `Catalog` library + downloader)
plus `cli` (a thin Kotlin front-end). The next front-end is a **web backend** —
initially to expose `search`/`find`/`download` over HTTP, with user management
and auth anticipated later. Two questions had to be answered together:

1. **What language/framework for the web module?** The author wants to learn the
   Spring stack and prefers mature JVM incumbents (see project memory). Spring
   Boot's Kotlin support is first-class and stable, but the explicit goal is to
   learn Spring in **Java**.

2. **Can a Java module consume `core` cleanly?** `core` is coroutine-first by
   contract: `Catalog.find`/`search` are `suspend`, `Catalog.download` returns a
   `Flow<Page>`, `Page.bytes()` is `suspend`, and `Downloader.download` is
   `suspend`. Per [ADR-0003](0003-streaming-download-contract.md) and
   [ADR-0005](0005-library-namespace-and-publishing-for-shared.md), kotlinx
   coroutines is deliberately **`api`** (public) surface.

The hard constraint: **Java cannot call any of these directly.** A `suspend` fun
compiles to a hidden `Continuation` parameter; `runBlocking` takes a `suspend`
lambda Java cannot form; `Flow` is not a type Java collects. So consuming the
coroutine-first contract from Java requires a bridge — and because only Kotlin
can invoke `suspend` functions, **the bridge must itself be written in Kotlin.**

Forces:

- Keep `core`'s elegant coroutine-first contract as the primary API; do not
  rewrite it to blocking for the sake of one consumer.
- The web module is to be Java, for learning — so it needs Java-friendly call
  sites into `core`.
- "JVM-compatible library" means *clean Java call sites*, not a zero-Kotlin
  classpath (any Java app consuming a Kotlin library already carries
  `kotlin-stdlib`).
- Avoid speculative abstraction (project memory): add the bridge only where
  interop genuinely requires it, in one place.

## Decision

1. **New `:server` module, written in Java, Spring Boot (Spring MVC).** It joins
   `cli` as a second thin front-end over `core` — request → `core` → serialize,
   the web analogue of the CLI's args → `core` → render. Spring **MVC**
   (blocking, servlet) is chosen over WebFlux: it is the simplest stack to learn
   and pairs naturally with a blocking facade. The module is a plain JVM
   application (`bootJar`/`bootRun`); unlike `cli` it has **no** GraalVM
   native-image concern. It applies the `java` plugin + Spring Boot plugins
   directly, **not** the `kotlin-jvm` convention plugin.

2. **A blocking interop facade lives in `core`:**
   `io.modernia.pixerion.interop.BlockingCatalog`. It wraps a `Catalog` and
   exposes blocking, Java-friendly signatures (`find`, `search`, `download`)
   backed by `runBlocking` and the existing `Downloader`. This is the single
   interop seam — coroutines stay an implementation detail behind it. It is
   Kotlin because only Kotlin can bridge `suspend`; it lives in `core` because
   it is reusable boundary logic, not server-specific.

3. **`core`'s coroutine-first contract is unchanged.** `Catalog`, `Page`, and
   `Downloader` keep their `suspend`/`Flow` shapes; `cli` keeps calling them
   directly via `runBlocking`. The facade is additive.

4. **Download endpoint downloads to disk and returns a `Summary`** (reusing
   `Downloader` unchanged), rather than streaming page bytes over HTTP. Lowest
   friction to verify the integration; HTTP byte-streaming can come later.

## Consequences

- **Easier:** Java (and any non-coroutine JVM caller) consumes `core` through one
  clean, blocking facade; the web module is a conventional Spring MVC app.
- **Module split reinforced:** `core` = reusable capability, `cli`/`server` =
  thin front-ends. The "register a new source in one place" rule now has a Java
  mirror (`server`'s catalog provider) alongside `cli`'s `catalogFor`.
- **One interop seam to maintain:** if the facade's blocking semantics or
  threading need tuning (e.g. dispatcher choice), it changes in exactly one file.
- **Cost / discipline:** Java callers still carry `kotlin-stdlib` and
  `kotlinx-coroutines` transitively (coroutines is `api`); the facade hides the
  *programming model*, not the classpath. The blocking facade ties up a request
  thread per call — acceptable for MVC, and revisited if the server moves to
  reactive streaming.

## Alternatives considered

- **Write the server in Kotlin.** Lowest friction — it could call the coroutine
  API directly with no facade. Rejected for the explicit learning goal (Spring in
  Java); the facade is cheap and also benefits future non-Kotlin consumers.
- **Neutralize `core`'s public contract** (blocking + Reactive Streams
  `Publisher<Page>`) so it is Java-first with Kotlin coroutine extensions on top.
  The "right" answer if broad Java/reactive consumption were a present
  requirement, but it inverts the whole library's elegant contract and touches
  `cli` — speculative for a single learning consumer. Deferred; revisit if real
  Java/reactive consumers arrive.
- **Spring WebFlux instead of MVC.** Enables true page-streaming via
  `Flow → Publisher`, but reactive is harder to learn and would pull the
  coroutine→Publisher bridge into `core` now. Rejected for the first iteration.
- **Separate Spring Boot repo consuming `core` via a Gradle composite build or
  published artifact.** Appropriate once the web app earns its own deploy cadence,
  but adds two-repo/version friction now; starting in-build keeps one version and
  atomic refactors. Extracting later is mechanical. Deferred.
- **Facade in a separate `core-java` module** (so Java consumers avoid the
  transitive coroutines dep). Cleaner dependency story (cf. `reactor-core` +
  `reactor-kotlin-extensions`), but more build ceremony than warranted now. The
  facade is a mechanical extract-to-module away if a Java consumer ever objects to
  the transitive dep. Deferred.
