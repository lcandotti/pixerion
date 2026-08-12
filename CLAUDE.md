# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Pixerion is a CLI that queries book catalogs from multiple sources behind one provider-agnostic interface. Four-module Gradle build:

- **`core`** — the domain model, the `Catalog` contract, source adapters, the downloader, and the cbz bundler. This is the publishable library (`io.modernia.pixerion:pixerion-core`). All reusable logic lives here.
- **`cli`** — thin picocli front-end. Parses args → calls `core` → renders. Not published. Ships as a GraalVM native binary.
- **`server`** — a bare **Java** Spring Boot (Spring MVC) skeleton: just the `Application` bootstrap, minimal properties, and a context-loads test. **The user is building this module by hand as a learning exercise — do NOT implement server features, endpoints, config, or tests unless explicitly asked.** Its `build.gradle.kts` deliberately keeps the full dependency stack (web, security/JWT, JPA/Postgres/Flyway, springdoc, test libs) as guidance on which libraries to use. It consumes `core` through the blocking interop facade (see below); not published, no native-image concern.
- **`webapp`** — the Angular SPA + the Gradle↔npm bridge (`node-gradle`, pinned auto-downloaded Node). `npm run build` output is packaged into a jar under `META-INF/resources/` that the backend embeds via a dedicated `webapp` configuration (bootJar/bootRun only — **never the test classpath**, so `:pixerion-server:test` never runs npm). All its tasks skip until an Angular app is scaffolded there (`ng new pixerion-webapp --directory . --skip-git` — the project name is load-bearing for the `dist/` path). Frontend dev loop: `ng serve --proxy-config proxy.conf.json` against a running backend, no Gradle involved.

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
./gradlew :pixerion-server:build             # compile + package the backend (bootJar → pixerion-server.jar)
docker compose up --build           # build + run the backend in a container (port 8080; healthcheck /actuator/health)
./gradlew :pixerion-core:publishToMavenLocal # publish the library locally
bash scripts/local/ci.sh            # reproduce the CI `verify` job locally (ktlint → test)
```

`scripts/local/ci.sh` mirrors the CI `verify` job so failures are caught before pushing. Targets: `all` (default), `lint`, `tests`.

Run a single test (JUnit 5 via Gradle):

```sh
./gradlew :pixerion-core:test --tests "io.modernia.pixerion.source.mangadex.MangaDexCatalogTest"
./gradlew :pixerion-core:test --tests "*MangaDexCatalogTest.find returns null*"   # single method
```

Toolchain: **JDK 25**, Kotlin 2.4.0 (supports the JVM 25 target; `core`/`cli` emit JDK 25 bytecode). ktlint is the formatter/linter (don't introduce a different style config — see project memory on sane defaults).

## Architecture

`Catalog` (`core`, `io.modernia.pixerion.domain`) is the central port. Callers depend only on domain types and cannot tell where data originates. Three operations: `find(ref)`, `search(query)`, `download(ref): Flow<DownloadEvent>`.

Key contract invariants (preserve these when adding adapters):
- **Absence vs. failure are distinct.** A genuinely missing book is a successful `null`/empty result; a source-level failure (timeout, non-2xx, malformed payload) is thrown as `domain.CatalogException`. Adapters must translate transport failures into `CatalogException`, never leak them.
- **`download` is a cold `Flow<DownloadEvent>`.** Collecting drives the download; the stream carries structure for determinate progress — `Manifest(chapters)` first, then per chapter a `ChapterStarted(chapterId, label, pages)` then one `PageReady(chapterId, page)` per page. Each `PageReady.page.bytes()` is fetched lazily so large books stream rather than materialize. `Downloader.download` takes an optional `DownloadProgress` listener (the CLI's live progress bars render from it). See ADR-0010.
- A reference is a `BookRef` — either a portable `BookId` or a source-scoped `SourceRef(scheme, value)`. An adapter resolves only the schemes it owns and returns `null`/empty for refs it can't resolve.

Adapters live under `io.modernia.pixerion.source.*`, one package each, indexed by `io.modernia.pixerion.source.CatalogRegistry` — an immutable scheme→factory map that is the single place a new source is registered. It sits *above* `domain` deliberately: the domain owns the `Catalog` port and must never import an implementation (ADR-0014).

**MangaDexCatalog** (`io.modernia.pixerion.source.mangadex`) is the only adapter. HTTP/JSON transport is isolated in `MangaDexClient`; the catalog only maps DTOs (`MangaDexDto`) onto domain types. New adapters must follow this split: catalog = mapping, sibling client = all OkHttp/serialization.

`Downloader` and `Bundler` (cbz packaging) are pure `core` capabilities the CLI invokes with parameters — orchestration stays out of the CLI. On-disk layout is `<root>/<source>/<book>/chapters/ch-<chapter>/<NNN>.<ext>` under `~/.pixerion` by default — raw chapters sit under a dedicated `chapters/` purpose layer beside sibling layers like the bundler's `cbz/` (dropping it breaks the bundler; see `StandardLayout`).

### Adding a catalog source

1. New package under `pixerion-core/src/main/kotlin/io/modernia/pixerion/source/<source>/`: a `Catalog` impl mirroring `MangaDexCatalog`, a sibling client for transport, a `SCHEME` constant.
2. Add one entry to the `registry` map in `io.modernia.pixerion.source.CatalogRegistry` (`pixerion-core/.../source/CatalogRegistry.kt`). **That is the only registration point** — front-ends resolve `--source` names through it and render their "known:" hints from `CatalogRegistry.known`, so no CLI or server change is needed.
3. Test against an in-process OkHttp `MockWebServer` (see `MangaDexCatalogTest`) — no live network.

### Consuming `core` from Java

`core` is coroutine-first (`Catalog.find`/`search` are `suspend`, `download` returns `Flow<DownloadEvent>`), which **Java cannot call directly**. Java callers consume `core` through the blocking interop facade in `io.modernia.pixerion.interop` (`BlockingCatalog` + `Refs`) — the single Kotlin interop seam. Notable: `Book.id` is a `@JvmInline value class`, so its getter is name-mangled and unusable from Java; `Refs.idOf`/`Refs.render` project it (and the sealed `BookRef`) to plain strings. Keep the coroutine-first contract intact and add Java-friendliness only behind this facade.

## CLI behavior

Subcommands (`Main.kt` → picocli): `search`, `find`, `download`, `bundle`. `--source` defaults to `mangadex`. Refs are bare ids (scoped to `--source`) or explicit `scheme:id`. Exit codes: `0` success, `1` no result, `2` usage error or failure (unreachable source, I/O error — `ExecutionErrorHandler` maps anything a command doesn't handle itself to `2`). The `suspend` contract is bridged to picocli's blocking `call()` via `runBlocking` in `CatalogCommand`.

CLI code lives in root-level packages (`command`, `output`); `Main.kt` is in the default package so the generated entrypoint is `MainKt`. Command classes are kept deliberately plain so `picocli-codegen` (kapt) can emit complete GraalVM reachability metadata at build time — avoid runtime reflection in them.

## GraalVM native build gotchas

- **Config cache is disabled for native tasks only** (the plugin isn't compatible). They're marked `notCompatibleWithConfigurationCache` in `pixerion-cli/build.gradle.kts`, so `:pixerion-cli:nativeCompile` degrades gracefully with a warning — no manual flags needed.
- **Toolchain selection.** When `GRAALVM_HOME` is set (CI), Gradle toolchain detection is turned off so the real GraalVM is used. Otherwise Gradle auto-provisions GraalVM via Foojay — but that distribution ships a broken 0-byte `bin/native-image` symlink. Fix by installing a real GraalVM (`sdk install java 25.0.2-graalce`) or recreating the symlink. See project memory `graalvm_native_build`.

## Conventions

- **Conventional Commits** are required (`feat:`, `fix:`, `chore:`, …, optional module scope).
- **Keep [`ARCHITECTURE.md`](ARCHITECTURE.md) and the README in sync with the code.** `ARCHITECTURE.md` is the living map of module topology, contract invariants, and the project's pitfalls. Whenever a change touches something it documents — a `Catalog` contract invariant, a module seam (e.g. the `interop` facade), the download concurrency / rate-limit / retry model, the on-disk layout, or the build/native-image wiring — update the relevant `ARCHITECTURE.md` section (and README if user-facing) **in the same change**. Unlike ADRs, these two are mutable and meant to track the current state.
- **ADRs** record architecturally significant decisions in `docs/adr/` (MADR-lite template, immutable once accepted, index in `docs/adr/README.md`). Add one when a real alternative was weighed (library/framework, module split, contract/protocol). Skip for config/version/format tweaks.
- Dependencies are declared in the version catalog `gradle/libs.versions.toml`, never inline. Shared compiler/test setup lives in the `kotlin-jvm` convention plugin under `buildSrc` (Kotlin modules only — the Java `server` applies the `java` + Spring Boot plugins directly).
- Gradle dependency verification has been removed (no `gradle/verification-metadata.xml`) — adding/upgrading dependencies no longer requires regenerating verification metadata.
- Static analysis: SonarQube was removed in favor of **Qodana** (see ADR-0009, which supersedes ADR-0006). Qodana runs as the `qodana` job in `.github/workflows/ci.yml` (JVM linter, `qodana.recommended` profile), between the `verify` (ktlint + tests) job and the artifact builds — a failed scan stops the pipeline before anything is built. It also enforces dependency licenses (`CheckDependencyLicenses` + `licenseRules` in `qodana.yaml`). **Fresh code** is on (`pr-mode: true`): PRs are analyzed against only their changed code. There is no root `build.gradle.kts` — the root project configures nothing (it existed only to host the Sonar plugin). Coverage is produced by **JaCoCo** — applied in the `kotlin-jvm` convention plugin for `core` and directly in `pixerion-cli/build.gradle.kts` and `pixerion-server/build.gradle.kts` (`test` finalizes `jacocoTestReport`; XML lands under `build/reports/jacoco/`). Qodana consumes it: the `verify` job runs `./gradlew test` and hands the XMLs to the `qodana` job as a `jacoco-coverage` artifact (restored at the same paths), then `qodana.yaml`'s `bootstrap` stages each module's `jacocoTestReport.xml` into `.qodana/code-coverage/` (where Qodana reads coverage from). Each module with tests emits a report that gets staged; the `|| true` on the copy lines is just defensive against a partial build or an empty report. Run the Qodana workflow locally with `bash scripts/local/ci.sh qodana` (needs the Qodana CLI + Docker, plus a `QODANA_TOKEN` env var — the **project** access token from Qodana Cloud, not an organization token) to catch coverage/quality issues before pushing.
- Library preference: mature JVM incumbents (OkHttp, kotlinx.serialization for native-image friendliness) over Kotlin-first newer libs; GraalVM native-image compatibility is a real constraint.
