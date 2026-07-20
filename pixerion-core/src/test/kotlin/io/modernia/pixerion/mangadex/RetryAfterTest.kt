package io.modernia.pixerion.mangadex

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Exercises [RetryInterceptor]'s back-off-source branches: each `Retry-After`
 * form, the `X-RateLimit-Retry-After` fallback, and the exponential default when
 * no hint is present. Past timestamps are used so the computed wait coerces to
 * zero and the tests don't actually sleep.
 */
class RetryAfterTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun retryClient() =
        OkHttpClient
            .Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 2, baseBackoffMillis = 1))
            .build()

    private fun get(client: OkHttpClient) = client.newCall(Request.Builder().url(server.url("/")).build()).execute()

    @Test
    fun `honours an HTTP-date Retry-After`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "Wed, 21 Oct 2015 07:28:00 GMT"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        get(retryClient()).use { assertEquals(200, it.code) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `falls back to X-RateLimit-Retry-After`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("X-RateLimit-Retry-After", "1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        get(retryClient()).use { assertEquals(200, it.code) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `backs off exponentially when no hint is present`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        get(retryClient()).use { assertEquals(200, it.code) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an unparseable Retry-After is ignored`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "soon"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        get(retryClient()).use { assertEquals(200, it.code) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `the rate-limit interceptor passes the request through`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(RateLimitInterceptor(RateLimiter(permitsPerSecond = 1000.0)))
                .build()

        get(client).use { assertEquals(200, it.code) }
        assertEquals(1, server.requestCount)
    }
}
