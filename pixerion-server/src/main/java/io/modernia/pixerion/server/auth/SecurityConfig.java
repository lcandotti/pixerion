package io.modernia.pixerion.server.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Stateless JWT security (ADR-0008). Tokens are signed/verified with a symmetric
 * HS256 key derived from {@code app.security.jwt.secret}. {@code /auth/login}, the
 * health probe, and the API docs (spec + Scalar UI, ADR-0012) are public; the API
 * ({@code /api/**} and the rest of {@code /auth/**}) requires a valid bearer token.
 * The token's {@code roles} claim is mapped to {@code ROLE_*} authorities for
 * {@code @PreAuthorize}.
 *
 * <p>Everything else that is a GET is public: that's the embedded SPA (ADR-0013) —
 * {@code index.html}, its hashed JS/CSS bundles, and any client-side route the SPA
 * fallback rewrites to {@code index.html}. The shell is static content; every call it
 * makes back to the API is what carries (and is gated by) the JWT.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final SecretKeySpec jwtKey;

    public SecurityConfig(@Value("${app.security.jwt.secret}") String secret) {
        // HS256 needs a key of at least 256 bits — enforce a sane minimum early.
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("app.security.jwt.secret must be at least 32 bytes for HS256");
        }
        this.jwtKey = new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    // CSRF is disabled deliberately: this is a stateless API (no sessions, no cookies) that
    // authenticates via a Bearer token in the Authorization header. CSRF relies on the browser
    // auto-attaching ambient credentials (cookies, HTTP Basic, JSESSIONID) to a forged cross-site
    // request; browsers do not auto-attach Authorization headers, so there is nothing to forge.
    // Disabling CSRF is the recommended configuration for token-in-header stateless APIs (ADR-0008).
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // API docs (ADR-0012): the spec and the Scalar UI (page + its JS asset)
                        // are deliberately public; calling the API still requires a token.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/scalar", "/scalar/**").permitAll()
                        // The whole API surface lives under /api (ADR-0013) plus /auth/me;
                        // matching it explicitly keeps future endpoints secure by default.
                        .requestMatchers("/api/**", "/auth/**").authenticated()
                        // Everything else that is a GET is the SPA: index.html, hashed
                        // bundles, and client-side routes served via the SPA fallback.
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter())));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private JwtAuthenticationConverter jwtAuthConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
