# 0002 — picocli for the CLI

- **Status:** Accepted
- **Date:** 2026-06-24

## Context

The `cli` module is the user-facing entry point for querying catalogs
(`search`, `find`, …). A hard requirement is that it ship as a **GraalVM
native-image executable** with maximum compatibility — the native build must be
reliable, not something hand-tuned each time a command is added.

That requirement dominates the choice of argument-parsing approach, because CLI
libraries lean on annotations and runtime reflection, which is exactly what
native-image struggles with unless the necessary reachability metadata is
provided at build time. The same constraint already shaped the adapter stack in
[ADR-0001](0001-http-and-json-stack-for-source-adapters.md): the OkHttp
dependency the CLI pulls in transitively needs reachability metadata too, which
the GraalVM build-tools plugin sources from the reachability-metadata repository.

Secondary force: a preference for mature JVM-ecosystem incumbents over
Kotlin-first newcomers.

## Decision

Use **picocli** (`info.picocli:picocli`) for argument parsing, with:

- **`picocli-codegen`** run as an annotation processor via **kapt**, which
  generates GraalVM reachability metadata (`reflect-config.json`,
  `resource-config.json`, `proxy-config.json`) from the `@Command` classes at
  build time. The metadata is namespaced under
  `META-INF/native-image/<group>/<artifact>/`.
- the **GraalVM `org.graalvm.buildtools.native` Gradle plugin**, with its
  metadata repository enabled, providing `:pixerion-cli:nativeCompile` / `:pixerion-cli:nativeRun`
  and supplying metadata for transitive dependencies (OkHttp).

Commands are `Callable<Int>` returning explicit exit codes; the `suspend`
`Catalog` contract is bridged to picocli's blocking `call()` with `runBlocking`.

## Consequences

- **Easier:** the native build is self-configuring — adding a command or option
  regenerates its metadata, so `nativeCompile` keeps working without
  hand-written reflection config. picocli is purpose-built for GraalVM and is the
  reference choice for native CLIs.
- **Easier:** standard help, version, subcommands, and usage errors come for free
  and behave like a conventional JVM CLI.
- **Cost:** the `cli` module now applies **kapt** (an extra Kotlin compilation
  stage and stub generation) solely to run picocli's Java annotation processor.
- **Cost:** producing the native image requires a GraalVM JDK with
  `native-image`; a plain JDK can still build and run the CLI on the JVM
  (`:pixerion-cli:run`).

## Alternatives considered

- **Clikt** — ergonomic Kotlin-first DSL with documented GraalVM support, but its
  reflection story is less battle-tested for native than picocli's codegen, and
  it is the Kotlin-first option rather than the JVM incumbent. Rejected.
- **Hand-rolled parsing** — zero dependencies and trivially native-compatible
  (no reflection at all), but weaker help/UX and growing boilerplate as the
  command surface expands. Rejected as a false economy now that picocli's native
  metadata is generated automatically.
