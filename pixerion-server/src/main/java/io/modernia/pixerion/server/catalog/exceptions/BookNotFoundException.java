package io.modernia.pixerion.server.catalog.exceptions;

/**
 * Raised when a reference resolves to no book.
 *
 * <p>This exists so the "absent" case travels as an exception rather than as a {@code null}
 * returned from a controller method (which Spring MVC would serialize as a 200 with an
 * empty body). It carries no failure meaning: the source answered correctly, and the
 * correct answer was "there is no such book".
 */
public class BookNotFoundException extends RuntimeException {

    public BookNotFoundException(String source, String id) {
        super("No book found for " + source + ":" + id);
    }
}
