package io.modernia.pixerion.server.catalog;

/** Raised when a request names a {@code source} that no adapter resolves; mapped to 400. */
class UnknownSourceException extends RuntimeException {
    UnknownSourceException(String source) {
        super("Unknown source \"" + source + "\"");
    }
}
