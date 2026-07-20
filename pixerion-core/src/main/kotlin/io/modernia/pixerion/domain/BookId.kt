package io.modernia.pixerion.domain

/**
 * The catalog's own portable identity for a book.
 *
 * Unlike a [SourceRef], a [BookId] is **cross-source**: it identifies a work
 * independently of which source it was fetched from, so the same logical book
 * keeps one [BookId] even if it appears in several sources. This identity is
 * assigned and maintained by the catalog (or its matching layer) — it only
 * exists because something decides when two source entries are the same work.
 *
 * @property value the catalog-assigned identifier, treated as opaque by callers.
 */
@JvmInline
value class BookId(
    val value: String,
) : BookRef
