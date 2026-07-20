package io.modernia.pixerion.server.auth;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * The login brute-force throttle end-to-end (in {@link AuthSecurityTest} style): five
 * failures for the same client/username exhaust the budget, then even correct
 * credentials get 429; a successful login resets the counter. The throttle keys on the
 * seeded admin, so this class forks its own context (via the marker property below) —
 * blocking 'admin' in a context shared with the other suites would break their logins.
 * Methods are ordered because the last test deliberately leaves 'admin' blocked.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "pixerion.test.context=login-throttle")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoginThrottleTest {

    // Injected by the test framework rather than via @LocalServerPort, to stay
    // independent of Boot's module-specific annotation packages.
    @Value("${local.server.port}")
    private int port;

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private int statusLogin(String username, String password) {
        return client().post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", username, "password", password))
                .exchange((request, response) -> response.getStatusCode().value());
    }

    @Test
    @Order(1)
    void successfulLoginResetsTheFailureCounter() {
        for (int i = 0; i < 4; i++) {
            assertThat(statusLogin("admin", "wrong")).isEqualTo(401);
        }
        assertThat(statusLogin("admin", "admin")).isEqualTo(200); // resets the four failures
        for (int i = 0; i < 4; i++) {
            assertThat(statusLogin("admin", "wrong")).isEqualTo(401);
        }
        // Eight failures total, but only four since the reset — without it this login
        // would be over budget and get 429. Also leaves the counter cleared for Order(2).
        assertThat(statusLogin("admin", "admin")).isEqualTo(200);
    }

    @Test
    @Order(2) // last: leaves 'admin' blocked in this context
    void sixthAttemptIsThrottledEvenWithCorrectCredentials() {
        for (int i = 0; i < 5; i++) {
            assertThat(statusLogin("admin", "wrong")).isEqualTo(401);
        }
        assertThat(statusLogin("admin", "admin")).isEqualTo(429);
        assertThat(statusLogin("admin", "wrong")).isEqualTo(429);
    }
}
