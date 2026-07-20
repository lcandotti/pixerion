# HTTP request set (JetBrains HTTP Client)

Ready-to-run requests for the `pixerion-server` REST API, for the built-in
**HTTP Client** in IntelliJ IDEA (Ultimate) or the headless `ijhttp` CLI.

| File | Requests |
|------|----------|
| [`auth.http`](auth.http) | health check, login (captures the JWT), `/auth/me`, + negative 401 cases |
| [`catalog.http`](catalog.http) | search (captures a book id), fetch by id, + negative 400/401 cases |
| [`downloads.http`](downloads.http) | start a download job (captures the job id), poll status, stream SSE progress, + negative 404/400/401 cases |
| [`http-client.env.json`](http-client.env.json) | the `dev` environment (base URL, credentials, default source/query) |

## Prerequisites

The server must be running with its PostgreSQL database. From the repo root:

```sh
docker compose up --build          # server + Postgres on :8080
# or: ./gradlew :pixerion-server:bootRun   (needs a local Postgres)
```

Wait until `GET http://localhost:8080/actuator/health` reports `{"status":"UP"}`.

## Running in IntelliJ

1. Open `auth.http`. Pick the **dev** environment from the dropdown at the
   top-right of the editor (or the run dialog).
2. Run requests with the green ▶ gutter icon, **in order**:
   - `auth.http`: **Health** → **Login** (stores the JWT in the `authToken`
     global) → **Who am I**.
   - `catalog.http`: **Search** (stores the first result's `bookId` global) →
     **Fetch**.
   - `downloads.http`: **Start** (stores the `jobId` global) → **Poll status**
     → **Stream SSE progress**.
3. Assertions show up in the **Services / Tests** tool window. The negative
   requests (`[negative] …`) are expected to return 404/401/400.

The login step captures the token via a response handler
(`client.global.set("authToken", …)`), so every protected request just sends
`Authorization: Bearer {{authToken}}` — no copy-pasting tokens.

## Running headless (ijhttp)

```sh
ijhttp --env-file pixerion-server/http/http-client.env.json --env dev \
       pixerion-server/http/auth.http pixerion-server/http/catalog.http \
       pixerion-server/http/downloads.http
```

It exits non-zero if any `client.test(...)` assertion fails.

## Environments & secrets

`http-client.env.json` holds **local, non-secret** defaults (`admin`/`admin`,
matching the seeded dev admin). For a real deployment, put overrides in a
`http-client.private.env.json` beside this file — same keys, and it takes
precedence over `http-client.env.json`. That filename is git-ignored so secrets
never get committed.

## Notes

- Downloads are a **background job** (ADR-0011): `POST /api/downloads` returns `202`
  with a job handle, then you poll `GET /api/downloads/{id}` or stream
  `GET /api/downloads/{id}/events` (SSE). All three require the **ADMIN** role, and a
  started job performs a real MangaDex download to disk under `~/.pixerion`.
  A missing book surfaces as the job's terminal `NOT_FOUND` status (not a
  synchronous 404); an unknown source is still a synchronous 400. Flagged in
  `downloads.http`.
- `/auth/me` returns `ROLE_`-prefixed roles (e.g. `ROLE_ADMIN`), whereas the JWT
  `roles` claim stores them un-prefixed — the assertions account for this.
