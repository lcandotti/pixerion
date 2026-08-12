package io.modernia.pixerion.source.mangadex

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetryInterceptorTest {
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

    // baseBackoffMillis kept tiny so the retry path doesn't actually wait in tests.
    private fun client() =
        OkHttpClient
            .Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 2, baseBackoffMillis = 1))
            .build()

    private fun get() = client().newCall(Request.Builder().url(server.url("/")).build()).execute()

    @Test
    fun `retries a 429 and returns the eventual success`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        get().use { response ->
            assertEquals(200, response.code)
            assertEquals("ok", response.body?.string())
        }
        assertEquals(2, server.requestCount) // initial 429 + one retry
    }

    @Test
    fun `gives up after maxRetries and returns the last 429`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0")) }

        get().use { response ->
            assertEquals(429, response.code)
        }
        assertEquals(3, server.requestCount) // initial + maxRetries(2)
    }

    @Test
    fun `does not retry a non-429 failure`() {
        server.enqueue(MockResponse().setResponseCode(503))

        get().use { response ->
            assertEquals(503, response.code)
        }
        assertEquals(1, server.requestCount)
    }
}

class RateLimiterTest {
    @Test
    fun `spaces acquisitions to the configured rate`() {
        // 100 permits/sec => ~10ms apart; the first is free, so 5 acquires wait ~40ms total.
        val limiter = RateLimiter(permitsPerSecond = 100.0)
        val elapsed = measureTimeMillis { repeat(5) { limiter.acquire() } }
        assertTrue(elapsed >= 25, "expected smoothing to take >= 25ms, was ${elapsed}ms")
    }
}
