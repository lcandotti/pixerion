package io.modernia.pixerion.server.catalog.components;

import io.modernia.pixerion.domain.Catalog;
import io.modernia.pixerion.server.catalog.services.CatalogService;
import io.modernia.pixerion.server.catalog.components.RegistryCatalogSources;
import io.modernia.pixerion.server.catalog.exceptions.UnknownSourceException;
import io.modernia.pixerion.source.CatalogRegistry;

import java.util.Set;

/**
 * The server's view of the set of catalog sources it can query.
 *
 * <p>A thin port over {@link CatalogRegistry}, and the one seam {@link CatalogService}
 * depends on instead of reaching for the registry directly. The registry is a Kotlin
 * {@code object} with a fixed table and no runtime registration — there is nothing to stub —
 * so a service that called it would leave a test no choice but to hit the live MangaDex API.
 * Injecting this interface is what lets a test hand the service a fake source and drive an
 * endpoint end to end with no network. The CLI keeps the same escape hatch in
 * {@code CatalogCommand} for the same reason.
 *
 * <p><b>Two operations, not one.</b> Resolving a single scheme is what the read endpoints
 * need, but {@code /api/catalog} has to <em>enumerate</em> them, and a resolver alone cannot
 * answer that. Keeping both here means the discovery route is as substitutable as the reads
 * are; a bare {@code Function<String, Catalog>} would leave it with no seam at all and
 * tempt it into reporting whatever happens to be lying in a cache.
 *
 * <p>Implementations are shared across request threads and must be safe to call
 * concurrently. {@link RegistryCatalogSources} is, trivially — the registry is immutable.
 */
public interface CatalogSources {

    /**
     * Resolves a scheme to a freshly built adapter.
     *
     * <p>Note this <em>constructs</em>: the registry's entries are factories, so every call
     * yields a new adapter owning a new HTTP client. Callers are expected to hold onto the
     * result rather than call this per request — see {@link CatalogService} on why that
     * matters for rate limiting.
     *
     * @param scheme the source name, e.g. {@code "mangadex"}.
     * @return the adapter that owns {@code scheme}, or {@code null} if none does. A
     *         {@code null} here is a plain "no such source" — a caller-facing usage error,
     *         not a source failure — which {@link CatalogService} turns into an
     *         {@link UnknownSourceException} and the API renders as a 400.
     */
    Catalog get(String scheme);

    /**
     * The scheme names {@link #get} can resolve.
     *
     * @return the registered schemes. Never {@code null}; empty only if nothing is
     *         registered at all.
     */
    Set<String> known();
}
