# 0015 — Stateless self-issued JWT authentication for `server`

- **Status:** Accepted
- **Date:** 2026-08-13

## Context

`pixerion-server` needs authentication and role-based authorization before it can
expose anything beyond the public health probe. Two properties shape the choice.

First, the deployment is **one service**. There is no fleet of backends to share a
session store between, no second party to federate with, and — for now — no user
base beyond whoever runs it. Machinery that pays off across many services is pure
cost here.

Second, the front-end is an **Angular SPA served from the same jar** as the API
(`webapp` is packaged into `META-INF/resources/`). Same origin, so a cookie-based
session would work; but the SPA also has to run against `ng serve` on `:4200`
proxying to `:8080` during development, where cookie semantics get fiddly.

The authorization side has its own question: roles could be per-user rows (a true
one-to-many, `roles.user_id`), or a shared lookup table joined through
`user_roles`. Role names are naturally canonical — there is one `ROLE_ADMIN`
concept, not one per administrator — and a `UNIQUE` name column under a one-to-many
would cap the system at a single admin.

## Decision

The module is **both authorization server and resource server**, issuing
self-contained JWTs signed with a symmetric HS256 secret.

- `POST /api/auth/login` is the only endpoint that authenticates a password. It
  delegates to an `AuthenticationManager` (a `ProviderManager` over
  `DaoAuthenticationProvider` + BCrypt), then mints a token via `NimbusJwtEncoder`.
- Every other route is a stateless resource server:
  `SessionCreationPolicy.STATELESS`, `oauth2ResourceServer().jwt()`, and a terminal
  `denyAll()` so unclassified paths are refused rather than exposed.
- Roles live in a **shared lookup table** — `users` ⟷ `user_roles` ⟷ `roles` —
  with names stored carrying the `ROLE_` prefix, and travel in the token's `roles`
  claim.

## Consequences

**Easier.** No session store, so horizontal scaling is free and the dev proxy setup
is trivial — the SPA holds a bearer token and nothing depends on cookie scope.
Authorization is a pure function of the token: no database read on the hot path.
Granting a role is one `user_roles` row, and role names stay canonical.

**Harder.** Tokens **cannot be revoked** before they expire. The only lever is
lifetime, hence a deliberately short `pixerion.security.jwt.ttl` (15m) — and
therefore a refresh-token flow, which does not exist yet, will be needed before the
SPA is pleasant to use.

The symmetric secret is a **shared signing and verification key**: anyone who can
read it can mint valid tokens. That is acceptable while one service holds both
halves, and is the thing that must change first if a second service ever needs to
verify these tokens.

Two authority mappings now have to stay in step — `UserService` writing authorities
outbound, `JwtAuthenticationConverter` reading the claim inbound — and a mismatch is
silent: the token verifies, and every rule refuses. The claim name is shared as
`SecurityConfig.ROLES_CLAIM` to remove one way to get this wrong, and an end-to-end
test mints a real token rather than using `SecurityMockMvcRequestPostProcessors.jwt()`,
which would inject authorities directly and pass even when the two halves disagree.

## Alternatives considered

**Server-side sessions** (`JSESSIONID` + Spring Session). Revocation becomes trivial
— delete the session — and there is no token-lifetime tuning. Rejected because it
introduces a session store as a stateful dependency for a single-instance app, and
because the `ng serve` proxy makes cookie handling more awkward than a bearer header.
Worth revisiting if instant revocation becomes a requirement.

**An external identity provider** (Keycloak, Auth0) with RS256 and JWKS. This is the
right answer at organizational scale: real revocation, key rotation, MFA, and social
login for free, with `server` reduced to a resource server validating against a
published JWKS. Rejected as disproportionate — it means operating another service (or
paying for one) to authenticate a handful of accounts, and it would put an
identity-provider dependency in front of a module explicitly being built by hand as a
learning exercise.

**Opaque tokens with introspection.** Revocable like sessions while keeping the
bearer-header shape. Rejected because introspection means a datastore lookup on every
request — the exact cost the stateless design avoids — with no benefit that sessions
would not deliver more simply.

**Per-user role rows** (`@OneToMany` with `roles.user_id`). Superficially simpler,
and it is what a naive reading of "roles belong to a user" suggests. Rejected because
role names are canonical: this model duplicates `'ROLE_ADMIN'` once per administrator
and cannot carry a `UNIQUE` constraint on the name — with one, exactly one user could
ever be an admin.

**`@ElementCollection` of an enum.** Lightest of all: a `user_roles(user_id, role)`
side table and no `Role` entity. Rejected because roles stop being first-class rows,
so they cannot be listed, described, or referenced by other tables later — a real
constraint once permissions attach to roles.
