# 0012 — OpenAPI documentation with springdoc + Scalar

- **Status:** Accepted
- **Date:** 2026-07-13
- **Builds on:** [ADR-0007](0007-java-web-front-end-and-interop-facade.md),
  [ADR-0008](0008-authentication-and-user-management.md),
  [ADR-0011](0011-streaming-download-progress-sse.md)

## Context

The server API has grown into a real surface — login/identity ([ADR-0008]),
catalog search/find, and the async download-job endpoints with SSE progress
([ADR-0011]) — but had no machine-readable contract and no interactive console.
A frontend (or anyone with `curl`) had to reverse-engineer request/response
shapes from the controllers. Forces:

- The spec must not drift from the code. The controllers and their record DTOs
  *are* the contract; documentation maintained by hand next to them will rot.
- The server is Spring Boot **4** (Framework 7, Jackson 3) — this rules out the
  widely-documented springdoc-openapi **2.x** line, which targets Boot 3.
- Auth is stateless JWT Bearer ([ADR-0008]); an interactive console is only
  useful if it can attach the token from `POST /auth/login`.
- ADR-0008 deliberately kept the unauthenticated surface minimal (`/auth/login`,
  the health probe); documentation endpoints widen it.
- The build enforces strict dependency verification, so a new dependency tree
  must be verifiable (signatures or pinned checksums).

## Decision

**Generate the OpenAPI spec at runtime with springdoc-openapi 3.x and serve
Scalar as the documentation UI**, via the single starter
`org.springdoc:springdoc-openapi-starter-webmvc-scalar`.

1. **Runtime generation.** springdoc derives paths, parameters, and schemas from
   the Spring MVC annotations and record DTOs already in place; the spec is served
   at `/v3/api-docs` (JSON) and `/v3/api-docs.yaml`. Controllers carry only
   moderate-depth annotations: a `@Tag` per controller, an `@Operation` summary per
   endpoint, and `@ApiResponse` codes only where they deviate from the obvious.
   The SSE endpoint is documented in prose (its `state`/`completed`/`error` event
   union has no useful OpenAPI schema; [ADR-0011] stays the authoritative contract).
2. **Bearer-JWT in the spec.** An `OpenAPI` bean declares an HTTP bearer
   security scheme applied globally, so Scalar's auth field takes the token from
   `POST /auth/login`; the login endpoint opts out with an empty
   `@SecurityRequirements`.
3. **Public docs.** `/v3/api-docs/**` and `/scalar/**` are `permitAll` — a
   deliberate widening of [ADR-0008]'s minimal public surface. The spec describes
   the API but exposes no data; calling anything still requires a token.
   `springdoc.api-docs.enabled=false` / `scalar.enabled=false` are env-overridable
   kill switches if a deployment must hide them.
4. **Build wiring consequence.** The legacy `io.spring.dependency-management`
   plugin resolved every dependency's POM ancestry in detached configurations that
   Gradle's `--write-verification-metadata` flow cannot record, which broke strict
   verification the moment springdoc (whose parent chain imports the whole Spring
   BOM tree) was added. The server now applies the Boot BOM through Gradle's
   native `platform(SpringBootPlugin.BOM_COORDINATES)` instead — the
   Boot-recommended approach — which keeps all resolution visible to the
   verification writer.

## Consequences

- **Easier:** the contract can never drift — it is regenerated from the
  controllers on every boot; consumers get `/scalar` for exploration and
  `/v3/api-docs` for tooling/client generation; new endpoints are documented by
  writing them (plus one `@Operation` line).
- **Wider public surface:** two unauthenticated route families, recorded here and
  mitigated by the kill switches.
- **New dependency tree** under strict verification: swagger-core and Scalar
  verify by signature (new trusted keys); springdoc's own signing key is absent
  from keyservers, so its four artifacts are pinned by SHA-256 checksum with an
  `ignored-key` entry.
- **Young line:** springdoc 3.x is the first Boot-4-compatible release train; its
  bundled `scalar-webmvc` is third-party. If Scalar misbehaves, swapping the
  starter for `springdoc-openapi-starter-webmvc-ui` (Swagger UI) is a one-line
  catalog change — the generator underneath is identical.

## Alternatives considered

- **springdoc with Swagger UI** (`…-starter-webmvc-ui`). Same generator, the
  classic UI. Scalar chosen for the cleaner reading experience; switching later
  is a one-dependency change, so little is at stake.
- **Hand-maintained OpenAPI YAML.** No new dependency, full control — and
  guaranteed drift, because nothing fails when the code and the file disagree.
  Rejected.
- **Spring REST Docs.** Test-driven and accurate (snippets only exist if a test
  produced them), but yields static docs with no interactive console, at a high
  per-endpoint authoring cost. Rejected for an API whose consumers want to poke it.
- **springdoc-openapi 2.x.** The line most documentation refers to; targets
  Boot 3 and does not run on Boot 4. Not viable.
