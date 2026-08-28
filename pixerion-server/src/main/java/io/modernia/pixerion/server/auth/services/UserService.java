package io.modernia.pixerion.server.auth.services;

import io.modernia.pixerion.server.auth.models.User;
import io.modernia.pixerion.server.auth.models.UserRepository;
import org.jspecify.annotations.NullMarked;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Looks users up for Spring Security. This is the outbound half of the role plumbing:
 * stored roles become {@code GrantedAuthority}s here, on the way into a login. The
 * inbound half — turning a token's {@code roles} claim back into authorities — lives in
 * {@code SecurityConfig.jwtAuthenticationConverter()}, and the two must stay in step.
 *
 * <p>Note that this only <em>fetches</em>. Comparing the submitted password against the
 * stored hash is {@code DaoAuthenticationProvider}'s job.
 */
@Service
public class UserService implements UserDetailsService {

    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    @Override
    @NullMarked
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = repository.findByEmail(username);
        if (user == null) {
            throw new UsernameNotFoundException(username);
        }
        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPassword(),
                // Role names are already stored with the ROLE_ prefix, so they map
                // straight onto authorities with no rewriting.
                user.getRoles().stream()
                        .map(role -> new SimpleGrantedAuthority(role.getName()))
                        .toList()
        );
    }

}
