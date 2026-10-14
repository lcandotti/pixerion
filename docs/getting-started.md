# Getting started

From a fresh clone to a green build, a working CLI, and a running backend. Plan on
about 20 minutes, most of it Gradle and npm downloading dependencies the first time.

- [1. Prerequisites](#1-prerequisites)
- [2. Clone and build](#2-clone-and-build)
- [3. Run the CLI](#3-run-the-cli)
- [4. Run the web backend](#4-run-the-web-backend)
- [5. Run the frontend](#5-run-the-frontend)
- [6. Before your first PR](#6-before-your-first-pr)

## 1. Prerequisites

| Tool | Needed for | Notes |
|---|---|---|
| **JDK 25** | everything | Must be on `PATH` or in `JAVA_HOME` so `./gradlew` can start. Gradle then provisions matching toolchains itself (Foojay). An IDE-bundled JBR 25 works. |
| **Docker** | the web backend | Runs PostgreSQL. The CLI and the library need neither Docker nor a database. |
| GraalVM 25 | native CLI binary only | Optional. See [CLI → Native image](architecture/cli.md#native-image). |
| Node | nothing (optional) | The webapp build downloads a pinned Node (`nodejs` in [`libs.versions.toml`](../gradle/libs.versions.toml)). A local Node and the Angular CLI are only a convenience. |

No local Gradle install is needed. Always use the wrapper, `./gradlew`.

## 2. Clone and build

```sh
git clone https://github.com/lcandotti/pixerion.git
cd pixerion
bash scripts/local/ci.sh        # ktlintCheck, then all tests: the same as the CI `verify` job
```

Expected: `✅ Local CI check (all) completed.` The first run takes a while:
Gradle downloads its distribution and dependencies, and `:pixerion-webapp` runs
`npm install` + `ng build` (see the note below).

> **Note:** `./gradlew test` at the root also builds the Angular bundle, because
> `:pixerion-webapp:test` depends on its `processResources`, which runs
> `npmBuild`. Only `:pixerion-server:test` is guaranteed to stay npm-free. To
> iterate on Kotlin code quickly, scope your runs: `./gradlew :pixerion-core:test`.

> **Warning (state as of 2026-10):** on the current branch head, `./gradlew build`
> fails resolving `spring-boot-devtools` and one `:pixerion-server` test fails. See
> [Troubleshooting](troubleshooting.md#build-and-tests). Core and CLI are green;
> `./gradlew :pixerion-core:test :pixerion-cli:test` is a reliable first check.

## 3. Run the CLI

On the JVM, through Gradle (arguments go in `--args`):

```sh
./gradlew :pixerion-cli:run --args="search berserk"
./gradlew :pixerion-cli:run --args="find 801513ba-a712-498c-8f57-cae55b38cc92"
./gradlew :pixerion-cli:run --args="download -o /tmp/pixerion 801513ba-a712-498c-8f57-cae55b38cc92"
./gradlew :pixerion-cli:run --args="bundle /tmp/pixerion/mangadex/Berserk"
```

These hit the live MangaDex API. For a launcher you can call directly, build the
JVM distribution (`./gradlew :pixerion-cli:distZip`, then unzip
`pixerion-cli/build/distributions/pixerion-*.zip` and run `bin/pixerion`) or the
native binary (`./gradlew :pixerion-cli:nativeCompile`). The full command
reference is in the [README](../README.md#cli-usage).

## 4. Run the web backend

The backend needs PostgreSQL to boot: Hibernate validates the schema at startup,
and Flyway creates and seeds it on first run.

```sh
docker compose up -d postgres            # publishes Postgres on host port 4433
./gradlew :pixerion-server:bootRun       # http://localhost:8080
```

The `application.properties` defaults (`localhost:4433`, user/password
`pixerion`) match the compose service, so no configuration is needed.

Check it:

```sh
curl -s localhost:8080/actuator/health                 # {"status":"UP"}
curl -s -X POST localhost:8080/api/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"email":"admin@pixerion.local","password":"changeme"}'
```

The second call returns `{"accessToken": "...", "expiresIn": 300}`. Send it as
`Authorization: Bearer <token>` to anything under `/api/**`. The interactive API
console is at <http://localhost:8080/scalar>. Requests ready to run in IntelliJ live in
[`pixerion-server/http/`](../pixerion-server/http/README.md).

> **Note:** `admin@pixerion.local` / `changeme` is seeded by
> [`V3__seed_admin.sql`](../pixerion-server/src/main/resources/db/migration/V3__seed_admin.sql)
> for local use only. It is not a credential.

## 5. Run the frontend

```sh
./gradlew :pixerion-webapp:run           # ng serve on http://localhost:4200, proxies the API to :8080
```

The Angular app is a freshly generated scaffold today. See [Webapp](architecture/webapp.md).

## 6. Before your first PR

- Read the [contract invariants](../ARCHITECTURE.md#contract-invariants--do-not-break-these)
  and [pitfalls](../ARCHITECTURE.md#4-pitfalls--gotchas). They are short, and
  breaking one doesn't make the compiler complain.
- Skim [Conventions](conventions.md) and the [Development workflow](workflows/development.md).

## Related

- [Repository structure](structure.md)
- [Troubleshooting](troubleshooting.md)
- [`CONTRIBUTING.md`](../CONTRIBUTING.md): the detailed backend and frontend dev loops
- [Back to the hub](index.md)
