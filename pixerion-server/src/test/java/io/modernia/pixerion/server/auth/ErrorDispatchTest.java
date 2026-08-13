package io.modernia.pixerion.server.auth;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Runs against a real servlet container, which the rest of the suite does not.
 *
 * <p>That is the entire point: authorization runs on every dispatch, so the terminal
 * {@code denyAll()} will re-deny the container's internal forward to {@code /error}
 * unless the ERROR dispatch is permitted — turning a 400 into an empty 401 and a 404
 * into an empty 403. MockMvc does not perform that forward, so it reports the original
 * status and cannot see the bug at all. Only an end-to-end request catches it.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class ErrorDispatchTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private String adminToken() throws Exception {
        HttpResponse<String> response = send(request("/api/auth/login")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"email": "admin@pixerion.local", "password": "changeme"}"""))
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.accessToken");
    }

    @Test
    void a_validation_failure_is_reported_as_400_not_swallowed_into_401() throws Exception {
        HttpResponse<String> response = send(request("/api/auth/login")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"email": "", "password": ""}"""))
                .build());

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void an_unmapped_api_path_is_reported_as_404_not_swallowed_into_403() throws Exception {
        HttpResponse<String> response = send(request("/api/anything")
                .header("Authorization", "Bearer " + adminToken())
                .GET().build());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void a_failed_login_keeps_its_problem_detail_body() throws Exception {
        HttpResponse<String> response = send(request("/api/auth/login")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"email": "admin@pixerion.local", "password": "not-the-password"}"""))
                .build());

        assertThat(response.statusCode()).isEqualTo(401);
        // An empty body here would mean the response was rewritten by the error dispatch
        // rather than produced by AuthExceptionHandler.
        assertThat(response.body()).contains("Invalid email or password.");
    }
}
