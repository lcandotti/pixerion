package io.modernia.pixerion.interop

import io.modernia.pixerion.domain.Book
import io.modernia.pixerion.domain.BookId
import io.modernia.pixerion.domain.BookRef
import io.modernia.pixerion.domain.SourceRef

/**
 * Java-friendly projections of domain identities, part of the interop seam (ADR-0007).
 *
 * Two domain shapes are awkward to read from Java:
 *  - [BookId] is a Kotlin `@JvmInline value class`, so [Book]'s `id` getter is
 *    **name-mangled** (`getId-<hash>()`) and not callable from Java at all;
 *  - [BookRef] is a sealed hierarchy that is clumsy to branch on from Java.
 *
 * These helpers flatten both onto plain strings so the Java front-end never has to
 * touch Kotlin-only types.
 */
object Refs {
    /** The portable [BookId] of [book] as a plain string (reaches the mangled getter from Kotlin). */
    @JvmStatic
    fun idOf(book: Book): String = book.id.value

    /**
     * Renders a [BookRef] as a portable handle string:
     * `"scheme:value"` for a [SourceRef], the raw identifier for a [BookId].
     */
    @JvmStatic
    fun render(ref: BookRef): String =
        when (ref) {
            is SourceRef -> "${ref.scheme}:${ref.value}"
            is BookId -> ref.value
        }
}
