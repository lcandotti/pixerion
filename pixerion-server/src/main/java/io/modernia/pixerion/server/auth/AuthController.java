package io.modernia.pixerion.server.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Authentication endpoints: exchange credentials for a JWT, and report the current identity. */
@RestController
@RequestMapping("/auth")
@Tag(name = "Auth", description = "Login and identity")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokens;
    private final LoginAttemptService loginAttempts;

    public AuthController(
            AuthenticationManager authenticationManager,
            TokenService tokens,
            LoginAttemptService loginAttempts) {
        this.authenticationManager = authenticationManager;
        this.tokens = tokens;
        this.loginAttempts = loginAttempts;
    }

    /** {@code POST /auth/login} — verify credentials and return a signed JWT. */
    @Operation(summary = "Exchange credentials for a JWT")
    @ApiResponse(responseCode = "401", description = "Invalid credentials")
    @ApiResponse(responseCode = "429", description = "Too many failed attempts; retry later")
    // The one public endpoint: an empty requirement list overrides the spec's global bearer scheme.
    @SecurityRequirements
    @PostMapping("/login")
    public TokenService.IssuedToken login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        // Brute-force throttle, checked before the credentials: once the budget is spent
        // even correct credentials get 429 until the window relaxes.
        String clientIp = http.getRemoteAddr();
        if (loginAttempts.isBlocked(clientIp, request.username())) {
            throw new TooManyLoginAttemptsException();
        }
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
            loginAttempts.recordSuccess(clientIp, request.username());
            return tokens.issue(authentication);
        } catch (AuthenticationException e) {
            loginAttempts.recordFailure(clientIp, request.username());
            throw e; // still mapped to 401 below
        }
    }

    /** {@code GET /auth/me} — the authenticated caller's identity (requires a valid token). */
    @Operation(summary = "Current authenticated identity")
    @GetMapping("/me")
    public Identity me(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        return new Identity(authentication.getName(), roles);
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> onAuthenticationFailure(AuthenticationException e) {
        return Map.of("error", "invalid credentials");
    }

    @ExceptionHandler(TooManyLoginAttemptsException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    Map<String, String> onTooManyAttempts(TooManyLoginAttemptsException e) {
        return Map.of("error", "too many attempts");
    }

    /** Marker for a throttled login; mapped to 429 by the handler above. */
    static final class TooManyLoginAttemptsException extends RuntimeException {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record Identity(String username, List<String> roles) {
    }
}
