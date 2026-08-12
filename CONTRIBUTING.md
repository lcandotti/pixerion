# Contributing to Pixerion

This guide covers the development environment, the day-to-day workflows, the
project conventions, and a walkthrough for adding a new catalog source. For what
the project *is* — module topology, contract invariants, and the pitfalls to
watch when changing the code — see [`ARCHITECTURE.md`](ARCHITECTURE.md).

## Prerequisites

- **JDK 25** — the build provisions a matching toolchain via Gradle if needed.
- **Docker** — runs the infrastructure the web backend depends on (PostgreSQL).
- **GraalVM** — only for building the native CLI binary (optional).
- **Node** — not required: the `webapp` module's Gradle build auto-downloads a
  pinned Node. A local Node + Angular CLI is convenient for frontend work, but
  `./gradlew :pixerion-webapp:run` works without one.

Everything is driven through the [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html)
(`./gradlew`), so no local Gradle install is required.

## Development environment

The rule of thumb: **Docker runs only the services that are not this project** —
today that's just PostgreSQL, and the same will hold for any infrastructure added
later (a reverse proxy, a cache, …). The code you are actually changing runs on
your host, where it can reload. The packaged backend container
(`docker compose up --build`) runs a bootJar and cannot pick up code changes —
it's for verifying the final artifact, not for iterating.

### Backend loop

```sh
docker compose up postgres            # infrastructure only
./gradlew :pixerion-server:bootRun    # the backend on your host, port 8080
```

