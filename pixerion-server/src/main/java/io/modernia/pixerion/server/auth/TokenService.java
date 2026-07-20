package io.modernia.pixerion.server.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Mints signed JWTs for authenticated principals. */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final long ttlSeconds;

    public TokenService(JwtEncoder encoder, @Value("${app.security.jwt.ttl-seconds}") long ttlSeconds) {
        this.encoder = encoder;
        this.ttlSeconds = ttlSeconds;
    }

    /** Issues a token whose {@code roles} claim holds the principal's role names (without the {@code ROLE_} prefix). */
    public IssuedToken issue(Authentication authentication) {
        Instant now = Instant.now();
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                // getAuthority() may be null when an authority has no String representation; skip those.
                .filter(Objects::nonNull)
                .map(authority -> authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority)
                .toList();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("pixerion")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(ttlSeconds))
                .subject(authentication.getName())
                .claim("roles", roles)
                .build();
        // Pin HS256 explicitly: with a symmetric secret the encoder otherwise defaults
        // to RS256 and fails to select a signing key. Must match the decoder's algorithm.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, ttlSeconds);
    }

    public record IssuedToken(String token, long expiresInSeconds) {
    }
}
