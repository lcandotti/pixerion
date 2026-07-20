package io.modernia.pixerion.server.auth;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Idempotently seeds the baseline roles and an initial admin user on startup.
 *
 * Seeding lives here (not in a Flyway migration) because the admin password must be
 * hashed by the application's {@link PasswordEncoder}. Credentials come from
 * {@code app.admin.*} and default to {@code admin}/{@code admin} for local use —
 * override them (and the JWT secret) in any real deployment.
 *
 * <p>Each seed step is check-then-act, so two instances booting against the same
 * database can race: both pass the exists-check, the loser's insert hits the unique
 * constraint. That violation poisons the transaction it happens in, which is why each
 * step runs in its own {@link TransactionTemplate transaction} with the catch outside
 * the boundary — the loser's step rolls back cleanly and it simply adopts the winner's
 * row instead of crashing startup.
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    private static final List<String> BASELINE_ROLES = List.of("USER", "ADMIN");

    private final RoleRepository roles;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transaction;
    private final String adminUsername;
    private final String adminPassword;

    public DataInitializer(
            RoleRepository roles,
            AppUserRepository users,
            PasswordEncoder encoder,
            PlatformTransactionManager transactionManager,
            @Value("${app.admin.username}") String adminUsername,
            @Value("${app.admin.password}") String adminPassword) {
        this.roles = roles;
        this.users = users;
        this.encoder = encoder;
        this.transaction = new TransactionTemplate(transactionManager);
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(@NonNull ApplicationArguments args) {
        BASELINE_ROLES.forEach(this::ensureRole);
        ensureAdmin();
    }

    private void ensureRole(String name) {
        try {
            // saveAndFlush (not save): the flush surfaces a constraint violation here,
            // already translated, instead of at commit wrapped in a transaction error.
            transaction.executeWithoutResult(tx ->
                    roles.findByName(name).orElseGet(() -> roles.saveAndFlush(new Role(name))));
        } catch (DataIntegrityViolationException e) {
            // Rethrow unless the row is really there — then it wasn't the seed race.
            roles.findByName(name).orElseThrow(() -> e);
            log.debug("Role '{}' was seeded concurrently by another instance", name);
        }
    }

    private void ensureAdmin() {
        try {
            transaction.executeWithoutResult(tx -> {
                if (users.existsByUsername(adminUsername)) {
                    return;
                }
                AppUser admin = new AppUser(adminUsername, encoder.encode(adminPassword));
                roles.findByName("ADMIN").ifPresent(admin::addRole);
                roles.findByName("USER").ifPresent(admin::addRole);
                users.saveAndFlush(admin);
                log.info("Seeded initial admin user '{}'", adminUsername);
            });
        } catch (DataIntegrityViolationException e) {
            if (!users.existsByUsername(adminUsername)) {
                throw e; // not the seed race — a genuine integrity failure
            }
            log.info("Admin user '{}' was seeded concurrently by another instance", adminUsername);
        }
    }
}
