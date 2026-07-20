package io.modernia.pixerion.server.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain unit coverage of the JPA entities' accessors. The auth flow tests reach these
 * entities only through Spring Security (username / password / authorities), so the
 * identity accessors — populated by JPA on persist and {@code null} on a fresh
 * instance — are asserted directly here.
 */
class AuthEntityTest {
    @Test
    void aFreshRoleExposesItsNameAndAnUnassignedId() {
        Role role = new Role("ADMIN");

        assertThat(role.getName()).isEqualTo("ADMIN");
        assertThat(role.getId()).isNull();
    }

    @Test
    void aFreshUserExposesItsCredentialsRolesAndAnUnassignedId() {
        AppUser user = new AppUser("alice", "hashed");
        Role role = new Role("USER");
        user.addRole(role);

        assertThat(user.getId()).isNull();
        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getPassword()).isEqualTo("hashed");
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getRoles()).containsExactly(role);
    }
}
