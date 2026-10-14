# Operations

How to configure, run, and check the web backend, and where the CLI keeps its data.
There is no production environment yet. The only defined runtimes are a developer
machine and Docker Compose.

## Environments

| Environment | How | Database | Notes |
|---|---|---|---|
| Local dev | `docker compose up -d postgres` + `./gradlew :pixerion-server:bootRun` | compose Postgres on host `:4433` | Defaults work with no configuration |
| Local packaged | `docker compose up --build` | compose Postgres on the internal network (`postgres:5432`) | Needs a `.env` file (see below). Image build currently broken ([Troubleshooting](troubleshooting.md#docker)). |
| Tests | `./gradlew :pixerion-server:test` | H2 in-memory, `MODE=PostgreSQL` | No Docker needed |
| Production | — | — | **TODO (owner to confirm):** none defined |

## Configuration

Spring reads [`application.properties`](../pixerion-server/src/main/resources/application.properties).
Every value can be overridden by an environment variable.

| Property | Env var | Default | Notes |
|---|---|---|---|
| `pixerion.security.jwt.secret` | `APP_JWT_SECRET` | an insecure dev value | HS256 key, **at least 32 characters** (validated at startup). Must be set to a real secret outside local dev. |
| `pixerion.security.jwt.ttl` | `APP_JWT_TTL` | `5m` | Token lifetime. Tokens can't be revoked, so keep it short. |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:4433/pixerion` | Built from `pixerion.datasource.host` / `.port` |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | `pixerion` | |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | `pixerion` | |
| `pixerion.datasource.host` / `.port` | — | `localhost` / `4433` | Matches the compose `ports: "4433:5432"` mapping |
| `management.endpoints.web.exposure.include` | — | `health` | Anything else exposed becomes ADMIN-only |

Fixed settings that shouldn't be changed casually: `ddl-auto=validate`,
`open-in-view=false`, `spring.flyway.enabled=true` (see [Server → Persistence](architecture/server.md#persistence)).

### The `.env` file (Docker Compose)

The compose `server` service loads `env_file: .env`. That file is git-ignored and
**no template is committed**, so `docker compose up` fails until you create one.
Inside the compose network, Postgres is `postgres:5432`, not `localhost:4433`.
A working minimum:

```dotenv
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/pixerion
SPRING_DATASOURCE_USERNAME=pixerion
SPRING_DATASOURCE_PASSWORD=pixerion
APP_JWT_SECRET=<at least 32 random characters, e.g. from: openssl rand -hex 32>
```

> **TODO (owner to confirm):** commit a `.env.example` with these keys so the
> packaged setup works from a clean clone.

## Secrets

| Secret | Where it lives |
|---|---|
| JWT signing key | `APP_JWT_SECRET` env var (`.env` locally). Changing it invalidates every issued token. |
| DB credentials | `SPRING_DATASOURCE_*` env vars. The compose defaults are dev-only. |
| `QODANA_TOKEN_802218222` | GitHub Actions secret (CI only) |
| Local Qodana | `QODANA_TOKEN` env var: the **project** token |
| GitHub Packages | `gpr.user` / `gpr.key` Gradle properties, or `GITHUB_ACTOR` / `GITHUB_TOKEN` |
| HTTP client secrets | `pixerion-server/http/http-client.private.env.json` (git-ignored) |

Never put secrets in `qodana.yaml`: its contents are included in the Qodana report.

## Health and observability

- **Health:** `GET /actuator/health`, public, returns `{"status":"UP"}`. Compose checks
  it every 30s (40s start period) with `curl -f`. Postgres has a `pg_isready`
  check, and the server waits for it (`depends_on: service_healthy`).
- **Logs:** Spring Boot default console logging. No structured logging, metrics, or tracing is configured.
- **Actuator:** only `health` is exposed. Exposing more makes it ADMIN-only automatically.

## Data on disk

| What | Where | Lifecycle |
|---|---|---|
| CLI downloads and `.cbz` files | `~/.pixerion/<source>/<book>/{chapters,cbz}/` | Never cleaned by Pixerion. Delete by hand. |
| Server downloads (container) | volume `pixerion-downloads` → `/home/app/.pixerion` | Persisted across restarts |
| Postgres data | volume `pixerion-pgdata` | `docker compose down -v` wipes it (and the seeded admin is recreated on next start) |

## Runbooks

**Reset the local database**

```sh
docker compose down -v            # drops the pixerion-pgdata volume
docker compose up -d postgres     # Flyway re-runs V1..V3 on next server start
```

**Find out why the server won't start**

1. `Connection to localhost:4433 refused` → Postgres isn't running: `docker compose up -d postgres`.
2. `Schema-validation: missing table` → Flyway didn't run, or an entity drifted from the migrations. Compare against `db/migration/`.
3. A binding/validation error on `pixerion.security.jwt.secret` → `APP_JWT_SECRET` is shorter than 32 characters.
4. Port 8080 in use → stop the compose `server` service (`docker compose stop server`).

More in [Troubleshooting](troubleshooting.md).

## Related

- [Server](architecture/server.md)
- [Release & artifacts](workflows/release.md)
- [Back to the hub](index.md)
