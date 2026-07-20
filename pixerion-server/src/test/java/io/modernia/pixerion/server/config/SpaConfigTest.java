package io.modernia.pixerion.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * The SPA serving contract (ADR-0013), exercised against the test classpath's
 * stand-in {@code META-INF/resources/index.html} (the real bundle ships in the
 * webapp jar, which tests deliberately don't carry): static resources are served
 * as-is, unknown client-side routes fall back to {@code index.html} (history-API
 * fallback), and backend prefixes are exempt from the fallback so API 404s stay 404s.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class SpaConfigTest {

    @Value("${local.server.port}")
    private int port;

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    @Test
    void rootServesTheSpaShellWithoutAuthentication() {
        String page = client().get().uri("/")
                .retrieve()
                .body(String.class);

        assertThat(page).contains("pixerion-spa-shell");
    }

    @Test
    void unknownClientRouteFallsBackToTheSpaShell() {
        // A pushState route only the Angular router knows: a deep link or refresh must
        // get index.html (200), not a 404, so the client router can take over.
        String page = client().get().uri("/library/books/42")
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(String.class);

        assertThat(page).contains("pixerion-spa-shell");
    }

    @Test
    void unknownApiPathStaysA404AndNeverFallsBackToHtml() {
        String token = adminToken();
        int status = client().get().uri("/api/does-not-exist")
                .header("Authorization", "Bearer " + token)
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(404);
    }

    @Test
    void nonGetRequestsOutsideTheApiAreDenied() {
        // anyRequest().denyAll(): the writable surface is only /api/** + /auth/**.
        // Anonymous callers are bounced to the entry point (401); even a valid
        // admin token doesn't open non-API writes (403).
        int anonymous = client().post().uri("/library")
                .exchange((request, response) -> response.getStatusCode().value());
        int authenticated = client().post().uri("/library")
                .header("Authorization", "Bearer " + adminToken())
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(anonymous).isEqualTo(401);
        assertThat(authenticated).isEqualTo(403);
    }

    private String adminToken() {
        Token token = client().post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "admin", "password", "admin"))
                .retrieve()
                .body(Token.class);
        assertThat(token).isNotNull();
        return token.token();
    }

    private record Token(String token, long expiresInSeconds) {
    }
}
