package io.modernia.pixerion.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the Spring context assembles — every bean (the controller, the
 * {@link io.modernia.pixerion.server.catalog.CatalogProvider}) wires without error.
 * Uses the default mock web environment, so no real server starts and no network is touched.
 */
@SpringBootTest
class ApplicationTests {

    @Test
    void contextLoads() {
    }
}
