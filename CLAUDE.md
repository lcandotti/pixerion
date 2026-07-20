# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Pixerion is a CLI that queries book catalogs from multiple sources behind one provider-agnostic interface. Four-module Gradle build:

- **`core`** — the domain model, the `Catalog` contract, source adapters, the downloader, and the cbz bundler. This is the publishable library (`io.modernia.pixerion:pixerion-core`). All reusable logic lives here.
- **`cli`** — thin picocli front-end. Parses args → calls `core` → renders. Not published. Ships as a GraalVM native binary.
- **`server`** — thin **Java** Spring Boot (Spring MVC) web front-end. Request → calls `core` → serializes JSON. Consumes `core` through its blocking interop facade (see below); not published, no native-image concern. Secured with Spring Security + stateless JWT, with users/roles in PostgreSQL (see ADR-0007, ADR-0008). The whole catalog API lives under **`/api`**; the server also serves the `webapp` bundle from the same origin (ADR-0013).
- **`webapp`** — the Angular SPA + the Gradle↔npm bridge (`node-gradle`, pinned auto-downloaded Node). `npm run build` output is packaged into a jar under `META-INF/resources/` that the server embeds via a dedicated `webapp` configuration (bootJar/bootRun only — **never the test classpath**, so server tests never run npm). All its tasks skip until an Angular app is scaffolded there (`ng new pixerion-webapp --directory . --skip-git` — the project name is load-bearing for the `dist/` path). Frontend dev loop: `ng serve --proxy-config proxy.conf.json` against a running backend, no Gradle involved.

