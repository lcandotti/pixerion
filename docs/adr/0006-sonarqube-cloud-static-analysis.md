# 0006 — SonarQube Cloud for static analysis

- **Status:** Superseded by [ADR-0009](0009-drop-sonarqube-cloud-static-analysis.md)
- **Date:** 2026-06-26

## Context

The CI quality gate already runs ktlint (style) and the test suite
([CI workflow](../../.github/workflows/ci.yml)), but nothing tracks code quality
trends, security hotspots, or maintainability over time, nor decorates pull
requests with new-code findings. The project's GitHub is connected to **SonarQube
Cloud**, so the question is only how to feed analysis into it, not whether to
adopt a server.

Two integration paths exist for a Gradle/Kotlin project:

- the **SonarScanner for Gradle** plugin (`org.sonarqube`), which runs as a Gradle
  task and infers its inputs (Kotlin source sets, test sources, JUnit/coverage
  report locations) from the build model; or
- the **standalone SonarScanner CLI** (e.g. the `sonarqube-scan-action`), driven
  by a hand-maintained `sonar-project.properties`.

## Decision

Use the **`org.sonarqube` Gradle plugin**, applied to the **root** project in
[`build.gradle.kts`](../../build.gradle.kts) so a single `./gradlew sonar`
analyzes every module. SonarQube Cloud coordinates (`sonar.projectKey`,
`sonar.organization`, `sonar.host.url = https://sonarcloud.io`) are set in the
root `sonar { properties { … } }` block; no `sonar-project.properties` file is
used. The plugin and its version are declared in the version catalog
([`gradle/libs.versions.toml`](../../gradle/libs.versions.toml)).

Code coverage is fed to Sonar via **JaCoCo**, applied in the `kotlin-jvm`
convention plugin ([`buildSrc`](../../buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts))
for `core` and wired directly in [`pixerion-cli/build.gradle.kts`](../../pixerion-cli/build.gradle.kts)
(which uses its own plugin set), so both modules emit an XML report; the Sonar
plugin auto-detects them, so no coverage paths are configured by hand. JaCoCo (the
mature JVM incumbent) is chosen over Kover (the Kotlin-first option), consistent
with the project's library-preference bias; its tool version is pinned to one that
understands the JDK 25 bytecode the toolchain targets. The `test` task finalizes
`jacocoTestReport`, so an ordinary test run leaves a fresh report for the scan.

A narrow `sonar.coverage.exclusions` list keeps the coverage ratio meaningful by
dropping code that cannot be unit-tested in a useful way: the `Main.kt` process
entrypoint (running it would terminate the test JVM) and the `MangaDexDto.kt`
`@Serializable` data holders (mostly kotlinx.serialization-generated plumbing —
synthetic constructors and `equals`/`hashCode`/`copy` — whose real mapping logic
is already exercised through the client/catalog tests). These files are still
analyzed for bugs and code smells; only their coverage is excluded.

CI runs the scan in the existing `verify` job, after the tests, so it reuses the
compiled classes and the JaCoCo/test reports already on disk. The job's checkout
uses `fetch-depth: 0` for accurate new-code/blame attribution, and the scan reads
the `SONAR_TOKEN` repository secret. PR runs are decorated by the SonarQube Cloud
GitHub app; pushes to `main`/`develop` update the project's main branch.

Because the Sonar tasks are not compatible with Gradle's configuration cache
(enabled in `gradle.properties`), they are marked
`notCompatibleWithConfigurationCache` — the same graceful-degradation approach the
GraalVM native tasks use ([ADR-0004](0004-parallel-download-and-layout.md),
`pixerion-cli/build.gradle.kts`) — so `./gradlew sonar` works without manual flags.

## Consequences

- **Easier:** analysis inputs track the build automatically — new modules, source
  sets, and JaCoCo coverage reports are picked up without editing a separate
  properties file. A module gets coverage simply by applying the convention plugin.
- **Easier:** one `./gradlew sonar` invocation reproduces locally exactly what CI
  runs, given a `SONAR_TOKEN`.
- **Cost:** the root project now carries a build script and the Sonar plugin,
  and a Sonar run discards the configuration-cache entry (with a warning) for that
  invocation.
- **Cost:** CI depends on a `SONAR_TOKEN` secret and on the SonarQube Cloud GitHub
  app being installed; without the token the scan step fails.

## Alternatives considered

- **Standalone SonarScanner CLI + `sonar-project.properties`** — fewer build
  changes (no root build script, no Gradle plugin), but the properties file
  duplicates source/test/coverage paths the Gradle model already knows, drifting
  as modules change, and it does not resolve the classpath the way the Gradle
  integration does. Rejected: worse fit for a multi-module Gradle build.
- **Kover for coverage** — JetBrains' Kotlin-first coverage tool, well integrated
  with Kotlin and Gradle. Rejected in favour of JaCoCo to stay with the mature JVM
  incumbent (the project's standing preference) and JaCoCo's first-class,
  auto-detected support in the Sonar Gradle plugin.
