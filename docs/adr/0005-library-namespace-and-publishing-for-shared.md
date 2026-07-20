# 0005 — Library namespace and publishing for the shared module

- **Status:** Accepted
- **Date:** 2026-06-25

## Context

Pixerion is a two-module build: `shared` holds the reusable capability (the
`Catalog` port, source adapters, `Downloader`, `Layout`) and `cli` is a thin
user-facing application over it. The intent is that **`shared` be consumable by
other projects as a dependency** — not just internally by `cli`.

Two problems stood in the way:

- **Package names.** `shared` declared bare top-level packages (`domain`,
  `download`, `mangadex`). On a consumer's classpath these collide with anyone
  else's `domain`/`download` and carry no provenance — `import domain.Book` says
  nothing about where it came from. A library meant for third-party use needs a
  globally-unique, reverse-DNS namespace.
- **No coordinates.** `shared` had no Maven `group`/`version` and no
  `maven-publish` wiring, so nothing could actually depend on it. "Importable by
  others" requires a real published artifact, ideally with sources for IDE
  navigation.

`cli`, by contrast, is a **leaf application**, not a dependency: it is shipped as
a GraalVM native executable (see
[ADR-0002](0002-picocli-for-the-cli.md)), never resolved as a library. It does
not need a namespace or coordinates.

Forces:

- Collision-safety and provenance for a public classpath.
- The `api`/`implementation` split of `shared` becomes *externally visible* once
  published — coroutines is already public API (`api`) because `Catalog.download`
  returns a `Flow` (see [ADR-0003](0003-streaming-download-contract.md)), so the
  generated POM must reflect that.
- Configuration cache is enabled project-wide (`gradle.properties`), so any
  publishing config must be configuration-cache compatible.
- No public release is finalized yet, so the remote destination should be
  low-friction now and swappable later.

## Decision

1. **Reverse-DNS namespace for `shared`.** All `shared` sources move under
   `io.modernia.pixerion.*` (`…domain`, `…download`, `…mangadex`), with the test
   tree mirroring the layout. Public types are now collision-safe and
   self-identifying.

2. **`cli` stays unnamespaced and unpublished.** `Main.kt` lives in the default
   (root) package — its entry class is `MainKt` (the `application` plugin's
   `mainClass` is set accordingly) — and the internal `command` / `output`
   subpackages stay bare. `cli` applies no `maven-publish`. This keeps the module
   split honest: `cli` is a dumb consumer of `shared`, never a dependency itself.

3. **`shared` is publishable via `maven-publish`.** Coordinates are
   **`io.modernia.pixerion:pixerion-core:0.1.0`** — the `group` aligns with the
   source namespace and the `artifactId` follows the JVM `<project>-<module>`
   convention (cf. `jackson-core`, `kotlinx-coroutines-core`, `spring-core`):
   `-core` names the foundational capability and keeps the jar self-identifying on
   a consumer's classpath (a bare `shared.jar` would not be). It also leaves room
   for sibling artifacts (e.g. a future `pixerion-mangadex`) without renaming this
   one. The publication carries the binary, **sources and
   javadoc jars**, a POM with project metadata, and Gradle Module Metadata. POM
   dependency scopes follow the `api`/`implementation` split (coroutines →
   `compile`; okhttp/serialization → `runtime`).

4. **Local-first, GitHub Packages as the remote.** `publishToMavenLocal` works
   out of the box for local consumption and testing. A GitHub Packages
   repository is wired but **registered only when an owner is configured**
   (`-Pgpr.owner=…` / gradle.properties / CI env), so the build stays green
   without credentials. Repository and credential reads use Gradle `providers` to
   remain configuration-cache compatible.

## Consequences

- **Easier:** `shared` types import collision-free and carry their origin
  (`io.modernia.pixerion.…`); consumers get a real artifact with attached
  sources for IDE navigation.
- **Discipline:** the `api`/`implementation` boundary now has external
  consequences — what is `api` leaks into the POM's compile scope and onto every
  consumer's classpath, so the split must be maintained deliberately.
- **Module split reinforced:** `cli` being unpublished and unnamespaced makes its
  "application, not library" role explicit and structural, not just convention.
- **Cost:** existing ADRs (0001/0003) link to source paths under the old
  `…/kotlin/domain/…` layout; those links are now stale. Per the immutability
  rule they are left as-is rather than edited.
- **Open / deferred:** the remote destination is not finalized. **Maven Central**
  would additionally require the `signing` plugin (GPG) and a Sonatype/Central
  setup; the POM currently carries placeholder `url` / `license` / `scm` /
  `developer` values that must be filled before any public release.

## Alternatives considered

- **Keep bare top-level packages.** Zero churn and fine for an application, but
  unacceptable for a redistributable library: name collisions on a consumer's
  classpath and no provenance. Rejected the moment `shared` is meant for external
  use.
- **Namespace and/or publish `cli` too.** Unnecessary — `cli` is a native
  executable consumed by end users, never resolved as a dependency. Namespacing
  it would add ceremony for no consumer, and publishing it would blur the module
  split. Rejected.
- **`io.modernia.pixerion:shared` (default `artifactId`).** Shorter, but a bare
  `shared-0.1.0.jar` is ambiguous in a downstream dependency list. Chose an
  explicit, prefixed artifact id instead.
- **`pixerion-shared` as the artifact id.** Initially chosen, but `shared` names
  the module's *role inside this build* (the module `cli` and others share), not
  the library's *capability*. Published JVM libraries are named by what they
  provide (`-core`, `-client`, `-json`), so `pixerion-core` was adopted — matching
  the ecosystem and reading correctly to an external consumer.
- **`io.modernia:pixerion-core` (group without the `pixerion` segment).** Avoids
  repeating `pixerion` across group and artifact, but the repetition is the JVM
  norm (`com.fasterxml.jackson.core:jackson-core`) and the jar filename carries no
  group, so the prefix is what keeps it self-identifying. Aligning `group` with
  the source namespace (`io.modernia.pixerion`) won out for consistency.
- **Publish to Maven Central now.** Highest reach, but pulls in GPG signing and
  Sonatype onboarding before a public release is even decided. GitHub Packages is
  lower-friction today and the same `MavenPublication` retargets to Central later
  by adding the repository and `signing`. Deferred.
- **Dokka for real javadoc.** Better API docs, but an extra plugin/dependency; an
  empty-but-present javadoc jar already satisfies the Central requirement. Can be
  added later without reshaping the publication. Deferred.
