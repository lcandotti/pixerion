# 0001 — HTTP and JSON stack for source adapters

- **Status:** Accepted
- **Date:** 2026-06-24

## Context

Pixerion's `Catalog` port is implemented by *source adapters* that talk to
remote catalogs over HTTP and parse their JSON responses. The MangaDex adapter
(`shared/.../mangadex`) is the first of these; more (e.g. Google Books) are
expected, so the transport and serialization choices made here will likely
become the default for every adapter.

Forces at play:

- **GraalVM native-image is treated as a real future constraint**, not a
  theoretical one. Library picks should already favour reflection-free,
  native-image-friendly options so we are not forced into a painful swap later.
- The team's background is the **mature JVM ecosystem**; familiarity and
  battle-tested operational behaviour are valued over newer Kotlin-first
  alternatives.
- The `Catalog` contract is `suspend`-based, so the transport must compose with
  Kotlin coroutines without dragging in a competing concurrency model.
- Adapters must translate transport- and parsing-level failures into domain
  failures (`CatalogException`) rather than leaking vendor types.

## Decision

Source adapters use:

1. **OkHttp** (`com.squareup.okhttp3:okhttp`) as the HTTP client. Adapters bridge
   OkHttp's async `Call.enqueue` callbacks onto coroutines with
   `suspendCancellableCoroutine`, keeping the public surface `suspend` and
   cancellable.
2. **kotlinx.serialization** (`kotlinx-serialization-json`, with the Kotlin
   compiler plugin) for JSON decoding. DTOs are `@Serializable`, `internal` to
   each adapter package, and never escape the adapter boundary.

OkHttp and serialization concerns are confined to a transport class
(`MangaDexClient`); the `Catalog` implementation only maps DTOs to domain types.

## Consequences

- **Easier:** A reflection-free JSON path and a widely-deployed HTTP client keep
  the door open for GraalVM native-image. The stack is familiar and its
  operational behaviour (connection pooling, timeouts, interceptors) is
  well understood.
- **Easier:** DTOs and transport are an implementation detail per adapter, so the
  domain stays clean and each adapter can evolve its wire mapping independently.
- **Harder / cost:** OkHttp is callback/blocking-native, so every adapter pays a
  small `suspendCancellableCoroutine` bridge rather than getting coroutine
  support for free. This bridge is established once and copied.
- **Constraint:** the kotlinx.serialization compiler plugin must be applied in
  any module that declares `@Serializable` DTOs.

## Alternatives considered

- **Ktor Client** (with the OkHttp engine) — idiomatic coroutine-first API, but
  adds a layer over OkHttp we do not need and leans Kotlin-first where the team
  prefers the underlying JVM incumbent. Rejected in favour of plain OkHttp.
- **Jackson / Gson / Moshi** for JSON — mature, but reflection- or annotation-
  processor-based, which works against the GraalVM goal and adds native-image
  configuration burden. kotlinx.serialization's compile-time codegen avoids that.
- **`java.net.http.HttpClient` (JDK)** — no third-party dependency, but a weaker
  feature set (interceptors, connection management) and less of the team's
  operational familiarity than OkHttp.
