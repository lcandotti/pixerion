package io.modernia.pixerion.server.catalog;

import io.modernia.pixerion.domain.Book;
import io.modernia.pixerion.domain.Catalog;
import io.modernia.pixerion.domain.CatalogException;
import io.modernia.pixerion.interop.BlockingCatalog;
import io.modernia.pixerion.source.CatalogRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
     * Resolves a source name to a {@link Catalog}. Defaults to the shared registry in
     * {@code core}; tests replace it with a fake so an endpoint can be driven end to end
     * without touching the network.
     *
     * <p>This seam is why the class is testable at all. {@link CatalogRegistry} is a Kotlin
     * {@code object} with a fixed table and no runtime registration — there is nothing to
     * stub — so calling it directly would leave {@code @WebMvcTest} no choice but to hit
     * the live MangaDex API. The CLI keeps the same escape hatch in {@code CatalogCommand}
     * for the same reason.
     *
     * <p>Package-private and mutable rather than a constructor parameter: two constructors
     * would leave Spring guessing which one to autowire, and a single one would force every
     * caller to pass the default in.
     */
    Function<String, Catalog> sources = CatalogRegistry::get;

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
        // TODO: catalogFor(source).find(new SourceRef(source, id))
        //
        // A SourceRef, not a BookId: the id came in scoped to a source, and minting a
        // portable BookId from it would claim a cross-source identity nothing established.
        // The CLI's parseRef() makes the same choice for a bare token.
        throw new UnsupportedOperationException("TODO: implement find");
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
        // TODO: computeIfAbsent(source, ...) over sources.apply(scheme), wrapping the
        // returned Catalog in a new BlockingCatalog; throw UnknownSourceException when the
        // resolver returns null.
        //
        // Careful: computeIfAbsent's mapping function must not return null (that would just
        // mean "absent" and re-run on every request, so the unknown-source case would never
        // surface). Check the registry first, or throw from inside the mapping function.
        throw new UnsupportedOperationException("TODO: implement catalogFor");
    }
}
