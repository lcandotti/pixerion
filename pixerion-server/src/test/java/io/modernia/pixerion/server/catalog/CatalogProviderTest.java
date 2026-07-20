package io.modernia.pixerion.server.catalog;

import io.modernia.pixerion.interop.BlockingCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage of the source registry itself. {@link CatalogControllerTest} overrides
 * this bean with a {@code @Primary} test double pointed at a mock server, so the real
 * {@code catalogFor} wiring — mangadex resolves, everything else is {@code null} — is
 * only exercised here. Construction issues no request, so no network is touched.
 */
class CatalogProviderTest {
    private final CatalogProvider provider = new CatalogProvider();

    @Test
    void resolvesTheMangaDexSourceToABlockingCatalog() {
        BlockingCatalog catalog = provider.catalogFor("mangadex");

        assertThat(catalog).isInstanceOf(BlockingCatalog.class);
    }

    @Test
    void returnsNullForAnUnknownSource() {
        assertThat(provider.catalogFor("not-a-source")).isNull();
    }
}
