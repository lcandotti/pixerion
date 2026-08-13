# Architecture Decision Records

This directory records **architecturally significant decisions** for Pixerion —
the choices whose *rationale* and *rejected alternatives* would otherwise be lost
to time, commit messages, or someone's memory.

## What gets an ADR

Record a decision here when it has meaningful, lasting consequences and a real
alternative was weighed: a library or framework choice, a module split, a
boundary or contract decision, a protocol or storage format.

Skip ADRs for config tweaks, version bumps, formatting, and decisions with no
real alternative.

## Format

Each ADR is a file named `NNNN-kebab-title.md`, numbered sequentially, using a
lightweight [MADR](https://adr.github.io/madr/)-inspired template:

- **Status** — `Proposed` | `Accepted` | `Superseded by [ADR-NNNN](...)`
- **Context** — the forces at play: requirements, constraints, what prompted the decision.
- **Decision** — what we chose, stated plainly.
- **Consequences** — what becomes easier and what becomes harder as a result.
- **Alternatives considered** — the options we rejected, and why.

## Rules

- ADRs are **immutable once accepted**. To change a decision, write a *new* ADR
  and mark the old one `Superseded by [ADR-NNNN](...)`. Don't edit accepted ADRs
  in place (typo fixes aside).
- Cross-link related ADRs with `[ADR-NNNN](NNNN-...md)`.
- Add every new ADR to the index below.
- Gaps in the numbering are retired ADRs — never reuse a retired number; new ADRs
  continue from the highest number ever assigned (currently 0015).

## Index

| ADR | Title | Status |
|-----|-------|--------|
| [0001](0001-http-and-json-stack-for-source-adapters.md) | HTTP and JSON stack for source adapters | Accepted |
| [0002](0002-picocli-for-the-cli.md) | picocli for the CLI | Accepted |
| [0003](0003-streaming-download-contract.md) | Streaming download contract on Catalog | Superseded by [0010](0010-structured-download-event-stream.md) |
| [0004](0004-parallel-download-and-layout.md) | Parallel download and on-disk layout | Accepted |
| [0005](0005-library-namespace-and-publishing-for-shared.md) | Library namespace and publishing for the shared module | Accepted |
| [0006](0006-sonarqube-cloud-static-analysis.md) | SonarQube Cloud for static analysis | Superseded by [0009](0009-drop-sonarqube-cloud-static-analysis.md) |
| [0009](0009-drop-sonarqube-cloud-static-analysis.md) | Drop SonarQube Cloud static analysis | Accepted |
| [0010](0010-structured-download-event-stream.md) | Structured download event stream for progress reporting | Accepted |
| [0014](0014-single-catalog-registry-in-core.md) | A single catalog registry in `core` | Accepted |
| [0015](0015-stateless-self-issued-jwt-auth.md) | Stateless self-issued JWT authentication for `server` | Accepted |
