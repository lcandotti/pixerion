# 0014 — A single catalog registry in `core`

- **Status:** Accepted
- **Date:** 2026-08-12

## Context

Resolving a source name (`--source mangadex`) to a `Catalog` was the CLI's job: a
`catalogFor(source)` `when` in `CatalogCommand.kt`, with the set of known schemes
also hard-coded into the unknown-source error message.

That worked while the CLI was the only front-end. It stops working now that
`pixerion-server` also consumes `core`: each front-end would carry its own copy of
the table, and adding an adapter would mean editing every one of them — the exact
duplication `core` exists to prevent. The hard-coded "known:" hint was already a
second copy of the same knowledge, free to drift from what actually resolves.

Three shapes were considered.

**A mutable registry** (`register(scheme, factory)` called at startup) is the
conventional answer, and it does not solve the problem: *someone* has to call
`register`, and that someone is each front-end at boot — the duplication moves
rather than disappears. As a global `object` it also adds process-wide mutable
state: no thread safety for the multi-threaded server, and test pollution across a
shared JVM with no reset. Keyed by `KClass` it is worse still, since the caller
must name the concrete adapter type (defeating the indirection) and instantiating
from a `KClass` needs reflection, which the CLI's GraalVM native image would have
to be told about.

**`ServiceLoader`** with `META-INF/services` gives genuine zero-touch discovery,
but it is designed for adapters that live *outside* the build. All of ours are in
`core`. It would trade a one-line edit for provider files, native-image service
configuration, and a discovery order nobody can see.

**A fixed table in `core`** requires no registration call at all, which is what
made the other two options leaky.

## Decision

Introduce `io.modernia.pixerion.source.CatalogRegistry`: an immutable
scheme→factory map, fixed at class-initialization time, that is the **single**
registration point for every front-end.

```kotlin
object CatalogRegistry {
    private val registry: Map<String, () -> Catalog> =
        mapOf(
            MangaDexCatalog.SCHEME to ::MangaDexCatalog,
        )

    @JvmStatic val known: Set<String> get() = registry.keys
    @JvmStatic operator fun get(source: String): Catalog? = registry[source]?.invoke()
}
```

Consequences of the shape:

- **No runtime registration, no reflection, no mutable state.** Thread-safe by
  construction, nothing for the native image to discover, no cross-test pollution.
- **Factories, not instances.** An adapter and the HTTP client it owns are built
  only for a source actually queried.
- **`known` is derived**, so the front-ends' "unknown source" hints cannot drift
  from what resolves.
- **`null` means "no such source"**, and nothing is printed — this is library
  code. Each front-end renders its own diagnostic (the CLI keeps its
  `✗ Unknown source “x” · known: …` block; the server can map it to a 400).
- **`@JvmStatic` + `String` keys** keep it directly callable from the Java server
  without widening the `interop` facade.

Adapters move from `io.modernia.pixerion.<source>` to
`io.modernia.pixerion.source.<source>`, so vendor names no longer sit beside
concepts (`domain`, `download`, `bundle`) at the package root, and the registry
gets a natural home as its package's index.

The registry deliberately does **not** live in `domain`. `domain` defines the
`Catalog` port; a registry there would make the port package import its own
implementations, inverting the module's dependency direction and creating a
`domain ⇄ source.mangadex` package cycle. Before this change every arrow in `core`
pointed inward to `domain`, and that invariant is worth keeping.

## Consequences

- Adding a source is one entry in one map. `CatalogCommand` and the future server
  wiring need no change.
- `catalogFor` is deleted from the CLI. `CatalogCommand.catalogs` — the injection
  seam tests use to substitute a fake — remains, now defaulting to
  `CatalogRegistry::get`.
- Package rename is a breaking change for consumers of the published
  `pixerion-core` artifact. Done now, while there are none.
- Per-source configuration (a mirror base URL, custom timeouts) has no place in
  the fixed table. If that becomes a requirement, the registry becomes an
  instantiable class built by each front-end's composition root, and this ADR is
  superseded. Tests that need a configured adapter construct it directly today
  (`MangaDexCatalog(baseUrl, httpClient)`), which is why nothing needs it yet.

## Alternatives considered

- **Mutable `register()` registry** — moves the duplication into each front-end's
  startup, and adds global mutable state for no gain. Rejected.
- **`KClass`-keyed registry** — caller must name the concrete type, needs
  reflection to instantiate, and is awkward from Java. Rejected.
- **`ServiceLoader`** — solves out-of-tree plugin discovery, a problem we do not
  have. Revisit only if third-party adapters on the classpath become a goal.
- **Leave it in the CLI** — acceptable at one front-end, not at two. Rejected.