No configuration needed: `application.properties` defaults already point at
`localhost:5432` with the compose credentials (`pixerion`/`pixerion`). Restart
`bootRun` to pick up a change. Don't run the compose `server` service at the
same time — it also binds port 8080 (`docker compose stop server` if it's up).

### Frontend loop (webapp code)

```sh
./gradlew :pixerion-webapp:run        # ng serve on :4200, hot reload
# equivalent, using your own Node:
#   cd pixerion-webapp && ng serve --proxy-config proxy.conf.json
```

Develop against `http://localhost:4200` — Angular's dev server live-reloads on
every save, and [`proxy.conf.json`](pixerion-webapp/proxy.conf.json) forwards
`/api`, `/auth`, `/actuator`, `/v3`, and `/scalar` to `localhost:8080`. Anything
answering on 8080 works as the backend: `bootRun` (backend loop above), or the
packaged container (`docker compose up`) when you're not touching backend code.
The Gradle↔npm packaging is never part of this loop — the bundle is only built
into the jar by `:pixerion-server:bootJar` / `bootRun`.

### Verifying the packaged artifact

```sh
docker compose up --build             # server (embedded SPA) + PostgreSQL, port 8080
```

Use this to check the real deployment shape — the embedded SPA, the container
healthcheck, env-based configuration (`.env`) — not for iterating.

## Build, test, lint

Shared build logic lives in a convention plugin under `buildSrc`; every
dependency is declared in the version catalog at
[`gradle/libs.versions.toml`](gradle/libs.versions.toml) rather than inline.

```sh
./gradlew build                 # compile everything and run all checks
./gradlew check                 # all checks, including tests
./gradlew test                  # tests only
./gradlew :pixerion-core:test   # core (adapter/domain) tests only
./gradlew ktlintCheck           # lint gate (wired into `check`)
./gradlew ktlintFormat          # auto-fix formatting
```

To reproduce the full CI `verify` job locally before pushing — ktlint, then
tests + coverage — run the helper script:

```sh
bash scripts/local/ci.sh                      # all: lint + test
bash scripts/local/ci.sh lint                 # ktlintCheck only
bash scripts/local/ci.sh tests                # test + coverage; prints report paths
```

Adapter tests drive a real `Catalog` against an in-process
[`MockWebServer`](https://github.com/square/okhttp/tree/master/mockwebserver)
(see [`MangaDexCatalogTest`](pixerion-core/src/test/kotlin/io/modernia/pixerion/source/mangadex/MangaDexCatalogTest.kt)) —
no live network. Prefer this pattern for new code.

Before opening a PR, make sure `./gradlew build` is green (it runs ktlint + all
tests).

## Conventions

- **Commits** follow [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat:`, `fix:`, `chore:`, …), optionally with a module scope.
- **Decisions** with lasting consequences get an ADR under [`docs/adr/`](docs/adr/)
  (MADR-lite; immutable once accepted). Add one when a real alternative was weighed.
- **`ARCHITECTURE.md`, the README, and this file are kept in sync with the code** —
  if a change alters a contract, a module seam, a workflow, or the
  build/security wiring, update them in the same change.
- Keep front-ends thin: all reusable logic belongs in `core`; `cli`/`server` only
  turn input into a `core` call and render the result. Avoid speculative
  abstractions.

## Adding a new catalog

A catalog is a source adapter that implements the provider-agnostic
[`Catalog`](pixerion-core/src/main/kotlin/io/modernia/pixerion/domain/Catalog.kt) port.
Adding one is entirely self-contained in `core`: the adapter package plus a
one-line entry in the shared registry. No front-end changes. The snippets below
sketch a placeholder source named `acme` — swap in your own source name, scheme,
and DTO mapping.

1. **Create the adapter package.** Add `pixerion-core/src/main/kotlin/io/modernia/pixerion/source/<source>/`
   and implement `Catalog` there, mirroring
   [`MangaDexCatalog`](pixerion-core/src/main/kotlin/io/modernia/pixerion/source/mangadex/MangaDexCatalog.kt).
   Give it a `SCHEME` constant — the
   [`SourceRef`](pixerion-core/src/main/kotlin/io/modernia/pixerion/domain/SourceRef.kt)
   scheme the adapter owns — and keep all HTTP/JSON out of the adapter by putting it
   in a sibling client (e.g. `AcmeClient`), so the catalog only maps DTOs onto domain
   types (as `MangaDexCatalog` does with
   [`MangaDexClient`](pixerion-core/src/main/kotlin/io/modernia/pixerion/source/mangadex/MangaDexClient.kt)):

   ```kotlin
   package io.modernia.pixerion.acme

   import io.modernia.pixerion.domain.Book
   import io.modernia.pixerion.domain.BookId
   import io.modernia.pixerion.domain.BookRef
   import io.modernia.pixerion.domain.Catalog
   import io.modernia.pixerion.domain.DownloadEvent
   import io.modernia.pixerion.domain.SourceRef
   import kotlinx.coroutines.flow.Flow
   import kotlinx.coroutines.flow.channelFlow

   class AcmeCatalog internal constructor(
       private val client: AcmeClient,
   ) : Catalog {

       /** Creates a catalog targeting the source's default endpoint. */
       constructor() : this(AcmeClient())

       override suspend fun search(query: Map<String, String>): List<Book> =
           client.search(query).map { it.toBook() }

       override suspend fun find(ref: BookRef): Book? {
           // Resolve only the references this adapter understands; null otherwise.
           val id = ref.toSourceId() ?: return null
           return client.fetch(id)?.toBook()
       }

       override fun download(ref: BookRef): Flow<DownloadEvent> = channelFlow {
           // Empty flow (not even a Manifest) = nothing to download for this ref.
           val id = ref.toSourceId() ?: return@channelFlow
           val chapters = client.chapters(id)
           send(DownloadEvent.Manifest(chapters.size))
           for (chapter in chapters) {
               val pages = client.pages(chapter.id)
               send(DownloadEvent.ChapterStarted(chapter.id, chapter.label, pages.size))
               for (page in pages) {
                   // Map each source page onto a domain Page whose bytes() stays lazy.
                   send(DownloadEvent.PageReady(chapter.id, page.toPage()))
               }
           }
       }

       companion object {
           /** The SourceRef scheme this adapter owns. */
           const val SCHEME: String = "acme"
       }
   }

   /**
    * Extracts the source-native id a [BookRef] points at, or null if this adapter
    * cannot resolve it. As a leaf adapter, a [BookId] it minted is taken as its id.
    */
   private fun BookRef.toSourceId(): String? = when (this) {
       is SourceRef -> value.takeIf { scheme == AcmeCatalog.SCHEME }
       is BookId -> value
   }
   ```

2. **Honour the contract.** Resolve the references you understand and return
   `null`/empty for the rest; translate source-level failures (timeouts, non-2xx,
   malformed payloads) into
   [`CatalogException`](pixerion-core/src/main/kotlin/io/modernia/pixerion/domain/CatalogException.kt)
   — usually raised inside the client — keeping them distinct from a genuinely absent
   book. As a leaf adapter with no cross-source matching layer, mint each
   [`BookId`](pixerion-core/src/main/kotlin/io/modernia/pixerion/domain/BookId.kt) from
   the source's native id so the ids you issue round-trip back through `find`.
   `download` returns a **cold** `Flow<DownloadEvent>` — one `Manifest(chapters)`
   first, then per chapter a `ChapterStarted(chapterId, label, pages)` followed by
   one `PageReady(chapterId, page)` per page, with each page's image bytes still
   fetched lazily; a ref that resolves to nothing yields an **empty** flow, not even
   a `Manifest` ([ADR-0010](docs/adr/0010-structured-download-event-stream.md)). The
   default HTTP/JSON stack is OkHttp + kotlinx.serialization
   ([ADR-0001](docs/adr/0001-http-and-json-stack-for-source-adapters.md)).

3. **Register it — one entry, one file.** Add your scheme to the `registry` map in
   [`CatalogRegistry`](pixerion-core/src/main/kotlin/io/modernia/pixerion/source/CatalogRegistry.kt).
   No front-end changes are needed: the CLI (and the web backend) resolve
   `--source` through the registry and render their "known:" hints from
   `CatalogRegistry.known`
   ([ADR-0014](docs/adr/0014-single-catalog-registry-in-core.md)).

   ```kotlin
   // pixerion-core/.../source/CatalogRegistry.kt
   private val registry: Map<String, () -> Catalog> =
       mapOf(
           MangaDexCatalog.SCHEME to ::MangaDexCatalog,
           AcmeCatalog.SCHEME to ::AcmeCatalog,
       )
   ```

   Entries are factories, so nothing is constructed for a source nobody queries.
   Keep the registry free of runtime registration and reflection — it must stay
   resolvable at build time for the CLI's native image.

4. **Test it.** Add a test under `pixerion-core/src/test/kotlin/io/modernia/pixerion/source/<source>/`
   that exercises the adapter against a `MockWebServer`
   ([like `MangaDexCatalogTest`](pixerion-core/src/test/kotlin/io/modernia/pixerion/source/mangadex/MangaDexCatalogTest.kt)),
   covering search/find mapping, a clean miss (`null`/empty), and the
   failure-to-`CatalogException` translation.

5. **Record the decision if it's significant.** A new source that introduces a new
   dependency or a notable trade-off warrants an ADR under [`docs/adr/`](docs/adr/);
   a routine adapter following the existing pattern does not.
