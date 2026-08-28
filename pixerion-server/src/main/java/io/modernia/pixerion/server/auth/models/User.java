package io.modernia.pixerion.server.auth.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

/** An account that can authenticate, and the roles it holds. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    /**
     * The roles this user holds, via the {@code user_roles} join table.
     *
     * <p>Note where these annotations sit. An entity's access type follows its
     * {@code @Id}, which is on a <em>field</em> above — so JPA reads mapping annotations
     * from fields only, and the same annotations placed on a getter would be silently
     * ignored, leaving Hibernate to treat this collection as a basic column and fail at
     * bootstrap.
     *
     * <p>{@code EAGER} is deliberate: {@code spring.jpa.open-in-view=false} closes the
     * session before {@code UserService} reads the roles, so {@code LAZY} would raise
     * {@code LazyInitializationException}. The set is tiny and a {@code User} is only
     * ever loaded in order to authenticate. The alternative, if this ever needs to be
     * lazy, is {@code @EntityGraph(attributePaths = "roles")} on the repository method.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    public void addRole(Role role) {
        roles.add(role);
    }
}
