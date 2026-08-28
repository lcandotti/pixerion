package io.modernia.pixerion.server.auth;

import io.modernia.pixerion.server.auth.models.Role;
import io.modernia.pixerion.server.auth.models.RoleRepository;
import io.modernia.pixerion.server.auth.models.User;
import io.modernia.pixerion.server.auth.models.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the many-to-many mapping between {@link User} and {@link Role}, and — because
 * the suite runs the real Flyway migrations against {@code ddl-auto=validate} — the
 * agreement between those entities and the schema.
 */
@SpringBootTest
@Transactional
class UserRepositoryTest {

    @Autowired
    UserRepository users;
    @Autowired
    RoleRepository roles;
    @Autowired
    EntityManager entityManager;

    @Test
    void roles_survive_a_round_trip_through_the_join_table() {
        User user = new User();
        user.setEmail("round-trip@pixerion.local");
        user.setPassword("{noop}irrelevant");
        user.addRole(roles.findByName("ROLE_USER"));
        user.addRole(roles.findByName("ROLE_ADMIN"));
        users.save(user);

        // Force the insert, then drop the persistence context so the reload below is a
        // real SELECT rather than the same instance handed back from the first-level cache.
        entityManager.flush();
        entityManager.clear();

        User reloaded = users.findByEmail("round-trip@pixerion.local");
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getRoles())
                .extracting(Role::getName)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void roles_are_shared_rows_not_per_user_copies() {
        Role admin = roles.findByName("ROLE_ADMIN");

        User first = new User();
        first.setEmail("first@pixerion.local");
        first.setPassword("{noop}irrelevant");
        first.setRoles(Set.of(admin));
        users.save(first);

        User second = new User();
        second.setEmail("second@pixerion.local");
        second.setPassword("{noop}irrelevant");
        second.setRoles(Set.of(admin));
        users.save(second);
        entityManager.flush();

        // The point of the lookup-table model: two admins, still one ROLE_ADMIN row.
        // A true @OneToMany with a unique name column could not express this.
        assertThat(roles.findAll()).extracting(Role::getName)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void seeded_admin_has_both_roles() {
        User admin = users.findByEmail("admin@pixerion.local");

        assertThat(admin).as("seeded by V3__seed_admin.sql").isNotNull();
        assertThat(admin.getRoles())
                .extracting(Role::getName)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }
}
