package io.modernia.pixerion.domain

/**
 * A book in the catalog, in domain terms.
 *
 * Carries identity at two scopes: [id] is the portable, cross-source identity,
 * while [ref] records the specific source this representation came from (and
 * serves as a direct re-fetch handle on that source). Either can be passed to
 * [Catalog.find]. Adapters map their source representation onto this type at
 * the boundary.
 *
 * @property id the portable, cross-source identity of the book.
 * @property ref the source-scoped handle for the representation that was fetched.
 * @property title the book's title.
 * @property synopsis the book's synopsis
 */
data class Book(
    val id: BookId,
    val ref: BookRef,
    val title: String,
    val synopsis: String,
)
