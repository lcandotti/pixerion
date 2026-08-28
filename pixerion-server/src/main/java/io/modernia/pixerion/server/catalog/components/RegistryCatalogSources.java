package io.modernia.pixerion.server.catalog.components;

import io.modernia.pixerion.domain.Catalog;
import io.modernia.pixerion.source.CatalogRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * The production {@link CatalogSources}: whatever {@code core} ships.
 *
 * <p>Pure delegation, and deliberately so — every question about <em>which</em> sources
 * exist is answered in one place, {@link CatalogRegistry}, so registering an adapter there
 * is still the only step needed to expose it through the API. Nothing may be filtered or
 * added here without breaking that promise.
 *
 * <p>Package-private: the interface is the contract, and no caller outside this package has
 * a reason to name the implementation.
 */
@Component
public class RegistryCatalogSources implements CatalogSources {

    @Override
    public Catalog get(String scheme) {
        return CatalogRegistry.get(scheme);
    }

    @Override
    public Set<String> known() {
        return CatalogRegistry.getKnown();
    }
}
