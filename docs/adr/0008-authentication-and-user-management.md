# 0008 — Authentication, user management, and persistence in the server

- **Status:** Accepted
- **Date:** 2026-06-29

## Context

The `server` module (ADR-0007) exposed the catalog over HTTP with no access
control. The next step is to **secure it** and introduce **user and group
management**, with persistence so users survive restarts. Future work (richer
user features, a UI) builds on this.

Forces:

- The author wants to learn the Spring stack and chose to **own** user management
  (rather than delegate to an external IdP like Keycloak) and to authenticate with
  **stateless JWTs** (rather than sessions or HTTP Basic).
- "Groups" must map onto something Spring Security enforces.
- The app was stateless; this is the first time it needs a database. A real
  schema-management story is wanted, not `ddl-auto` in production.
- `core` must stay an auth-agnostic catalog library — none of this belongs there.
- The container healthcheck (ADR-0007) probes `/actuator/health`; turning on
  security must not break it.
- Strict dependency verification (project-wide) applies to the new dependency tree.

## Decision

1. **Spring Security, self-owned users, stateless JWT.** Users and roles live in
   the app's own PostgreSQL database via Spring Data JPA. Login
   (`POST /auth/login`) verifies credentials with a `DaoAuthenticationProvider` +
   `BCryptPasswordEncoder` and returns a signed **JWT**; subsequent requests carry
   it as a bearer token, validated by Spring Security's OAuth2 **resource server**.
   No server-side session (`SessionCreationPolicy.STATELESS`); CSRF disabled as the
   API is token-based.

2. **JWTs are HS256, symmetric-keyed.** A single secret
   (`app.security.jwt.secret`, env-overridable, min 32 bytes) signs and verifies
   tokens via `NimbusJwtEncoder`/`NimbusJwtDecoder`. The signing algorithm is
   pinned explicitly to HS256 (the encoder otherwise defaults to RS256 and fails to
   select a key for a symmetric secret). Token TTL is configurable.

3. **Groups are roles → authorities.** An `AppUser` has many `Role`s; each maps to
   a Spring Security `ROLE_<name>` authority. The JWT carries a `roles` claim,
   converted back to authorities on each request. `@EnableMethodSecurity` plus
   `@PreAuthorize("hasRole('ADMIN')")` gates privileged operations (e.g.
   `POST /downloads`); search/find require any authenticated user.

4. **Flyway owns the schema; a runner seeds data.** A Flyway migration creates the
   `roles`/`app_users`/`user_roles` tables; Hibernate is set to `validate`. Baseline
   roles and an initial admin are seeded **at startup by an idempotent
   `ApplicationRunner`**, not in SQL, because the admin password must be hashed by
   the application's `PasswordEncoder`. Admin credentials come from `app.admin.*`
   (env-overridable; default `admin`/`admin` for local use only).

5. **Public surface stays minimal:** only `POST /auth/login` and
   `/actuator/health` are unauthenticated, so the Docker healthcheck keeps working.

6. **All of this lives in `server`.** `core` is untouched and remains an
   auth-agnostic catalog library.

7. **docker-compose gains a `postgres:17` service** with a named volume and a
   `pg_isready` healthcheck; `server` `depends_on` it (condition: healthy) and is
   wired via `SPRING_DATASOURCE_*` / `APP_*` env.

## Consequences

- **Easier:** the API is secured with a conventional, well-documented Spring
  Security setup; users/roles are managed in our own DB; stateless tokens scale
  horizontally with no shared session store.
- **Discipline:** the JWT secret and admin password are deployment secrets — the
  defaults are explicitly local-only and must be overridden. HS256 means the
  signing secret is also the verification secret (no public-key split).
- **New runtime dependency:** the server now requires PostgreSQL to start; tests
  avoid this by running the full context against in-memory H2 (Flyway disabled,
  Hibernate creating the schema) with a per-context random DB name for isolation.
- **Verification metadata** grew again for the security/JPA/Flyway/Postgres tree.
- **Boot 4 note:** Spring Framework 7 ships **Jackson 3** (`tools.jackson.*`) and
  reorganized some test-autoconfigure packages; tests use `RestClient` against a
  real random port to stay clear of moved symbols.

## Alternatives considered

- **Delegate to Keycloak (or another IdP).** Off-the-shelf user/group management
  and a login UI, but a heavier component, its own datastore, and an OIDC learning
  curve. Rejected for now in favor of owning a minimal store; the resource-server
  setup means swapping to an IdP-issued JWT later is a small change. Deferred.
- **Session + form login**, or **HTTP Basic.** Simpler to stand up, but sessions
  need a shared store to scale and Basic re-sends credentials on every call; JWT
  was the author's choice and fits a future SPA/mobile client. Rejected.
- **RSA/asymmetric JWTs.** Lets verifiers validate without the signing key (useful
  across services). Overkill for a single service; HS256 is simpler. Revisit if
  tokens must be verified by other parties.
- **Seed everything (incl. admin) in Flyway SQL.** Would require a hard-coded
  BCrypt hash in a migration — brittle and awkward to rotate. Seeding via a runner
  that uses the real `PasswordEncoder` is cleaner. Rejected.
- **`ddl-auto` to manage the schema.** Fine for the H2 tests, but not a real
  migration story for production. Flyway is the durable choice; `ddl-auto=validate`
  keeps entities and schema honest. Rejected for runtime.

## Clarifications

_These notes elaborate on the accepted decision; they do not change it._

### 2026-07-01 — Why disabling CSRF is safe here (and the SonarQube finding)

Decision item 1 disables CSRF (`http.csrf(AbstractHttpConfigurer::disable)` in
`SecurityConfig`). This is safe, not a shortcut: CSRF exploits the browser
**auto-attaching ambient credentials** — cookies, HTTP Basic, or a server session
(`JSESSIONID`) — to a forged cross-site request. This server has none of those:
it is **stateless** (`SessionCreationPolicy.STATELESS`, no `JSESSIONID` ever
issued) and authenticates **only** via a Bearer token in the `Authorization`
header, which browsers do **not** auto-attach cross-site. With no ambient
credential to forge, there is nothing for CSRF to protect, so disabling it is the
recommended configuration for a token-in-header stateless API.

SonarQube flags this line as **`java:S4502` ("CSRF protections should not be
disabled")**. It is a true false-positive for this design and is suppressed in
code with `@SuppressWarnings("java:S4502")` plus an explanatory comment on
`SecurityConfig#filterChain`, rather than silenced globally — if the app ever
adopts cookie/session auth, the suppression should be revisited. (The same pass
also removed a superfluous `throws Exception` from `filterChain`, `java:S1130`.)
