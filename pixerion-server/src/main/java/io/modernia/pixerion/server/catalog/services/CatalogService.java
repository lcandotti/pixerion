package io.modernia.pixerion.server.catalog.services;

import io.modernia.pixerion.domain.Book;
import io.modernia.pixerion.domain.CatalogException;
import io.modernia.pixerion.domain.SourceRef;
import io.modernia.pixerion.interop.BlockingCatalog;
import io.modernia.pixerion.server.catalog.components.CatalogSources;
import io.modernia.pixerion.server.catalog.components.RegistryCatalogSources;
import io.modernia.pixerion.server.catalog.exceptions.UnknownSourceException;
import io.modernia.pixerion.source.CatalogRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server's read-only door onto {@code core}'s catalog sources.
 *
 * <p>This is the web analogue of the CLI's {@code CatalogCommand}: resolve a source name
 * through {@link CatalogRegistry}, then run one read against it. Nothing here persists —
 * a catalog is an <em>upstream</em> in this application, never a table, so the API exposes
 * no create/update/delete and this class has no repository.
 *
 * <p><b>Why a cache and not a bare {@code CatalogRegistry.get(...)} per request.</b>
 * The registry's entries are <em>factories</em>: every call constructs a fresh adapter, and
 * a fresh adapter owns a fresh OkHttp client — with its own connection pool, its own rate
 * limiter and its own 429 back-off. Building one per HTTP request would therefore throw
 * away exactly the pacing that keeps the source from banning us: a hundred concurrent
 * requests would become a hundred unpaced clients, each convinced it is the only caller.
 * One long-lived adapter per scheme, shared across requests, is what makes the transport
 * policy mean anything. {@code BlockingCatalog} and the adapters below it hold no
 * per-request state, so sharing is safe.
 *
 * <p><b>Threading.</b> {@link BlockingCatalog} blocks the calling thread until the
 * underlying coroutine finishes. That is the intended shape for Spring MVC (one thread per
 * request) and the reason the interop facade exists at all — see ADR-0007.
 *
 * <p><b>Contract to preserve.</b> {@code core} draws a hard line between absence and
 * failure, and this class must not blur it on the way out: a missing book is a {@code null}
 * / empty result that the controller renders as 404 / an empty array, while an unreachable
 * or unintelligible source arrives as {@link CatalogException} and becomes a 502. Do not
 * catch {@link CatalogException} here — {@code CatalogExceptionHandler} owns that mapping.
 */
@Service
public class CatalogService {

    /**
     * One {@link BlockingCatalog} per scheme, built on first use.
     *
     * <p>{@code computeIfAbsent} rather than a pre-populated map: an adapter (and its HTTP
     * client) should only exist for a source somebody actually queried.
     */
    private final Map<String, BlockingCatalog> catalogs = new ConcurrentHashMap<>();

    /**
     * Where sources come from — {@link RegistryCatalogSources} in production, a fake in
     * tests. {@link CatalogSources} explains why the indirection exists at all.
     *
     * <p>Injected and {@code final}, rather than a package-private field a test reassigns
     * after construction. Two reasons. This bean is a singleton shared by every request
     * thread, and a final field set in the constructor is safely published where a mutable
     * one is not. And a test that substitutes a <em>bean</em> instead of a field gets its own
     * application context — so its own {@code CatalogService}, with an empty
     * {@link #catalogs} cache — where mutating one shared instance in a {@code @BeforeEach}
     * leaves whatever earlier tests cached sitting underneath it.
     */
    private final CatalogSources sources;

    public CatalogService(CatalogSources sources) {
        this.sources = sources;
    }

    /**
     * The names of every catalog source this server can query.
     *
     * <p>The <em>registered</em> schemes, straight from {@link CatalogSources}, and pointedly
     * not {@code catalogs.keySet()}. That distinction is the whole trap here: {@link #catalogs}
     * is a lazily-filled cache, so reading its key set would answer "sources somebody has hit
     * since the last restart" — empty on a cold boot, growing as traffic arrives. A discovery
     * route that returns nothing until the caller already knew what to ask for is useless to
     * the frontend.
     *
     * <p>No I/O and no failure mode: the table behind {@link CatalogSources} is fixed at
     * class-init time, so this never reaches a source, never builds an adapter, and never
     * throws {@link CatalogException}.
     *
     * @return the registered scheme names, e.g. {@code ["mangadex"]}. Never {@code null};
     *         empty only if no adapter is registered at all.
     */
    public Set<String> all() {
        return sources.known();
    }

    /**
     * Searches one source by title.
     *
     * @param source the scheme to query, e.g. {@code "mangadex"}.
     * @param title  the title text to match.
     * @return the matching books, or an <b>empty list</b> if nothing matched. Empty means
     *         "no matches", never "the lookup failed".
     * @throws UnknownSourceException if no adapter owns {@code source}.
     * @throws CatalogException       if the source could not be reached or understood.
     */
    public List<Book> searchByTitle(String source, String title) {
        // TODO: catalogFor(source).search(Map.of("title", title))
        //
        // The query map is core's domain-level search criteria, not a passthrough for
        // arbitrary source parameters: only keys this method decides on should ever reach
        // it. Forwarding the raw request query string here would let a caller drive
        // MangaDex's API directly through us — and would silently break the day a source
        // that does not speak MangaDex's parameter names is registered.
        throw new UnsupportedOperationException("TODO: implement searchByTitle");
    }

    /**
     * Fetches a single book by its source-native id.
     *
     * @param source the scheme that issued the id.
     * @param id     the source-native identifier (a UUID, for MangaDex).
     * @return the book, or {@code null} if no book exists for that reference — including
     *         the case where the adapter simply cannot resolve it.
     * @throws UnknownSourceException if no adapter owns {@code source}.
     * @throws CatalogException       if the source could not be reached or understood.
     */
    public Book find(String source, String id) {
        return catalogFor(source).find(new SourceRef(source, id));
    }

    /**
     * Resolves {@code source} to its shared adapter.
     *
     * @throws UnknownSourceException if {@link #sources} owns no such scheme. That is a
     *         <em>caller</em> error (a bad request parameter), not a source failure, which
     *         is why it is a distinct exception and not a {@link CatalogException} — the
     *         two map to different statuses.
     */
    private BlockingCatalog catalogFor(String source) {
        var catalog = sources.get(source);
        if  (catalog == null) {
            throw new UnknownSourceException(source);
        }
        return new BlockingCatalog(catalog);
    }
}
