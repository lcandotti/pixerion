package io.modernia.pixerion.server.catalog;

import io.modernia.pixerion.source.CatalogRegistry;

/**
 * Raised when a request names a source no adapter owns.
 *
 * <p>Deliberately <em>not</em> a {@code CatalogException}: that type means "a real source
 * failed", whereas this means "you asked for a source that does not exist" — a client
 * mistake, and so a 400 rather than a 502. The CLI draws the same line, reporting an
 * unknown {@code --source} as a usage error (exit 2) instead of a source failure.
 *
 * <p>The message lists {@link CatalogRegistry#getKnown()} so the hint can never drift from
 * what is actually resolvable — adding an adapter updates this text for free.
 */
public class UnknownSourceException extends RuntimeException {

    private final String source;

    public UnknownSourceException(String source) {
        super("Unknown source \"" + source + "\" — known: " + String.join(", ", CatalogRegistry.getKnown()));
        this.source = source;
    }

    public String getSource() {
        return source;
    }
}
