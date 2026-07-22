# Pixerion

A tool for querying book catalogs from multiple sources behind a single,
provider-agnostic interface — with a native-image **CLI** built on a reusable
core.

[![CI](https://github.com/lcandotti/pixerion/actions/workflows/ci.yml/badge.svg)](https://github.com/lcandotti/pixerion/actions/workflows/ci.yml)
[![License: LGPL v3](https://img.shields.io/badge/License-LGPL_v3-blue.svg)](LICENSE)

## Overview

Pixerion adapts different book sources (starting with [MangaDex](https://mangadex.org))
to one `Catalog` contract, so callers depend only on domain types and never on a
specific provider. The reusable logic — the contract, source adapters, a parallel
streaming downloader, and a `.cbz` bundler — lives in a single publishable library
(`core`); front-ends like the thin **CLI** stay dumb layers over it.

## Features

- **Provider-agnostic catalog** — search, look up, and download books through one
  interface; sources are pluggable adapters.
- **Streaming parallel downloads** — pages stream lazily and are written by a
  bounded worker pool, so large books don't materialize in memory.
- **`.cbz` bundling** — package a downloaded book into per-chapter comic archives
  (with `ComicInfo.xml`) for readers like Panel, Komga, Kavita, or Mihon.
- **Native CLI** — builds to a standalone GraalVM native binary (no JVM needed at
  runtime).

## Requirements

- **JDK 25** (the build provisions a toolchain via Gradle if needed).
- **GraalVM** — only for building the native CLI binary (optional; see
  [Native binary](#native-binary)).
- **Docker** — only for running the web backend with its PostgreSQL database via
  Compose (optional; the CLI needs neither).

Everything is driven through the [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html)
(`./gradlew`), so no local Gradle install is required.

## Quick start

```sh
git clone https://github.com/lcandotti/pixerion.git
cd pixerion
./gradlew build                                   # compile + run all checks
./gradlew :pixerion-cli:run --args="search berserk"
```

## CLI usage

```
pixerion search   [--source <s>] <query>                     # search a catalog by title
pixerion find     [--source <s>] <ref>                       # fetch a single book by reference
pixerion download [--source <s>] [-o <dir>] [-w <n>] <ref>   # download a book to disk
pixerion bundle   [-o <dir>] [--overwrite] <book-dir>        # package a download into .cbz archives
```

- `--source` selects the catalog (default: `mangadex`).
- A `find`/`download` **reference** is either a bare source id (scoped to
  `--source`) or an explicit `scheme:id` pair, e.g.
  `mangadex:801513ba-a712-498c-8f57-cae55b38cc92`.
- **Exit codes:** `0` success, `1` no result (e.g. a `find` miss), `2` usage error
  or failure (unreachable source, I/O error).

Run it on the JVM with the `application` plugin, passing args via `--args`:

```sh
./gradlew :pixerion-cli:run --args="find 801513ba-a712-498c-8f57-cae55b38cc92"
```

`download` saves a book's pages in parallel (`--workers`, default 4) under
`~/.pixerion` by default — override with `--output <dir>`. Raw pages are laid out as
`<root>/<source>/<book>/chapters/ch-<chapter>/<NNN>.<ext>`; the `chapters/` layer
keeps images apart from sibling outputs like the bundler's `cbz/`.

`bundle` packages a downloaded book — the book directory whose `chapters/` layer
holds the `ch-<chapter>/` folders — into one `.cbz` (zipped images) per chapter. It is purely local (no catalog or network).
Archives go to a `cbz/` subfolder by default so they never mix with the raw image
dirs; existing archives are skipped unless `--overwrite` is given:

```sh
pixerion bundle ~/.pixerion/mangadex/Berserk
# → ~/.pixerion/mangadex/Berserk/cbz/Berserk - ch-1.cbz, …
```

## Native binary

The CLI builds to a standalone native binary via the
[GraalVM Native Build Tools](https://graalvm.github.io/native-build-tools/).
picocli's reachability metadata is generated at build time by `picocli-codegen`, and
other dependencies' metadata (e.g. OkHttp's) comes from the GraalVM metadata
repository — so no reflection config is hand-written.

```sh
./gradlew :pixerion-cli:nativeCompile
# output: pixerion-cli/build/native/nativeCompile/pixerion
./pixerion-cli/build/native/nativeCompile/pixerion search berserk
```

Building the image needs a GraalVM toolchain, and there are two environment gotchas
(config-cache incompatibility and a broken auto-provisioned `native-image` symlink)
that the build already handles — see
[`ARCHITECTURE.md` §4.7](ARCHITECTURE.md#47-build-native-image--tooling) for the details.

## Web backend & frontend

The `pixerion-server` module is a bare Spring Boot (Spring MVC) skeleton — its
web API is being built by hand and is not documented here yet.

```sh
docker compose up --build                   # backend + PostgreSQL together (port 8080)
# or, for development: run only the database in Docker and the backend on the host
docker compose up postgres
./gradlew :pixerion-server:bootRun          # port 8080; defaults point at localhost:5432
```

The `pixerion-webapp` module holds the Angular SPA and its Gradle↔npm bridge:
`:pixerion-server:bootJar` / `bootRun` embed the compiled bundle automatically
(building it with a pinned, auto-downloaded Node), so one jar carries both, and
backend tests never trigger the npm build. Day-to-day frontend development
bypasses Gradle entirely — the hot-reload dev loops are described in
[`CONTRIBUTING.md`](CONTRIBUTING.md).

## Architecture

`Catalog` (in `core`, package `io.modernia.pixerion.domain`) is the provider-agnostic
port: callers depend only on domain types (`Book`, `BookId`, `SourceRef`) and cannot
tell where the data originates. Source adapters implement it; the first is
**`MangaDexCatalog`**, whose HTTP/JSON transport is isolated in `MangaDexClient`.
Source-level failures surface as `domain.CatalogException`, kept distinct from a
genuinely absent book (a successful `null`/empty result).

For the full module topology, data flow, contract invariants, and the **pitfalls**
to watch when changing the code (the Java↔Kotlin interop seam, the rate-limit/retry
path, the download concurrency model, and the build gotchas), see
**[`ARCHITECTURE.md`](ARCHITECTURE.md)**. Architecturally significant decisions are
recorded as ADRs in [`docs/adr/`](docs/adr/).

| Module | Description |
|--------|-------------|
| `core` | Domain model, the `Catalog` contract, source adapters, the downloader, the cbz bundler, and the Java-interop facade. Coroutine-first. Published as `io.modernia.pixerion:pixerion-core`. |
| `cli` | The picocli CLI. Wires commands to catalogs; ships as a native executable. Not published. |
| `server` | A Java Spring Boot web front-end skeleton over `core` (via its blocking interop facade) — being built by hand, currently just the application bootstrap. Not published. |
| `webapp` | The Angular SPA plus its Gradle↔npm bridge; `npm run build` output is packaged into a jar embedded into the backend's bootJar. Not published. |

## Contributing

Commits follow [Conventional Commits](https://www.conventionalcommits.org/)
(`feat:`, `fix:`, `chore:`, …), and `./gradlew build` must be green before opening
a PR (it runs ktlint + all tests). Everything else a contributor needs — the
development environment (Docker runs only the infrastructure; the code you're
changing runs on your host), the backend/frontend dev loops, the build/test/lint
commands, the project conventions, and the step-by-step guide to adding a new
catalog source — lives in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## License

Pixerion is licensed under the **GNU Lesser General Public License v3.0**
(LGPL-3.0) — see [`LICENSE`](LICENSE). The LGPL lets the `pixerion-core` library be
used by other software while keeping modifications to the library itself open.
