package io.modernia.pixerion.server.catalog;

import io.modernia.pixerion.interop.BlockingCatalog;
import io.modernia.pixerion.mangadex.MangaDexCatalog;
import org.springframework.stereotype.Component;

/**
 * Maps a {@code source} name to a {@link BlockingCatalog} — the server's mirror of
 * the CLI's {@code catalogFor} registry. Register new adapters here in one place.
 */
@Component
public class CatalogProvider {

    /** @return the catalog for {@code source}, wrapped for blocking use, or {@code null} if unknown. */
    public BlockingCatalog catalogFor(String source) {
        if (MangaDexCatalog.SCHEME.equals(source)) {
            return new BlockingCatalog(new MangaDexCatalog());
        }
        return null;
    }

    /**
     * The canonical name of a registered {@code source}, or {@code null} if unknown. Returns the
     * registry's own constant — never the caller's (request-derived) string — so a source name
     * echoed back in a response body can't carry attacker-controlled content. Mirror new adapters
     * from {@link #catalogFor}.
     *
     * @return the vetted source name, or {@code null} if {@code source} isn't registered.
     */
    public String canonicalName(String source) {
        if (MangaDexCatalog.SCHEME.equals(source)) {
            return MangaDexCatalog.SCHEME;
        }
        return null;
    }
}
