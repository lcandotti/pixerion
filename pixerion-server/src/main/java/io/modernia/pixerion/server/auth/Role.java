package io.modernia.pixerion.server.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Objects;

/**
 * A grantable role, e.g. {@code ROLE_ADMIN}.
 *
 * <p>One canonical row per name, shared by every user that holds it. {@link User} owns
 * the association through the {@code user_roles} join table, so there is deliberately no
 * back-reference to {@code User} here — a role does not belong to anyone.
 *
 * <p>Names are stored <em>with</em> the {@code ROLE_} prefix that Spring Security's
 * {@code hasRole(...)} prepends when matching, so no translation is needed on either
 * side of the token.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "roles")
public class Role {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    public Role(String name) {
        this.name = name;
    }

    // Keyed on `name`, not `id`: User.roles is a Set, and Hibernate compares elements
    // while flushing — before a generated id exists, when every transient Role would
    // otherwise collide on a null id. `name` is unique and stable, so it is a safe
    // natural key. This is also why Lombok's @EqualsAndHashCode is not used here.
    // `instanceof` rather than getClass() so a Hibernate proxy still compares equal.
    @Override
    public boolean equals(Object o) {
        return o instanceof Role other && Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
