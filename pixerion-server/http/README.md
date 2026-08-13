# HTTP request set (JetBrains HTTP Client)

Ready-to-run requests for `pixerion-server`, for the built-in **HTTP Client** in
IntelliJ IDEA (Ultimate) or the headless [`ijhttp`](https://www.jetbrains.com/help/idea/http-client-cli.html) CLI.

| File | Contents |
|------|----------|
| [`api.http`](api.http) | every endpoint that exists today, plus negative cases pinning the `SecurityConfig` rules |
| [`http-client.env.json`](http-client.env.json) | the `dev` environment (just `baseUrl`) |

The module is a deliberate work-in-progress, so the set is small: `GET /`,
`/actuator/health`, `POST /api/auth/login` plus the authenticated requests that
follow it, and negative requests asserting that `/api/**` and `/actuator` are
refused without a token. Grow it as you add endpoints.

> **Open question the file pins:** `/v3/api-docs` and `/scalar` match no rule in
> `SecurityConfig` and so fall through to the terminal `denyAll()` — 401 without a
> token, 403 with one. The Scalar console is therefore unusable, since a browser
> cannot attach a bearer header. Two requests assert that 401 today; the comment
> above them has the one-line fix if you decide the docs should be public.

## Prerequisites

The server needs a reachable PostgreSQL — `spring-boot-starter-data-jpa` is on
the classpath with `ddl-auto=validate`, so Hibernate opens a connection during
startup and the app **fails to boot** without one. Flyway creates and seeds the
schema on first start, so an empty database is fine; no manual DDL is needed.

```sh
docker run -d --rm --name pixerion-pg -p 5432:5432 \
  -e POSTGRES_DB=pixerion -e POSTGRES_USER=pixerion -e POSTGRES_PASSWORD=pixerion \
  postgres:17
./gradlew :pixerion-server:bootRun
```

> The compose `postgres` service does **not** publish a host port, so
> `docker compose up postgres` + `bootRun` cannot connect — hence the plain
> `docker run` above. See the note in [`CONTRIBUTING.md`](../../CONTRIBUTING.md).

Wait until `GET http://localhost:8080/actuator/health` reports `{"status":"UP"}`
(the first request in `api.http` does exactly that).

## Running in IntelliJ

1. Open `api.http` and pick the **dev** environment from the dropdown at the
   top-right of the editor.
2. Run a single request with the green ▶ in the gutter, or the whole file with
   the ▶▶ at the top. The `http@api` run configuration does the latter.
3. Assertion results land in the **Services** tool window. Requests prefixed
   `[negative]` are *expected* to return 401/404 — they pin the security rules,
   so a green run means authorization behaves as intended.

## Running headless

```sh
ijhttp --env-file pixerion-server/http/http-client.env.json --env dev \
       pixerion-server/http/api.http
```

Exits non-zero if any `client.test(...)` assertion fails.

## Authentication

`POST /api/auth/login` exchanges credentials for a signed JWT. In `api.http` that
request stashes the token in the `accessToken` client variable, so **run the file
from the top** (or run the login request once) before any authenticated request —
they all read that variable.

The seeded dev account is `admin@pixerion.local` / `changeme`, created by
`V3__seed_admin.sql` with both `ROLE_USER` and `ROLE_ADMIN`. It is a local
convenience, not a credential: a real deployment creates its own accounts and
deletes that row.

Two mappings have to agree for roles to work, and a mismatch is silent — the token
looks perfectly valid and every rule simply refuses:

- **outbound**, `UserService` turns `user_roles` rows into authorities, which
  `TokenController` writes into the token's `roles` claim;
- **inbound**, `SecurityConfig.jwtAuthenticationConverter()` reads that claim back.
  Note it clears the authority prefix — the default `JwtGrantedAuthoritiesConverter`
  reads `scope`/`scp` and prefixes `SCOPE_`, which would yield `SCOPE_ROLE_ADMIN`
  and make every `hasRole(...)` rule unreachable.

The "Actuator discovery with an ADMIN token" request exercises that whole chain end
to end; a 403 there means the two halves have drifted apart.

## Environments & secrets

`http-client.env.json` holds non-secret local defaults and is committed. For
anything sensitive, create `http-client.private.env.json` beside it — same keys,
it takes precedence, and it is already git-ignored.
