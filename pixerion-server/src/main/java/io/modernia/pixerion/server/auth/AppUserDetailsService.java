package io.modernia.pixerion.server.auth;

import org.jspecify.annotations.NonNull;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/** Loads users from the database for Spring Security, mapping roles to {@code ROLE_*} authorities. */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public @NonNull UserDetails loadUserByUsername(@NonNull String username) {
        AppUser user = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));
        List<SimpleGrantedAuthority> authorities = user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName()))
                .toList();
        return User.withUsername(user.getUsername())
                .password(user.getPassword())
                .authorities(authorities)
                .disabled(!user.isEnabled())
                .build();
    }
}
