package io.modernia.pixerion.server.catalog.controllers;

import io.modernia.pixerion.domain.Book;
import io.modernia.pixerion.interop.Refs;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The wire shape of a book. A deliberate copy of {@link Book} rather than the domain type
 * itself: serializing the domain would publish {@code core}'s internals as the API's
 * contract, and every later change to {@link Book} would silently become a breaking change
 * for the frontend.
 *
 * @param id       the portable, cross-source identity.
 * @param ref      the source-scoped handle, rendered as {@code "scheme:value"}.
 * @param title    the book's title.
 * @param synopsis the book's synopsis.
 */
@Schema(description = "A book as returned by a catalog source")
public record BookResponse(
        @Schema(description = "Portable, cross-source identity", example = "801513ba-a712-498c-8f57-cae55b38cc92")
        String id,

        @Schema(description = "Source-scoped handle, as scheme:value", example = "mangadex:801513ba-a712-498c-8f57-cae55b38cc92")
        String ref,

        @Schema(description = "Title", example = "Berserk")
        String title,

        @Schema(description = "Synopsis")
        String synopsis
) {

    /**
     * Projects a domain {@link Book} onto the wire shape.
     *
     * <p><b>Read this before writing the body.</b> Two of these four fields cannot be read
     * from Java the obvious way, which is the whole reason {@link Refs} exists (ADR-0007):
     *
     * <ul>
     *   <li>{@code book.getId()} <b>does not compile</b>. {@code BookId} is a Kotlin
     *       {@code @JvmInline value class}, so the getter is name-mangled to something like
     *       {@code getId-XYZ()} and is unreachable from Java. Use {@link Refs#idOf(Book)}.</li>
     *   <li>{@code book.getRef()} does compile, but hands back a sealed {@code BookRef} that
     *       is clumsy to branch on here. Use {@link Refs#render(io.modernia.pixerion.domain.BookRef)}
     *       to flatten it to {@code "scheme:value"}.</li>
     * </ul>
     *
     * <p>{@code getTitle()} and {@code getSynopsis()} are ordinary and need no help.
     */
    public static BookResponse from(Book book) {
        // TODO: new BookResponse(Refs.idOf(book), Refs.render(book.getRef()), book.getTitle(), book.getSynopsis())
        throw new UnsupportedOperationException("TODO: implement BookResponse.from");
    }
}
