package io.modernia.pixerion.server.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * End-to-end auth wiring against a real random-port server backed by in-memory H2:
 * the seeded admin can exchange credentials for a JWT and reach protected endpoints,
 * anonymous callers cannot, and the health probe stays public (so the container
 * healthcheck keeps working under security).
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class AuthSecurityTest {

    // Injected by the test framework rather than via @LocalServerPort, to stay
    // independent of Boot's module-specific annotation packages.
    @Value("${local.server.port}")
    private int port;

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private int statusGet(String uri, String bearer) {
        RestClient.RequestHeadersSpec<?> spec = client().get().uri(uri);
        if (bearer != null) {
            spec = spec.header("Authorization", "Bearer " + bearer);
        }
        return spec.exchange((request, response) -> response.getStatusCode().value());
    }

    private int statusLogin(String username, String password) {
        return client().post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", username, "password", password))
                .exchange((request, response) -> response.getStatusCode().value());
    }

    @Test
    void healthIsPublic() {
        assertThat(statusGet("/actuator/health", null)).isEqualTo(200);
    }

    @Test
    void protectedEndpointRejectsAnonymous() {
        assertThat(statusGet("/auth/me", null)).isEqualTo(401);
    }

    @Test
    void downloadEndpointsRejectAnonymous() {
        // The security filter rejects before the controller runs, so an unknown job id is irrelevant.
        assertThat(statusGet("/api/downloads/any-id", null)).isEqualTo(401);
        assertThat(statusGet("/api/downloads/any-id/events", null)).isEqualTo(401);
    }

    @Test
    void badCredentialsAreRejected() {
        assertThat(statusLogin("admin", "wrong")).isEqualTo(401);
    }

    @Test
    void loginYieldsTokenThatGrantsAccess() {
        Token token = client().post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "admin", "password", "admin"))
                .retrieve()
                .body(Token.class);
        assertThat(token).isNotNull();
        assertThat(token.token()).isNotBlank();

        Identity me = client().get().uri("/auth/me")
                .header("Authorization", "Bearer " + token.token())
                .retrieve()
                .body(Identity.class);
        assertThat(me).isNotNull();
        assertThat(me.username()).isEqualTo("admin");
        assertThat(me.roles()).contains("ROLE_ADMIN");
    }

    record Token(String token, long expiresInSeconds) {
    }

    record Identity(String username, List<String> roles) {
    }
}
