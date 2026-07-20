package io.modernia.pixerion.domain

/**
 * A stable handle to a book within a [Catalog].
 *
 * The two variants address books at different scopes:
 *  - [BookId] — the catalog's own portable identity, stable across sources;
 *  - [SourceRef] — a handle scoped to the single source that issued it.
 */
sealed interface BookRef
