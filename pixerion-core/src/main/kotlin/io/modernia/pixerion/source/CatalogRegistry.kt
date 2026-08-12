package io.modernia.pixerion.source

import io.modernia.pixerion.domain.Catalog
import io.modernia.pixerion.source.mangadex.MangaDexCatalog

/**
 * The catalog sources shipped with pixerion, keyed by the [SourceRef][io.modernia.pixerion.domain.SourceRef]
 * scheme each adapter owns.
 *
 * This is the single registration point: every front-end (the CLI, the web
 * backend) resolves `--source`-style names through here rather than keeping its
 * own table, so **adding an adapter means adding one entry to [registry]** and
 * nothing else.
 *
 * The table is fixed at class-initialization time — there is no runtime
 * registration, no reflection, and no mutable state — which keeps it safe to
 * read from any thread and cheap for the CLI's GraalVM native image. Entries are
 * factories, so an adapter (and the HTTP client it owns) is only constructed for
 * a source that is actually queried.
 *
 * This package sits above [domain][io.modernia.pixerion.domain] deliberately:
 * the domain defines the [Catalog] port and must not know its implementations.
 */
object CatalogRegistry {
    private val registry: Map<String, () -> Catalog> =
        mapOf(
            MangaDexCatalog.SCHEME to ::MangaDexCatalog,
        )

    /**
     * The registered scheme names, in registration order.
     *
     * Front-ends render this in their "unknown source" diagnostics, so the hint
     * can never drift from what is actually resolvable.
     */
    @JvmStatic
    val known: Set<String> get() = registry.keys

    /**
     * Returns a new [Catalog] for [source], or `null` if no adapter owns that
     * scheme.
     *
     * `null` is a plain "no such source" — a caller-facing usage error the
     * front-end reports itself. Nothing is printed here: this is library code.
     */
    @JvmStatic
    operator fun get(source: String): Catalog? = registry[source]?.invoke()
}
