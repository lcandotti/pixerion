package io.modernia.pixerion.server.auth.controllers;

import io.modernia.pixerion.server.config.JwtProperties;
import io.modernia.pixerion.server.config.SecurityConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Objects;

/**
 * Issues access tokens. This is the only endpoint that authenticates a password — every
 * other route is a pure resource server that trusts a signed token and nothing else.
 *
 * <p>{@code POST /api/auth/login} is explicitly permitted in {@code SecurityConfig};
 * without that exemption it would be caught by the {@code /api/**} rule and could never
 * be reached unauthenticated.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Sign in")
public class TokenController {

    private final AuthenticationManager authenticationManager;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public TokenController(AuthenticationManager authenticationManager, JwtEncoder jwtEncoder, JwtProperties jwtProperties) {
        this.authenticationManager = authenticationManager;
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
    }

    @PostMapping("/login")
    @Operation(summary = "Obtain a JWT token", description = "Return an access token to access protected endpoint")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Obtain a valid access token"),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid email / password",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ProblemDetail.class)))
    })
    public LoginResponse login(@RequestBody @Valid LoginRequest loginRequest) {
        // Throws BadCredentialsException on a bad password *or* an unknown email —
        // DaoAuthenticationProvider hides UsernameNotFoundException behind it by default
        // so the response cannot be used to probe which accounts exist.
        var authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.email(), loginRequest.password()));

        var now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer("pixerion")
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.ttl()))
                .subject(authentication.getName())
                // Roles only. Spring Security 7 also grants factor authorities such as
                // FACTOR_PASSWORD, which are true of the authentication but are not roles
                // — putting them in a claim named `roles` would make the token lie.
                .claim(SecurityConfig.ROLES_CLAIM, authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority).filter(Objects::nonNull)
                        .filter(authority -> authority.startsWith("ROLE_"))
                        .toList())
                .build();

        var token = jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        return new LoginResponse(token, jwtProperties.ttl().toSeconds());
    }

}
