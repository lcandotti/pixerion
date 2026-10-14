# Decisions

Why Pixerion is shaped the way it is: a one-line summary of each recorded
decision, plus the decisions that live only in code comments. The full records
(context, alternatives, consequences) are the ADRs in [`docs/adr/`](../adr/README.md).
Read the relevant ADR before proposing to reverse one of these.

## Recorded decisions (ADRs)

| ADR | Decision | In one line |
|---|---|---|
| [0001](../adr/0001-http-and-json-stack-for-source-adapters.md) | OkHttp + kotlinx.serialization for adapters | Mature, native-image friendly, no reflection-based JSON |
| [0002](../adr/0002-picocli-for-the-cli.md) | picocli for the CLI | Build-time GraalVM metadata via `picocli-codegen` |
| [0003](../adr/0003-streaming-download-contract.md) | Streaming download on `Catalog` | *Superseded by 0010* |
| [0004](../adr/0004-parallel-download-and-layout.md) | Parallel downloader in core + on-disk layout | Orchestration in `core`, bounded worker pool, predictable tree |
| [0005](../adr/0005-library-namespace-and-publishing-for-shared.md) | `io.modernia.pixerion:pixerion-core` | The library is publishable. `shared` was renamed `core`. |
| [0006](../adr/0006-sonarqube-cloud-static-analysis.md) | SonarQube Cloud | *Superseded by 0009* |
| [0009](../adr/0009-drop-sonarqube-cloud-static-analysis.md) | Drop SonarQube, use Qodana | One analysis tool that also checks licenses and coverage |
| [0010](../adr/0010-structured-download-event-stream.md) | `Flow<DownloadEvent>` | Structure (manifest, chapters) travels with the pages, which enables determinate progress |
| [0014](../adr/0014-single-catalog-registry-in-core.md) | One `CatalogRegistry` in `core.source` | Adding a source is a single entry. Front-ends can't drift. |
| [0015](../adr/0015-stateless-self-issued-jwt-auth.md) | Stateless self-issued HS256 JWT | The server is its own issuer and resource server. No sessions, no external IdP. |

Numbers 0007, 0008, 0011, 0012 and 0013 are **retired**. Never reuse them.

> **TODO (owner to confirm):** code comments still cite ADR-0007 (Java interop
> facade / `:server` module), ADR-0012 (springdoc + Scalar) and ADR-0013 (webapp
> Gradle↔npm bridge). Either those decisions were dropped and the comments
> should be updated, or they deserve fresh ADRs (0016+). The rationale is currently
> in the code comments and [`ARCHITECTURE.md`](../../ARCHITECTURE.md) §4.1/§4.6.

## Decisions recorded only in code

These are significant, but they live in comments rather than ADRs. They're
collected here so nobody "fixes" them by accident.

| Decision | Where | Why |
|---|---|---|
| Absence (`null`/`[]`) vs failure (`CatalogException`) | `domain/Catalog.kt` | Callers must be able to tell "doesn't exist" from "couldn't ask". This is the core invariant. |
| Interceptor order: retry outside rate limit | `MangaDexClient.withDefaultPolicy` | Retries must re-enter the limiter, or they burst past the per-IP cap |
| Telemetry on a separate, unpaced client | `MangaDexClient` (`reportClient`) | Reports must never compete with image fetches for permits |
| Downloads keep one upload per chapter label | `MangaDexCatalog.download` | Duplicate scanlations would overwrite each other's files |
| `.cbz` entries `STORED`, written atomically | `Bundler` | Images are already compressed. Avoids partial archives. |
| Java consumes `core` only via `interop` | `BlockingCatalog`, `Refs` | Value classes and `suspend` functions can't be called from Java |
| SPA embedded via a dedicated Gradle configuration | `pixerion-server/build.gradle.kts` | Server tests must never run npm |
| Boot BOM via `platform()`, not the dependency-management plugin | `pixerion-server/build.gradle.kts` | Gradle-native, no legacy plugin |
| API absence is `200 []`, no 404 | `CatalogController` | One response shape. The frontend never branches on a status code to detect absence. |
| `502` for upstream failures | `CatalogExceptionHandler` | The upstream source is broken, not this server, so on-call should look there |
| Flyway owns the schema, and tests run it on H2 | `application.properties` (main and test) | Migrations are exercised, and drift fails startup |
| JWT TTL short (5 min), no revocation | `application.properties` | Self-contained tokens can only be limited by their lifetime |

## Writing a new ADR

Follow [`docs/adr/README.md`](../adr/README.md): MADR-lite, numbered from the
highest number ever used (next is **0016**), immutable once accepted. Add it to
the index there and to the table above.

## Related

- [Architecture overview](overview.md)
- [Conventions](../conventions.md)
- [Back to the hub](../index.md)
