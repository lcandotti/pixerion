# Troubleshooting

Known problems, as **symptom → cause → fix**. If you hit something that isn't
here and figure it out, add it.

- [Build and tests](#build-and-tests)
- [Native image](#native-image)
- [Running the server](#running-the-server)
- [Docker](#docker)
- [Runtime behaviour](#runtime-behaviour)

## Build and tests

| Symptom | Cause | Fix |
|---|---|---|
| `JAVA_HOME is not set and no 'java' command could be found` | The wrapper needs *a* JDK to start, even though Gradle provisions toolchains afterwards | Install JDK 25, or point at one: `export JAVA_HOME=~/.jdks/<jdk-25>` (IntelliJ-downloaded JDKs live there) |
| `Could not find org.springframework.boot:spring-boot-devtools:.` (during `build` / `bootJar`) | `developmentOnly(libs.springBootDevTools)` has no version: the Boot BOM is applied to `implementation` only, and `developmentOnly` doesn't inherit it | Add `developmentOnly(platform(SpringBootPlugin.BOM_COORDINATES))` in `pixerion-server/build.gradle.kts`. **Open on `main` as of 2026-10.** |
| `TokenControllerTest > valid_credentials_yield_a_token() FAILED`: `expected:<900> but was:<300>` | The test asserts a 15-minute TTL, but the test properties set `pixerion.security.jwt.ttl=5m` | Align one with the other. **Open as of 2026-10.** |
| `./gradlew test` runs `npm install` / `ng build` | `:pixerion-webapp:test` → `processResources` → `npmBuild` | Expected. Scope runs (`./gradlew :pixerion-core:test`) to skip it. |
| `Configuration cache entry discarded …` warning on native or `forked*` tasks | Those plugins aren't configuration-cache compatible. The tasks are marked as such. | Harmless, ignore it |
| `ktlintCheck` fails in CI | Formatting | `./gradlew ktlintFormat`, then commit |
| JaCoCo "Unsupported class file major version" | JaCoCo too old for JDK 25 bytecode | Keep `toolVersion = "0.8.13"` or newer in **all three** places (see [Conventions](conventions.md#dependencies)) |

## Native image

| Symptom | Cause | Fix |
|---|---|---|
| `A problem occurred starting process … native-image` | The Foojay auto-provisioned GraalVM has a 0-byte `bin/native-image` symlink | Install a real GraalVM (`sdk install java 25.0.2-graalce`) and `export GRAALVM_HOME=…`. With it set, toolchain detection is turned off. |
| Native binary fails at runtime with a reflection/`ClassNotFound` error | A command class or the registry started using reflection | Keep commands plain. Let `picocli-codegen` and the metadata repository generate the config. Don't hand-write `reflect-config.json`. |

## Running the server

| Symptom | Cause | Fix |
|---|---|---|
| `Connection to localhost:4433 refused` | Postgres isn't running | `docker compose up -d postgres` |
| Older docs mention `localhost:5432` and a `docker run … -p 5432:5432` workaround | Outdated: compose now publishes `4433:5432` and the app defaults to `4433` | Use `docker compose up -d postgres` |
| Every `hasRole` rule refuses even with a valid ADMIN token (403) | The token's `roles` claim and `jwtAuthenticationConverter` disagree (claim name or `SCOPE_` prefix) | Both sides must use `SecurityConfig.ROLES_CLAIM` with an empty prefix. Test with the "Actuator discovery with an ADMIN token" request in `api.http`. |
| A 400 comes back as an empty 401, or a 404 as a 403 | The ERROR dispatch isn't permitted, so `denyAll()` re-denies `/error` | Keep `dispatcherTypeMatchers(ERROR, FORWARD).permitAll()` as the first rule |
| A new route returns 401/403 instead of 404 | Paths outside `/api/**` fall through to `denyAll()` | Add an explicit `requestMatchers` rule |
| Catalog endpoints return 500 | `searchByTitle` and the `CatalogExceptionHandler` methods are still stubs | Expected while the server is being built ([Server → Current state](architecture/server.md#current-state)) |
| Hibernate fails at boot mapping `User.roles` as a basic column | A JPA annotation was put on a getter while `@Id` is on a field | Annotate fields only |
| `api.http` negative tests for `/v3/api-docs` and `/scalar` fail (they get 200) | `SecurityConfig` now permits those paths, so the requests are stale | Update the two requests in `api.http` |

## Docker

| Symptom | Cause | Fix |
|---|---|---|
| `docker compose up --build` fails: `Configuring project ':pixerion-webapp' without an existing directory is not allowed` | The Dockerfile doesn't `COPY pixerion-webapp`, and Gradle 9 rejects a missing project dir | Add `COPY pixerion-webapp ./pixerion-webapp` before the build step (the image will then download Node and run the npm build) |
| `env file … .env not found` | Compose `server` uses `env_file: .env`, which isn't committed | Create `.env` (see [Operations → The .env file](operations.md#the-env-file-docker-compose)) |
| Container is up but unhealthy / can't reach the DB | The app default `localhost:4433` means *the container itself* | Set `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/pixerion` in `.env` |
| `bootRun` fails with "port 8080 in use" | The compose `server` is running | `docker compose stop server` |

## Runtime behaviour

| Symptom | Cause | Fix |
|---|---|---|
| Downloads are slow (about 5 pages/s at most) | By design: a global 5 req/s limiter protects against MangaDex bans | Don't raise it without checking MangaDex's current limits. More `--workers` won't help. |
| HTTP 429s in a long-running process | Adapters are being rebuilt per call, so each one has a fresh, unpaced limiter | Reuse one adapter per scheme (see [Server → Catalog API](architecture/server.md#catalog-api)) |
| `bundle` reports no chapters | The book dir isn't the one containing `chapters/`, or the layout changed | Point it at `<root>/<source>/<book>` |
| Only one version of a chapter is downloaded | By design: one upload per chapter label (first wins) | — |
| Only English chapters are downloaded | `LANGUAGE = "en"` is hard-coded in `MangaDexCatalog` | Known limitation |
| CLI exits `1` with no error | A clean "no result" (empty search, `find` miss) | Expected. `2` means failure. |

## Related

- [Getting started](getting-started.md)
- [Operations](operations.md)
- [Back to the hub](index.md)
