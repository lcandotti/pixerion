package io.modernia.pixerion.server;

import io.modernia.pixerion.server.auth.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full context against a real PostgreSQL container with Flyway ENABLED and
 * {@code ddl-auto=validate} — exactly the production wiring — proving the V1 migration
 * actually matches the JPA entities. The H2 suites can never catch that drift: there
 * Flyway is off and Hibernate creates the schema <em>from</em> the entities. Skipped
 * (not failed) on machines without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        // Override the H2-oriented test defaults; the datasource itself comes from the
        // container via @ServiceConnection, which takes precedence over properties.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
})
class FlywayMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private AppUserRepository users;

    @Test
    void migratedSchemaMatchesTheEntitiesAndAcceptsTheSeed() {
        // Reaching here means Hibernate's validate passed against the Flyway-created
        // schema; the seeded admin proves inserts (users, roles, join table) work too.
        assertThat(users.existsByUsername("admin")).isTrue();
    }
}
