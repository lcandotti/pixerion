# 0009 — Drop SonarQube Cloud static analysis

- **Status:** Accepted
- **Date:** 2026-07-02
- **Supersedes:** [ADR-0006](0006-sonarqube-cloud-static-analysis.md)

## Context

[ADR-0006](0006-sonarqube-cloud-static-analysis.md) adopted SonarQube Cloud (via
the `org.sonarqube` Gradle plugin) as the project's static-analysis and
code-quality gate, fed by JaCoCo coverage. That wiring carried real overhead: a
root `build.gradle.kts` existing solely to host the Sonar plugin, a `SONAR_TOKEN`
secret CI depended on, a configuration-cache exception for the `sonar` task, and a
Sonar-specific rule suppression (`java:S4502`) in the codebase.

The decisive driver is **cost and free-tier limitations**: SonarQube Cloud's free
tier is too constrained for this project's needs, and lifting those limits means
moving to a paid plan that isn't justified here. The project is therefore trialling
**Qodana** (JetBrains) instead, which runs as its own CI action / local runner
without Gradle-side plugin wiring. Qodana has **no free tier — only a 60-day
trial**, so this is explicitly an evaluation, not a settled choice: the service will
be reassessed when the trial ends, and if it isn't kept, another static-analysis
option (or none) will be picked then.

## Decision

Remove all SonarQube wiring from the project:

- Delete the `org.sonarqube` plugin and its version from the version catalog
  ([`gradle/libs.versions.toml`](../../gradle/libs.versions.toml)).
- Delete the root `build.gradle.kts` — it existed **only** to apply and configure
  the Sonar plugin, so with Sonar gone the root project configures nothing and the
  script is unnecessary (a root build script is optional in a multi-module Gradle
  build; `settings.gradle.kts` defines the modules).
- Remove the `SonarQube scan` step and the Sonar-only `fetch-depth: 0` checkout
  from the CI `verify` job ([`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)).
- Drop the `sonar`/`fast` targets and all `SONAR_*` handling from the local CI
  helper ([`scripts/local/ci.sh`](../../scripts/local/ci.sh)); it now runs
  ktlint → test.
- Remove the `@SuppressWarnings("java:S4502")` Sonar rule suppression in
  `SecurityConfig` (the explanatory comment for the deliberate CSRF-disable stays).

**JaCoCo is kept.** Coverage still matters and Qodana can consume the JaCoCo XML
reports the modules already emit, so the coverage wiring (convention plugin +
per-module config) is unchanged — only its Sonar-specific comments were retargeted.

## Consequences

- **Easier:** no root build script, no `SONAR_TOKEN` secret, no config-cache
  exception, and no Sonar rule IDs leaking into application code.
- **Easier:** CI has one fewer external dependency (the SonarQube Cloud GitHub app
  and token).
- **Cost:** until Qodana is wired up (tracked separately, not in this change), CI
  performs no static-analysis / code-quality gate beyond ktlint and the test suite.
- **Open decision:** Qodana is on a **60-day trial** with no free tier. A
  keep / replace / drop decision is due when the trial ends; a follow-up ADR should
  record whichever way it goes.
- The coverage exclusions that lived in the Sonar config (`Main.kt`,
  `MangaDexDto.kt`, `Application.java`) are dropped with it; equivalent scoping, if
  wanted, is reconfigured in Qodana.

## Alternatives considered

- **Keep SonarQube alongside Qodana** — rejected: the two overlap in purpose, and
  running both doubles the config surface and CI cost for no added signal.
- **Remove JaCoCo too** — rejected: coverage is still wanted, and Qodana can read
  JaCoCo XML, so there is no reason to lose coverage reporting.
