package io.modernia.pixerion.server.catalog.dto;

/**
 * Wire representation of a book. Keeps domain types (the value-class {@code BookId},
 * the sealed {@code BookRef}) off the HTTP boundary — they are flattened to strings
 * via {@code io.modernia.pixerion.interop.Refs}.
 *
 * @param id       the portable, cross-source identity.
 * @param ref      a re-fetch handle as {@code "scheme:value"}, usable on {@code /books/{id}}.
 * @param title    the book's title.
 * @param synopsis the book's synopsis.
 */
public record BookResponse(String id, String ref, String title, String synopsis) {
}
