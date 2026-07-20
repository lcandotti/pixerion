# 0013 — Embedded Angular SPA served by the server, API under `/api`

- **Status:** Accepted
- **Date:** 2026-07-16
- **Builds on:** [ADR-0007](0007-java-web-front-end-and-interop-facade.md),
  [ADR-0008](0008-authentication-and-user-management.md),
  [ADR-0011](0011-streaming-download-progress-sse.md)

## Context

The server API is complete enough to carry a UI: login/identity
([ADR-0008]), catalog search/find, and async download jobs with SSE progress
([ADR-0011]). A web frontend (Angular) is planned. Forces:

- **One deployable.** The project ships the backend as a single bootJar in a
  single container (docker-compose); the UI should not add a second artifact,
  web server, or deployment step.
- **Same-origin avoids CORS.** A UI served from another origin would require a
  CORS policy on the API and a second TLS/host story; served from the same
  origin, the browser needs none of it.
- **Route namespaces collide.** The API lived at the root (`/search`,
  `/books/{id}`, `/downloads`) — exactly the URLs an SPA router wants for its
  own pushState routes. A deep link like `/books/42` must be answered with
  `index.html` (so the client router renders it), yet the same URL was an API
  endpoint. The two surfaces need disjoint prefixes *before* any UI exists,
  because moving API paths later breaks deployed clients.
- **Frontend and backend build lifecycles differ.** npm/Angular builds are
  slow, need Node, and change on a different cadence; the JVM test suite must
  never wait on (or require) them.
- **Frontend iteration must not go through Gradle.** Rebuilding a jar per
  change is unusable as a dev loop; `ng serve`'s hot reload is the point of the
  Angular tooling.

## Decision

**Serve the Angular SPA from the server's classpath, moving the whole
catalog API under `/api` and packaging the frontend as its own Gradle module
(`pixerion-webapp`) whose jar the server embeds.**

1. **API prefix.** All `CatalogController` routes move under `/api`
   (`/api/search`, `/api/books/{id}`, `/api/downloads…`). `/auth`, `/actuator`,
   and the docs (`/v3/api-docs`, `/scalar`, ADR-0012) keep their conventional
   paths. Everything backend is thus enumerable by prefix; every other GET is
   the SPA's.
2. **`pixerion-webapp` module.** A Gradle module holding the Angular app and
   the Gradle↔npm bridge (`com.github.node-gradle.node`, pinned auto-downloaded
   Node): `npm run build` → `dist/…/browser` → packaged into a plain jar under
   `META-INF/resources/`, one of Spring Boot's classpath static-resource roots.
   The module is inert (all tasks skip) until the Angular app is scaffolded.
3. **Embedded, but only in runnable artifacts.** The server consumes the
   webapp jar through a dedicated `webapp` configuration wired into `bootJar`
   and `bootRun` only — never `implementation` — so `:pixerion-server:test`
   never triggers an npm build and the test classpath stays frontend-free.
4. **History-API fallback.** A `PathResourceResolver` subclass (`SpaConfig`)
   serves unknown, non-backend GET paths as `index.html`, so deep links and
   refreshes reach the Angular router. Backend prefixes are exempt: an unknown
   `/api/**` path stays a JSON-era 404, never a 200 with HTML.
5. **Security posture.** `/api/**` and `/auth/**` require a bearer token
   (login/health/docs exceptions unchanged); any other GET — the static shell
   and client routes — is public; anything else is denied. The shell is public
   by design: authorization lives in the API the shell calls, not in the HTML.
6. **Dev loop.** Frontend development runs `ng serve` with a proxy config
   (`pixerion-webapp/proxy.conf.json`) forwarding backend prefixes to
   `localhost:8080`. Gradle is only involved when producing the bootJar.

## Consequences

- **Easier:** one artifact serves UI + API (unchanged Dockerfile/compose); no
  CORS configuration anywhere; API growth is secure by default (`/api/**` is
  authenticated as a family); the SPA fallback rule is a one-liner per new
  backend prefix.
- **API paths changed** (`/search` → `/api/search`, …): a breaking change for
  any existing consumer, accepted now precisely because no UI or external
  consumer is deployed yet. The `.http` files, tests, and docs moved in the
  same change.
- **Harder:** the server's `assemble` now transitively runs an npm build once
  the Angular app exists (mitigated: never during tests; Gradle caches it);
  Node enters the toolchain as a pinned, auto-downloaded runtime.
- **SSE from the browser** still requires a fetch-based client — native
  `EventSource` cannot send the `Authorization` header ([ADR-0011]) — the
  embedding changes nothing there.
- Native `EventSource` aside, the JWT lives in the SPA's memory/storage; token
  refresh (absent today) becomes a UI-visible gap to address later.

## Alternatives considered

- **Separate frontend deployment (nginx/CDN) + CORS on the API.** The
  standard SPA topology at scale, and better when frontend/backend release
  independently. Rejected: two artifacts and a CORS+origin story for a
  self-hosted single-node tool whose stated goal is one bundle.
- **API at the root, SPA under `/app`.** Avoids moving API paths, but pins the
  UI to an ugly prefix forever and still needs fallback rules; the UI, not the
  API, is what humans link to. Rejected.
- **Server-side templates (Thymeleaf) instead of an SPA.** No npm in the
  build at all, but forfeits the reactive UI (live SSE progress bars) that
  motivates a frontend here, and the user's chosen stack is Angular. Rejected.
- **Frontend built inside `pixerion-server` (processResources hook).** One
  module fewer, but couples the server's every build/test to npm and muddies
  the "thin Java front-end" module. Rejected in favor of an isolated module the
  server merely depends on.
