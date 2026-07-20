package io.modernia.pixerion.server.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import java.util.HashSet;
import java.util.Set;

/**
 * An application user. The {@code password} column holds a BCrypt hash, never a
 * plaintext password. Roles are eagerly loaded because they are needed on every
 * authentication to build the user's authorities.
 */
@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(nullable = false, length = 100)
    private String password;

    // JPA hydrates this column by reflection on load, so it can be neither final
    // (reflective final-field writes fail on JDK 25) nor a local variable.
    @SuppressWarnings({"FieldMayBeFinal", "FieldCanBeLocal"})
    @Column(nullable = false)
    private boolean enabled = true;

    // JPA replaces this collection with its own PersistentSet by reflection, so it can't be final.
    @SuppressWarnings("FieldMayBeFinal")
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    protected AppUser() {
        // for JPA
    }

    public AppUser(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public void addRole(Role role) {
        roles.add(role);
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Set<Role> getRoles() {
        return roles;
    }
}