> Note: the module was renamed `shared` → `core` (package `io.modernia.pixerion`) in ADR-0005. The accepted ADRs still say `shared` as the historical record (they're immutable once accepted) — treat "shared" in ADR prose as referring to `core`.

## Commands

```sh
./gradlew build                     # compile + all checks (lint + tests)
./gradlew check                     # all checks
./gradlew test                      # tests only
./gradlew :pixerion-core:test                # core (adapter/domain) tests only
./gradlew ktlintCheck               # lint gate (also wired into `check`)
./gradlew ktlintFormat              # auto-fix formatting
./gradlew :pixerion-cli:run --args="search berserk"   # run CLI on the JVM
./gradlew :pixerion-cli:nativeCompile        # build native binary → pixerion-cli/build/native/nativeCompile/pixerion
./gradlew :pixerion-cli:distZip              # runnable JVM distribution (bin/pixerion + jars)
./gradlew :pixerion-server:bootRun           # run the web backend (Spring MVC, port 8080)
./gradlew :pixerion-webapp:run               # Angular dev server (ng serve, port 4200, backend proxied to 8080)
./gradlew :pixerion-server:build             # compile + package the server (bootJar → pixerion-server.jar)
docker compose up --build           # build + run the server in a container (port 8080; healthcheck /actuator/health)
./gradlew :pixerion-core:publishToMavenLocal # publish the library locally
bash scripts/local/ci.sh            # reproduce the CI `verify` job locally (ktlint → test)
```

`scripts/local/ci.sh` mirrors the CI `verify` job so failures are caught before pushing. Targets: `all` (default), `lint`, `tests`.

Run a single test (JUnit 5 via Gradle):

```sh
./gradlew :pixerion-core:test --tests "io.modernia.pixerion.mangadex.MangaDexCatalogTest"
./gradlew :pixerion-core:test --tests "*MangaDexCatalogTest.find returns null*"   # single method
```

Toolchain: **JDK 25**, Kotlin 2.4.0 (supports the JVM 25 target; `core`/`cli` emit JDK 25 bytecode). ktlint is the formatter/linter (don't introduce a different style config — see project memory on sane defaults).

## Architecture

`Catalog` (`core`, `io.modernia.pixerion.domain`) is the central port. Callers depend only on domain types and cannot tell where data originates. Three operations: `find(ref)`, `search(query)`, `download(ref): Flow<DownloadEvent>`.

Key contract invariants (preserve these when adding adapters):
- **Absence vs. failure are distinct.** A genuinely missing book is a successful `null`/empty result; a source-level failure (timeout, non-2xx, malformed payload) is thrown as `domain.CatalogException`. Adapters must translate transport failures into `CatalogException`, never leak them.
- **`download` is a cold `Flow<DownloadEvent>`.** Collecting drives the download; the stream carries structure for determinate progress — `Manifest(chapters)` first, then per chapter a `ChapterStarted(chapterId, label, pages)` then one `PageReady(chapterId, page)` per page. Each `PageReady.page.bytes()` is fetched lazily so large books stream rather than materialize. `Downloader.download` takes an optional `DownloadProgress` listener (the CLI's live progress bars render from it). See ADR-0010.
- A reference is a `BookRef` — either a portable `BookId` or a source-scoped `SourceRef(scheme, value)`. An adapter resolves only the schemes it owns and returns `null`/empty for refs it can't resolve.

**MangaDexCatalog** (`io.modernia.pixerion.mangadex`) is the only adapter. HTTP/JSON transport is isolated in `MangaDexClient`; the catalog only maps DTOs (`MangaDexDto`) onto domain types. New adapters must follow this split: catalog = mapping, sibling client = all OkHttp/serialization.

`Downloader` and `Bundler` (cbz packaging) are pure `core` capabilities the CLI invokes with parameters — orchestration stays out of the CLI. On-disk layout is `<root>/<source>/<book>/chapters/ch-<chapter>/<NNN>.<ext>` under `~/.pixerion` by default — raw chapters sit under a dedicated `chapters/` purpose layer beside sibling layers like the bundler's `cbz/` (dropping it breaks the bundler; see `StandardLayout`).

### Adding a catalog source

1. New package under `pixerion-core/src/main/kotlin/io/modernia/pixerion/<source>/`: a `Catalog` impl mirroring `MangaDexCatalog`, a sibling client for transport, a `SCHEME` constant.
2. Register it in **two** thin front-ends, each in one place: the CLI's `catalogFor(source)` `when` in `pixerion-cli/.../command/CatalogCommand.kt` (add it to the "known" list in the error message too), and the server's `CatalogProvider.catalogFor` in `pixerion-server/.../CatalogProvider.java`.
3. Test against an in-process OkHttp `MockWebServer` (see `MangaDexCatalogTest`) — no live network.

### Consuming `core` from Java (the `server` module)

`core` is coroutine-first (`Catalog.find`/`search` are `suspend`, `download` returns `Flow<DownloadEvent>`), which **Java cannot call directly**. The Java `server` consumes `core` through the blocking interop facade in `io.modernia.pixerion.interop` (`BlockingCatalog` + `Refs`) — the single Kotlin interop seam. Notable: `Book.id` is a `@JvmInline value class`, so its getter is name-mangled and unusable from Java; `Refs.idOf`/`Refs.render` project it (and the sealed `BookRef`) to plain strings. Keep the coroutine-first contract intact and add Java-friendliness only behind this facade. See ADR-0007.

## Server auth (ADR-0008)

The `server` is secured with Spring Security + **stateless JWT** (HS256, symmetric secret in `app.security.jwt.secret`). Users/roles live in PostgreSQL via Spring Data JPA; `core` stays auth-agnostic. Key points when working on it:

- **Flow:** `POST /auth/login` (public) verifies credentials (BCrypt) and returns a JWT; clients send it as `Authorization: Bearer …`. `GET /auth/me` reports the caller. Public endpoints are `/auth/login`, `/actuator/health` (so the container healthcheck survives security), and the API docs — `/v3/api-docs`, `/v3/api-docs/**`, `/scalar`, `/scalar/**` (ADR-0012). The API families `/api/**` + `/auth/**` require a valid bearer token; any **other GET is the public SPA shell** (static bundle + history-API fallback to `index.html`, `SpaConfig` — backend prefixes exempt so API 404s stay 404s); anything else is denied. New backend endpoints must go under `/api` (secure by default; outside it they'd fall into the SPA rules).
- **Groups = roles → `ROLE_*` authorities.** `AppUser` ↔ `Role` (many-to-many); the JWT's `roles` claim is mapped back to authorities. `@EnableMethodSecurity` + `@PreAuthorize("hasRole('ADMIN')")` gate privileged ops (e.g. `POST /api/downloads`).
- **Schema via Flyway** (`pixerion-server/src/main/resources/db/migration`), Hibernate `ddl-auto=validate`. Baseline roles + an initial admin are seeded at startup by `DataInitializer` (idempotent), **not** in SQL — the admin password must be hashed by the app's `PasswordEncoder`. Admin creds and JWT secret come from `app.admin.*` / `app.security.jwt.*` (env-overridable; local defaults are `admin`/`admin` — override in prod).
- **JWT signing:** pin HS256 explicitly in the encoder header — `NimbusJwtEncoder` defaults to RS256 and fails to select a key for a symmetric secret.
- **Tests** run the full context against in-memory **H2** (Flyway disabled, Hibernate creates the schema, per-context random DB name) so no live Postgres is needed. Use `RestClient` against a random port — **Boot 4 ships Jackson 3 (`tools.jackson.*`)** and moved some test-autoconfigure packages, so avoid `com.fasterxml.jackson` / `@AutoConfigureMockMvc` imports.

## Server downloads (ADR-0011)

Downloads on the server are a **background job with SSE progress**, not a synchronous call. `POST /api/downloads` starts a job on the `downloadExecutor` (bounded pool, `AsyncConfig`) and returns `202` + `{ id }`; `GET /api/downloads/{id}/events` streams progress as **SSE** and `GET /api/downloads/{id}` reports status/summary — all admin-only, authenticated via the normal Bearer header (a browser must use a **fetch-based** SSE client, since native `EventSource` can't send `Authorization`). The `DownloadJob` *is* the `core` `DownloadProgress` sink (reached through `BlockingCatalog.download(ref, root, progress)`): callbacks only mutate an in-memory snapshot (they must not block — they run on `core`'s download threads), and a `@Scheduled` flusher pushes each running job's snapshot to subscribers as a `state` event, then a terminal `completed`/`error` event — the server analog of the CLI's repaint loop. A missing book is a terminal `NOT_FOUND` **status** (async), not a synchronous 404; an unknown source is still a synchronous 400. Jobs are in-memory (swept after a retention window) — they don't survive a restart or span instances.

## CLI behavior

Subcommands (`Main.kt` → picocli): `search`, `find`, `download`, `bundle`. `--source` defaults to `mangadex`. Refs are bare ids (scoped to `--source`) or explicit `scheme:id`. Exit codes: `0` success, `1` no result, `2` usage error or failure (unreachable source, I/O error — `ExecutionErrorHandler` maps anything a command doesn't handle itself to `2`). The `suspend` contract is bridged to picocli's blocking `call()` via `runBlocking` in `CatalogCommand`.

CLI code lives in root-level packages (`command`, `output`); `Main.kt` is in the default package so the generated entrypoint is `MainKt`. Command classes are kept deliberately plain so `picocli-codegen` (kapt) can emit complete GraalVM reachability metadata at build time — avoid runtime reflection in them.

## GraalVM native build gotchas

- **Config cache is disabled for native tasks only** (the plugin isn't compatible). They're marked `notCompatibleWithConfigurationCache` in `pixerion-cli/build.gradle.kts`, so `:pixerion-cli:nativeCompile` degrades gracefully with a warning — no manual flags needed.
- **Toolchain selection.** When `GRAALVM_HOME` is set (CI), Gradle toolchain detection is turned off so the real GraalVM is used. Otherwise Gradle auto-provisions GraalVM via Foojay — but that distribution ships a broken 0-byte `bin/native-image` symlink. Fix by installing a real GraalVM (`sdk install java 25.0.2-graalce`) or recreating the symlink. See project memory `graalvm_native_build`.

## Conventions

- **Conventional Commits** are required (`feat:`, `fix:`, `chore:`, …, optional module scope).
- **Keep [`ARCHITECTURE.md`](ARCHITECTURE.md) and the README in sync with the code.** `ARCHITECTURE.md` is the living map of module topology, contract invariants, and the project's pitfalls. Whenever a change touches something it documents — a `Catalog` contract invariant, a module seam (e.g. the `interop` facade), the download concurrency / rate-limit / retry model, the on-disk layout, or the build/security/native-image wiring — update the relevant `ARCHITECTURE.md` section (and README if user-facing) **in the same change**. Unlike ADRs, these two are mutable and meant to track the current state.
- **ADRs** record architecturally significant decisions in `docs/adr/` (MADR-lite template, immutable once accepted, index in `docs/adr/README.md`). Add one when a real alternative was weighed (library/framework, module split, contract/protocol). Skip for config/version/format tweaks.
- Dependencies are declared in the version catalog `gradle/libs.versions.toml`, never inline. Shared compiler/test setup lives in the `kotlin-jvm` convention plugin under `buildSrc` (Kotlin modules only — the Java `server` applies the `java` + Spring Boot plugins directly).
- Gradle dependency verification has been removed (no `gradle/verification-metadata.xml`) — adding/upgrading dependencies no longer requires regenerating verification metadata.
- Static analysis: SonarQube was removed in favor of **Qodana** (see ADR-0009, which supersedes ADR-0006). Qodana runs in CI via `.github/workflows/qodana_code_quality.yml` (JVM linter, `qodana.recommended` profile) — alongside ktlint + tests it's the code-quality gate. **Fresh code** is on (`pr-mode: true`): PRs are analyzed against only their changed code. There is no root `build.gradle.kts` — the root project configures nothing (it existed only to host the Sonar plugin). Coverage is produced by **JaCoCo** — applied in the `kotlin-jvm` convention plugin for `core` and directly in `pixerion-cli/build.gradle.kts` and `pixerion-server/build.gradle.kts` (`test` finalizes `jacocoTestReport`; XML lands under `build/reports/jacoco/`). Qodana consumes it: the workflow runs `./gradlew test`, then `qodana.yaml`'s `bootstrap` stages each module's `jacocoTestReport.xml` into `.qodana/code-coverage/` (where Qodana reads coverage from). All three modules have tests and emit coverage (the `server` via its H2-backed context/auth tests), so all three reports are staged; the `|| true` on the server copy is just defensive against a partial build. Run the Qodana workflow locally with `bash scripts/local/ci.sh qodana` (needs the Qodana CLI + Docker, plus a `QODANA_TOKEN` env var — the **project** access token from Qodana Cloud, not an organization token) to catch coverage/quality issues before pushing.
- Library preference: mature JVM incumbents (OkHttp, kotlinx.serialization for native-image friendliness) over Kotlin-first newer libs; GraalVM native-image compatibility is a real constraint.
