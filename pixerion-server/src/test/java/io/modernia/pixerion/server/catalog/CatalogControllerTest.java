package io.modernia.pixerion.server.catalog;

import io.modernia.pixerion.interop.BlockingCatalog;
import io.modernia.pixerion.mangadex.MangaDexCatalog;
import io.modernia.pixerion.server.catalog.dto.BookResponse;
import io.modernia.pixerion.server.catalog.dto.DownloadResponse;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * End-to-end coverage of {@link CatalogController} against a real random-port server:
 * the controller is wired to a genuine {@link MangaDexCatalog} pointed at an in-process
 * {@link MockWebServer} (the same technique core's adapter tests use), so the full
 * request → core → serialize path — and the contract's absence-vs-failure mapping onto
 * HTTP status — is exercised without a live network. Endpoints are reached with the
 * seeded admin's JWT since everything but {@code /auth/login} requires authentication.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class CatalogControllerTest {

    private static final String MANGA_ID = "801513ba-a712-498c-8f57-cae55b38cc92";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private MockWebServer upstream;

    /**
     * Replaces the real {@link CatalogProvider} with one whose {@code mangadex} adapter
     * targets the {@link MockWebServer}, so the controller talks to a fully functional
     * catalog whose upstream responses the test controls.
     */
    @TestConfiguration
    static class CatalogTestConfig {

        @Bean(destroyMethod = "shutdown")
        MockWebServer upstream() throws Exception {
            MockWebServer server = new MockWebServer();
            server.start();
            return server;
        }

        @Bean
        @Primary
        CatalogProvider catalogProvider(MockWebServer upstream) {
            return new CatalogProvider() {
                @Override
                public BlockingCatalog catalogFor(String source) {
                    if (MangaDexCatalog.SCHEME.equals(source)) {
                        return new BlockingCatalog(new MangaDexCatalog(upstream.url("/"), new OkHttpClient()));
                    }
                    return null;
                }
            };
        }
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
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

    private RestClient.RequestHeadersSpec<?> authGet(String uri) {
        return client().get().uri(uri).header("Authorization", "Bearer " + adminToken());
    }

    @Test
    void searchMapsUpstreamMangaOntoBookResponses() {
        upstream.enqueue(new MockResponse().setBody(SEARCH_BODY));

        BookResponse[] books = authGet("/api/search?q=berserk&source=mangadex")
                .retrieve()
                .body(BookResponse[].class);

        assertThat(books).hasSize(1);
        BookResponse book = books[0];
        assertThat(book.id()).isEqualTo(MANGA_ID);
        assertThat(book.ref()).isEqualTo("mangadex:" + MANGA_ID);
        assertThat(book.title()).isEqualTo("Berserk");
        assertThat(book.synopsis()).isEqualTo("Guts wields a giant sword.");
    }

    @Test
    void findReturnsTheBookWhenPresent() {
        upstream.enqueue(new MockResponse().setBody(SINGLE_BODY));

        BookResponse book = authGet("/api/books/" + MANGA_ID + "?source=mangadex")
                .retrieve()
                .body(BookResponse.class);

        assertThat(book).isNotNull();
        assertThat(book.title()).isEqualTo("Berserk");
    }

    @Test
    void findReturns404WhenAbsent() {
        upstream.enqueue(new MockResponse().setResponseCode(404));

        int status = authGet("/api/books/" + MANGA_ID + "?source=mangadex")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(404);
    }

    @Test
    void unknownSourceIsRejectedWith400() {
        // No upstream response is consumed: the provider returns null before any HTTP call.
        int status = authGet("/api/search?q=berserk&source=nope")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(400);
    }

    @Test
    void sourceFailureIsMappedTo502() {
        upstream.enqueue(new MockResponse().setResponseCode(503));

        int status = authGet("/api/search?q=berserk&source=mangadex")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(502);
    }

    @Test
    void downloadRunsAsAJobAndCompletes() {
        upstream.enqueue(new MockResponse().setBody(SINGLE_BODY)); // find the book
        upstream.enqueue(new MockResponse().setBody(EMPTY_FEED_BODY)); // no chapters -> nothing hits disk

        String token = adminToken();
        JobRef job = client().post()
                .uri("/api/downloads?id=" + MANGA_ID + "&source=mangadex")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(JobRef.class);

        assertThat(job).isNotNull();
        assertThat(job.status()).isEqualTo("RUNNING");

        JobStatus finished = awaitTerminal(job.id(), token);
        assertThat(finished.status()).isEqualTo("COMPLETED");
        assertThat(finished.summary()).isNotNull();
        assertThat(finished.summary().book()).isEqualTo("Berserk");
        assertThat(finished.summary().pages()).isZero();
        assertThat(finished.summary().chapters()).isZero();
    }

    @Test
    void downloadEventsStreamEndsWithACompletedEvent() {
        upstream.enqueue(new MockResponse().setBody(SINGLE_BODY));
        upstream.enqueue(new MockResponse().setBody(EMPTY_FEED_BODY));

        String token = adminToken();
        JobRef job = client().post()
                .uri("/api/downloads?id=" + MANGA_ID + "&source=mangadex")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(JobRef.class);
        assertThat(job).isNotNull();

        // Reading the event-stream body blocks until the emitter completes (the job finishes fast).
        String stream = client().get()
                .uri("/api/downloads/" + job.id() + "/events")
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .body(String.class);

        assertThat(stream).contains("completed");
        assertThat(stream).contains("Berserk");
    }

    @Test
    void downloadReportsNotFoundWhenAbsent() {
        upstream.enqueue(new MockResponse().setResponseCode(404)); // find -> null

        String token = adminToken();
        JobRef job = client().post()
                .uri("/api/downloads?id=" + MANGA_ID + "&source=mangadex")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(JobRef.class);

        JobStatus finished = awaitTerminal(job.id(), token);
        assertThat(finished.status()).isEqualTo("NOT_FOUND");
        assertThat(finished.summary()).isNull();
    }

    @Test
    void statusOfAnUnknownJobIs404() {
        int status = client().get().uri("/api/downloads/does-not-exist")
                .header("Authorization", "Bearer " + adminToken())
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(404);
    }

    @Test
    void downloadReportsFailedWhenTheSourceIsUnreachable() {
        upstream.enqueue(new MockResponse().setBody(SINGLE_BODY)); // find the book
        upstream.enqueue(new MockResponse().setResponseCode(503)); // feed unreachable -> CatalogException

        String token = adminToken();
        JobRef job = client().post()
                .uri("/api/downloads?id=" + MANGA_ID + "&source=mangadex")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(JobRef.class);

        JobStatus finished = awaitTerminal(job.id(), token);
        assertThat(finished.status()).isEqualTo("FAILED");
        assertThat(finished.summary()).isNull();
        assertThat(finished.error()).isNotBlank();
    }

    @Test
    void downloadEventsStreamEndsWithAnErrorEventWhenAbsent() {
        upstream.enqueue(new MockResponse().setResponseCode(404)); // find -> null -> NOT_FOUND

        String token = adminToken();
        JobRef job = client().post()
                .uri("/api/downloads?id=" + MANGA_ID + "&source=mangadex")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(JobRef.class);

        String stream = client().get()
                .uri("/api/downloads/" + job.id() + "/events")
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .body(String.class);

        assertThat(stream).contains("error");
        assertThat(stream).contains("NOT_FOUND");
    }

    @Test
    void eventsForAnUnknownJobIs404() {
        int status = client().get().uri("/api/downloads/does-not-exist/events")
                .header("Authorization", "Bearer " + adminToken())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(404);
    }

    /** Polls the status endpoint until the job leaves RUNNING, or fails the test. */
    private JobStatus awaitTerminal(String id, String token) {
        for (int attempt = 0; attempt < 50; attempt++) {
            JobStatus status = client().get().uri("/api/downloads/" + id)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(JobStatus.class);
            if (status != null && !"RUNNING".equals(status.status())) {
                return status;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting job " + id, e);
            }
        }
        throw new AssertionError("job " + id + " did not finish in time");
    }

    private record Token(String token, long expiresInSeconds) {
    }

    private record JobRef(String id, String status, String source) {
    }

    private record JobStatus(String id, String status, String source, DownloadResponse summary, String error) {
    }

    private static final String SEARCH_BODY = """
            {
              "result": "ok",
              "response": "collection",
              "data": [
                {
                  "id": "%s",
                  "type": "manga",
                  "attributes": {
                    "title": { "en": "Berserk" },
                    "description": { "en": "Guts wields a giant sword." }
                  }
                }
              ],
              "limit": 10, "offset": 0, "total": 1
            }
            """.formatted(MANGA_ID);

    private static final String SINGLE_BODY = """
            {
              "result": "ok",
              "response": "entity",
              "data": {
                "id": "%s",
                "type": "manga",
                "attributes": {
                  "title": { "en": "Berserk" },
                  "description": { "en": "Guts wields a giant sword." }
                }
              }
            }
            """.formatted(MANGA_ID);

    private static final String EMPTY_FEED_BODY = """
            { "result": "ok", "data": [], "limit": 100, "offset": 0, "total": 0 }
            """;
}
