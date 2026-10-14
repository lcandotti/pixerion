# Pixerion documentation

Pixerion queries book catalogs (today: [MangaDex](https://mangadex.org)) through
one provider-agnostic interface, downloads books to disk, and bundles them into
`.cbz` comic archives. It ships as a **native CLI**, a reusable Kotlin **library**
(`pixerion-core`), and a Java/Spring **web backend** with an Angular SPA that is
still being built.

This site is for engineers joining the project. It explains how the pieces fit,
*why* they are shaped the way they are, and how to make a change without breaking
an invariant somebody already paid for.

> **Note:** Two root documents are the living source of truth and are linked from
> here rather than copied: [`ARCHITECTURE.md`](../ARCHITECTURE.md) (contract
> invariants and pitfalls) and [`CONTRIBUTING.md`](../CONTRIBUTING.md) (dev loops
> and the add-a-source walkthrough). When this site and those two disagree, they
> win — and please fix the drift.

## Start here (day one)

1. [Getting started](getting-started.md): clone, build, run the CLI, and boot the backend.
2. [Architecture overview](architecture/overview.md): the four modules and the one rule that governs them.
3. [Glossary](domain/glossary.md): `Catalog`, `BookRef`, `SourceRef`, `DownloadEvent`, and so on.
4. [Conventions](conventions.md): what reviewers will check before anything else.
5. [`ARCHITECTURE.md` §2 and §4](../ARCHITECTURE.md#2-the-central-contract-catalog): the invariants and pitfalls. Read these before your first PR.

## Find your way

| I want to… | Go to |
|---|---|
| Run it locally | [Getting started](getting-started.md) |
| Understand the overall design | [Architecture overview](architecture/overview.md) |
| Change the library (`Catalog`, downloader, bundler) | [Core](architecture/core.md) |
| Add a new catalog source | [Core → Adding a source](architecture/core.md#adding-a-source) → [`CONTRIBUTING.md`](../CONTRIBUTING.md#adding-a-new-catalog) |
| Change a CLI command | [CLI](architecture/cli.md) |
| Work on the web API or auth | [Server](architecture/server.md) |
| Work on the frontend | [Webapp](architecture/webapp.md) |
| Know why something is the way it is | [Decisions](architecture/decisions.md) → [ADRs](adr/README.md) |
| Find a file | [Repository structure](structure.md) |
| Ship a change (branch → PR → CI) | [Development workflow](workflows/development.md) |
| Produce or publish an artifact | [Release & artifacts](workflows/release.md) |
| Configure or run the backend | [Operations](operations.md) |
| Fix something that's broken | [Troubleshooting](troubleshooting.md) |
| Look up a term | [Glossary](domain/glossary.md) |

## All pages

- [Getting started](getting-started.md)
- [Repository structure](structure.md)
- [Conventions](conventions.md)
- Architecture
  - [Overview](architecture/overview.md)
  - [Core library](architecture/core.md)
  - [CLI](architecture/cli.md)
  - [Server](architecture/server.md)
  - [Webapp](architecture/webapp.md)
  - [Decisions (ADR summary)](architecture/decisions.md)
  - [Design note: the download pipeline](design/download-pipeline.md)
- Workflows
  - [Development](workflows/development.md)
  - [Release & artifacts](workflows/release.md)
- Domain
  - [Glossary](domain/glossary.md)
- [Operations](operations.md)
- [Troubleshooting](troubleshooting.md)
- [Architecture Decision Records](adr/README.md)
- Root documents: [`README.md`](../README.md) · [`ARCHITECTURE.md`](../ARCHITECTURE.md) · [`CONTRIBUTING.md`](../CONTRIBUTING.md)
