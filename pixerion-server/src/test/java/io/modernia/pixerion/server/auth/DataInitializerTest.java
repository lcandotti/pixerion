package io.modernia.pixerion.server.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The multi-instance seed race: two instances boot against the same database, both
 * pass the exists-check, the loser's insert hits the unique constraint. The happy
 * path is covered by every H2-backed context test (the admin login works), so this
 * only pins the losing side — every insert below collides with a row "another
 * instance" just wrote, and startup must survive by adopting that row.
 */
class DataInitializerTest {

    @Test
    void losingEverySeedRaceToAnotherInstanceStillCompletesStartup() {
        RoleRepository roles = mock(RoleRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(encoder.encode(anyString())).thenReturn("{bcrypt}hash");

        // Roles: the lookup misses, and the insert finds the winner's row already there.
        Map<String, Role> roleStore = new ConcurrentHashMap<>();
        when(roles.findByName(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(roleStore.get(inv.<String>getArgument(0))));
        when(roles.saveAndFlush(any(Role.class))).thenAnswer(inv -> {
            Role role = inv.getArgument(0);
            roleStore.put(role.getName(), role); // the other instance committed first…
            throw new DataIntegrityViolationException("duplicate key: roles.name");
        });

        // Admin: same story for the user row.
        AtomicBoolean adminInserted = new AtomicBoolean();
        when(users.existsByUsername("admin")).thenAnswer(inv -> adminInserted.get());
        when(users.saveAndFlush(any(AppUser.class))).thenAnswer(inv -> {
            adminInserted.set(true);
            throw new DataIntegrityViolationException("duplicate key: app_users.username");
        });

        DataInitializer initializer = new DataInitializer(roles, users, encoder, txManager, "admin", "admin");

        assertThatCode(() -> initializer.run(mock(ApplicationArguments.class))).doesNotThrowAnyException();
    }
}
