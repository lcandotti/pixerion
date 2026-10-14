# Repository structure

An annotated map of the repository, so you know where a change belongs before you
open an editor. Generated directories (`build/`, `.gradle/`, `node_modules/`,
`dist/`) are omitted.

```
pixerion/
├── pixerion-core/                 # The library: all reusable logic (Kotlin, published)
│   └── src/main/kotlin/io/modernia/pixerion/
│       ├── domain/                #   The Catalog port + domain types. Imports no implementation.
│       ├── source/                #   CatalogRegistry: the ONE place adapters are registered
│       │   └── mangadex/          #   MangaDex adapter: Catalog (mapping) + Client (transport) + DTOs
│       ├── download/              #   Downloader, Layout (on-disk tree), DownloadProgress listener
│       ├── bundle/                #   Bundler: downloaded tree → one .cbz per chapter
│       └── interop/               #   BlockingCatalog + Refs: the only Java-facing seam
├── pixerion-cli/                  # picocli front-end (Kotlin, native image, not published)
│   └── src/main/kotlin/
│       ├── Main.kt                #   Default package on purpose → entrypoint class `MainKt`
│       ├── command/               #   One class per subcommand + CatalogCommand base + error handler
│       └── output/                #   Rendering: BookView, DownloadProgressView, Terminal
├── pixerion-server/               # Spring Boot MVC backend (Java, built by hand, not published)
│   ├── src/main/java/io/modernia/pixerion/server/
│   │   ├── auth/                  #   Login → JWT; users/roles JPA entities + repositories
│   │   ├── catalog/               #   Read-only catalog API over core (in progress)
│   │   └── config/                #   SecurityConfig, JwtProperties, OpenApiConfig
│   ├── src/main/resources/
│   │   ├── application.properties
│   │   └── db/migration/          #   Flyway migrations (V1 users, V2 roles, V3 dev seed)
│   ├── src/test/resources/application.properties   # SHADOWS the main one (H2, test secret)
│   ├── http/                      #   JetBrains HTTP Client request set (api.http)
│   └── Dockerfile                 #   Multi-stage image; build context is the repo ROOT
├── pixerion-webapp/               # Angular SPA + Gradle↔npm bridge (build.gradle.kts)
│   ├── src/                       #   Angular sources (fresh scaffold)
│   └── proxy.conf.json            #   ng serve → localhost:8080 for /api, /actuator, /v3, /scalar
├── buildSrc/                      # kotlin-jvm convention plugin (core only: Kotlin, ktlint, JaCoCo)
├── gradle/libs.versions.toml      # Version catalog: every dependency is declared here
├── docs/                          # This site, plus adr/ (decision records) and design/ (deep dives)
├── scripts/local/ci.sh            # Reproduce CI locally (lint | tests | qodana | all | full)
├── .github/workflows/ci.yml       # CI: verify → qodana → artifacts (on merge)
├── qodana.yaml                    # Static analysis profile, license rules, coverage staging
├── docker-compose.yml             # postgres (host :4433) + server (:8080)
├── .idea/runConfigurations/       # Shared IntelliJ run configs (cli_main, server_localhost, http_api, ci_localhost)
├── ARCHITECTURE.md                # Living map of invariants and pitfalls (keep in sync!)
├── CONTRIBUTING.md                # Dev loops, conventions, add-a-source walkthrough
└── CLAUDE.md                      # Guidance for Claude Code sessions in this repo
```

## Where does my change go?

| You are… | Put it in |
|---|---|
| Adding or changing anything reusable (a source, download logic, a file format) | `pixerion-core` |
| Adding a CLI flag or changing output formatting | `pixerion-cli` (`command/` or `output/`) |
| Exposing a `core` capability to Java | `pixerion-core/.../interop/`, never by calling `suspend` code from Java |
| Adding an HTTP endpoint | `pixerion-server/.../<feature>/controllers` + `services` |
| Changing the DB schema | A **new** `V<n>__*.sql` in `db/migration/`. Never edit an applied migration. |
| Adding a dependency | `gradle/libs.versions.toml`, then reference `libs.<alias>` |

> **Why:** `core` holds the logic and the front-ends stay thin, so the CLI and the
> server cannot drift into two implementations of the same behaviour. See
> [Architecture overview](architecture/overview.md).

## Package naming

- `core`: `io.modernia.pixerion.<area>`. It was once a module named `shared`; older ADRs still use that name ([ADR-0005](adr/0005-library-namespace-and-publishing-for-shared.md)).
- `cli`: root-level packages (`command`, `output`) and a default-package `Main.kt`, because the CLI is not published and needs no reverse-DNS namespace.
- `server`: `io.modernia.pixerion.server.<feature>.<layer>`, where the layers are `controllers`, `services`, `components`, `models`, and `exceptions`.

## Related

- [Conventions](conventions.md)
- [Architecture overview](architecture/overview.md)
- [Back to the hub](index.md)
