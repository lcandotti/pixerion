package io.modernia.pixerion.source.mangadex

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtHomeReportTest {
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

    // baseUrl points the report POST at the mock server; reportClient defaults to the
    // plain fire-and-forget client, which still reaches the mock over localhost.
    private fun client() = MangaDexClient(baseUrl = server.url("/"))

    @Test
    fun `reports a MangaDex@Home image fetch to the report endpoint`() {
        server.enqueue(MockResponse()) // 200 for the (ignored) report response

        client().reportAtHome(
            url = "https://abc123.mangadex.network/data/hash/p1.jpg",
            success = true,
            cached = true,
            bytes = 4242,
            durationMillis = 87,
        )

        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("POST", request.method)
        assertEquals("/at-home/report", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"url\":\"https://abc123.mangadex.network/data/hash/p1.jpg\""), body)
        assertTrue(body.contains("\"success\":true"), body)
        assertTrue(body.contains("\"cached\":true"), body)
        assertTrue(body.contains("\"bytes\":4242"), body)
        assertTrue(body.contains("\"duration\":87"), body)
    }

    @Test
    fun `consumes and closes a successful at-home report response`() {
        // A dedicated report client whose in-flight calls we can await, so the async
        // onResponse callback (which closes the response body) actually runs before the
        // assertion — otherwise the fire-and-forget POST may outlive the test.
        val reportClient = OkHttpClient()
        server.enqueue(MockResponse()) // 200 for the report POST
        val client = MangaDexClient(baseUrl = server.url("/"), reportClient = reportClient)

        client.reportAtHome(
            url = "https://abc123.mangadex.network/data/hash/p1.jpg",
            success = true,
            cached = true,
            bytes = 4242,
            durationMillis = 87,
        )

        awaitIdle(reportClient)
    }

    @Test
    fun `swallows a failed at-home report without throwing`() {
        // A dedicated report client whose in-flight calls we can await, pointed at a
        // refused port so the fire-and-forget POST fails: the Callback's onFailure must
        // swallow it (best-effort telemetry never disrupts a download).
        val reportClient = OkHttpClient()
        val dead = MockWebServer().apply { start() }
        val deadUrl = dead.url("/")
        dead.shutdown()
        val client = MangaDexClient(baseUrl = deadUrl, reportClient = reportClient)

        client.reportAtHome(
            url = "https://abc123.mangadex.network/data/hash/p1.jpg",
            success = false,
            cached = false,
            bytes = 0,
            durationMillis = 3,
        )

        awaitIdle(reportClient)
    }

    /** Blocks until [client]'s dispatcher has drained, so its async callback has run. */
    private fun awaitIdle(client: OkHttpClient) {
        val dispatcher = client.dispatcher
        var spins = 0
        while (dispatcher.runningCallsCount() + dispatcher.queuedCallsCount() > 0 && spins++ < 200) {
            Thread.sleep(10)
        }
        assertEquals(0, dispatcher.runningCallsCount() + dispatcher.queuedCallsCount())
    }

    @Test
    fun `does not report the canonical uploads server`() {
        client().reportAtHome(
            url = "https://uploads.mangadex.org/data/hash/p1.jpg",
            success = true,
            cached = false,
            bytes = 10,
            durationMillis = 5,
        )

        // No MangaDex@Home node => no report should be sent.
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }
}
