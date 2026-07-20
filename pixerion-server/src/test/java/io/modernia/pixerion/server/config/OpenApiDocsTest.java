package io.modernia.pixerion.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * The generated API docs (ADR-0012) against a real random-port server: the OpenAPI
 * spec and the Scalar UI are public (no bearer token), the spec describes the known
 * endpoints, carries the bearer-JWT scheme, and exempts the login endpoint from it.
 * Assertions are on the raw JSON string — Boot 4's Jackson 3 makes tree-parsing
 * imports awkward here and substring checks are plenty for a smoke test.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class OpenApiDocsTest {

    // Injected by the test framework rather than via @LocalServerPort, to stay
    // independent of Boot's module-specific annotation packages.
    @Value("${local.server.port}")
    private int port;

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    @Test
    void apiDocsArePublicAndDescribeKnownEndpoints() {
        String spec = client().get().uri("/v3/api-docs")
                .retrieve()
                .body(String.class);

        assertThat(spec).isNotNull();
        assertThat(spec).contains("\"openapi\"");
        // Endpoints from both controllers made it into the spec.
        assertThat(spec).contains("/auth/login", "/auth/me", "/api/search", "/api/books/{id}",
                "/api/downloads", "/api/downloads/{id}", "/api/downloads/{id}/events");
        // The global bearer-JWT scheme is declared…
        assertThat(spec).contains("\"bearer-jwt\"");
        // …and the login operation opts out with an empty security list.
        assertThat(spec).contains("\"security\":[]");
    }

    @Test
    void scalarUiIsPublic() {
        String page = client().get().uri("/scalar")
                .retrieve()
                .body(String.class);

        assertThat(page).isNotNull();
        assertThat(page.toLowerCase()).contains("<!doctype html>");
    }
}
