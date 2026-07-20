package io.modernia.pixerion.mangadex

import io.modernia.pixerion.domain.Catalog
import io.modernia.pixerion.domain.CatalogException
import io.modernia.pixerion.mangadex.MangaDexClient.Companion.MAX_REQUESTS_PER_HOST
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resumeWithException

/**
 * Thin transport over the MangaDex REST API.
 *
 * Owns everything HTTP and JSON: it issues requests, bridges OkHttp's async
 * callbacks onto coroutines, and decodes responses into [MangaDexDto] types. It
 * deliberately does *not* know about domain types — that mapping belongs to
 * [MangaDexCatalog].
 *
 * Failure policy mirrors the [Catalog] contract's distinction between
 * "absent" and "unreachable":
 *  - a `404` is surfaced as `null` (the resource simply does not exist);
 *  - any other transport problem (connection failure, timeout, non-success
 *    status, malformed body) is raised as a [CatalogException].
 *
 * @param baseUrl root of the MangaDex API; defaults to the public endpoint.
 * @param httpClient the OkHttp client to use; inject a configured one (timeouts,
 *   interceptors) or a test client pointed at a stub server.
 * @param json the JSON codec; defaults to a lenient, unknown-key-tolerant config.
 */
internal class MangaDexClient(
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val httpClient: OkHttpClient = DEFAULT_HTTP_CLIENT,
    private val json: Json = DEFAULT_JSON,
    // Separate from [httpClient] so MangaDex@Home reports (best-effort telemetry) are
    // neither rate-limited nor retried — they must not compete with image fetches.
    private val reportClient: OkHttpClient = DEFAULT_REPORT_CLIENT,
) {
    /** Searches manga via `GET /manga`, forwarding [query] as query parameters. */
    suspend fun searchManga(query: Map<String, String>): List<MangaData> {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment("manga")
                .apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }
                .build()
        // A list endpoint has no "not found"; treat a missing body as empty.
        val body = fetch(url) ?: return emptyList()
        return decode<MangaListResponse>(body, url).data
    }

    /** Fetches a single manga via `GET /manga/{id}`, or `null` if it does not exist. */
    suspend fun getManga(id: String): MangaData? {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment("manga")
                .addPathSegment(id)
                .build()
        val body = fetch(url) ?: return null
        return decode<MangaResponse>(body, url).data
    }

    /**
     * Returns all of a manga's [language] chapters, walking the paginated feed and
     * ordering them by ascending chapter number (unnumbered chapters sort last) —
     * or `null` if the manga itself does not exist. The distinction matters: an
     * existing manga with no chapters is an empty list, a missing manga is `null`.
     */
    suspend fun chapters(
        mangaId: String,
        language: String,
    ): List<ChapterData>? {
        val all = mutableListOf<ChapterData>()
        var offset = 0
        while (true) {
            val page = chapterFeed(mangaId, language, offset) ?: return null
            all += page.data
            offset += page.data.size
            if (page.data.isEmpty() || offset >= page.total) break
        }
        return all.sortedBy { it.attributes.chapter?.toDoubleOrNull() ?: Double.MAX_VALUE }
    }

    /**
     * Fetches one page of a manga's chapter feed via `GET /manga/{id}/feed`,
     * restricted to [language] and ordered by ascending chapter number. `null`
     * when the feed 404s, i.e. the manga does not exist.
     */
    private suspend fun chapterFeed(
        mangaId: String,
        language: String,
        offset: Int,
    ): ChapterListResponse? {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment("manga")
                .addPathSegment(mangaId)
                .addPathSegment("feed")
                .addQueryParameter("translatedLanguage[]", language)
                .addQueryParameter("order[chapter]", "asc")
                .addQueryParameter("limit", FEED_PAGE_SIZE.toString())
                .addQueryParameter("offset", offset.toString())
                .build()
        val body = fetch(url) ?: return null
        return decode<ChapterListResponse>(body, url)
    }

    /** Resolves a chapter's image server and page list via `GET /at-home/server/{id}`. */
    suspend fun atHomeServer(chapterId: String): AtHomeResponse {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment("at-home")
                .addPathSegment("server")
                .addPathSegment(chapterId)
                .build()
        val body = fetch(url) ?: throw CatalogException("MangaDex has no image server for chapter $chapterId")
        return decode<AtHomeResponse>(body, url)
    }

    /**
     * Downloads the raw bytes of an image at an absolute [url] (an at-home page),
     * reporting the outcome back to MangaDex@Home (see [reportAtHome]).
     */
    suspend fun imageBytes(url: String): ByteArray {
        val request =
            Request
                .Builder()
                .url(url)
                .header(USER_AGENT_HEADER, USER_AGENT)
                .build()
        val startNanos = System.nanoTime()
        await(request).use { response ->
            val cached = response.header("X-Cache")?.startsWith("HIT") == true
            if (!response.isSuccessful) {
                reportAtHome(
                    url,
                    success = false,
                    cached = cached,
                    bytes = 0,
                    durationMillis = elapsedMillis(startNanos),
                )
                throw CatalogException("MangaDex returned HTTP ${response.code} for image $url")
            }
            val bytes = response.body?.bytes()
            val durationMillis = elapsedMillis(startNanos)
            if (bytes == null) {
                reportAtHome(url, success = false, cached = cached, bytes = 0, durationMillis = durationMillis)
                throw CatalogException("MangaDex returned an empty image body for $url")
            }
            reportAtHome(url, success = true, cached = cached, bytes = bytes.size, durationMillis = durationMillis)
            return bytes
        }
    }

    private fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    /**
     * Best-effort MangaDex@Home health report for an image fetch. Sent only for
     * `*.mangadex.network` nodes — the canonical `uploads.mangadex.org` server (and
     * anything else) is skipped, per the spec. Fire-and-forget on [reportClient] with
     * the outcome ignored: telemetry must never slow down or fail a download.
     */
    internal fun reportAtHome(
        url: String,
        success: Boolean,
        cached: Boolean,
        bytes: Int,
        durationMillis: Long,
    ) {
        val host = url.toHttpUrlOrNull()?.host ?: return
        if (!host.endsWith(".mangadex.network")) return

        val payload =
            json.encodeToString(
                AtHomeReport(url = url, success = success, cached = cached, bytes = bytes, duration = durationMillis),
            )
        val request =
            Request
                .Builder()
                .url(
                    baseUrl
                        .newBuilder()
                        .addPathSegment("at-home")
                        .addPathSegment("report")
                        .build(),
                ).header(USER_AGENT_HEADER, USER_AGENT)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        reportClient.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) = Unit

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) = response.close()
            },
        )
    }

    /**
     * Performs a GET, returning the response body for a 2xx, `null` for a 404,
     * and raising [CatalogException] for anything else.
     */
    private suspend fun fetch(url: HttpUrl): String? {
        val request =
            Request
                .Builder()
                .url(url)
                .header(USER_AGENT_HEADER, USER_AGENT)
                .header("Accept", "application/json")
                .build()
        await(request).use { response ->
            return when {
                response.isSuccessful ->
                    response.body?.string()
                        ?: throw CatalogException("MangaDex returned an empty body for $url")

                response.code == 404 -> null
                else -> throw CatalogException("MangaDex returned HTTP ${response.code} for $url")
            }
        }
    }

    /** Suspending bridge over OkHttp's callback-based [Call.enqueue]. */
    private suspend fun await(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(
                            CatalogException("MangaDex request failed: ${request.url}", e),
                        )
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        // The onCancellation handler closes the response when the coroutine is
                        // cancelled after OkHttp delivered it but before the resumption runs —
                        // otherwise the body (and its pooled connection) would leak.
                        continuation.resume(response) { _, resource, _ -> resource.close() }
                    }
                },
            )
        }

    private inline fun <reified T> decode(
        body: String,
        url: HttpUrl,
    ): T =
        try {
            json.decodeFromString<T>(body)
        } catch (e: SerializationException) {
            throw CatalogException("Could not parse MangaDex response from $url", e)
        }

    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://api.mangadex.org".toHttpUrl()

        /** Page size for chapter-feed pagination (MangaDex caps the feed at 500). */
        private const val FEED_PAGE_SIZE = 100

        /**
         * MangaDex asks clients to identify themselves with a descriptive User-Agent
         * (a bare/default one invites stricter handling under their abuse enforcement).
         * A contact URL can be appended once the public repo location is finalized.
         */
        private const val USER_AGENT_HEADER = "User-Agent"
        private const val USER_AGENT = "pixerion/0.1.0"

        /**
         * Concurrent requests OkHttp allows per host. OkHttp's default is 5, which
         * would silently throttle a parallel download (all images come from the
         * same at-home host); raise it so download concurrency isn't capped here.
         */
        private const val MAX_REQUESTS_PER_HOST = 8

        /**
         * Requests/sec we smooth *all* MangaDex traffic to. The API enforces ~5/sec
         * per IP, and the MangaDex@Home image servers apply even stricter limits — so
         * a single global governor at this rate paces both the API calls and the image
         * fetches (which otherwise burst out across [MAX_REQUESTS_PER_HOST] and trip the
         * node's limit). See [RateLimitInterceptor]; [RetryInterceptor] mops up the rest.
         */
        private const val MAX_REQUESTS_PER_SECOND = 5.0

        /**
         * Derives a client from [client] with the MangaDex transport policy applied:
         * 429 retry, request smoothing, and the raised per-host cap. The policy is
         * part of "talking to MangaDex correctly", so every client used against the
         * API must carry it — including caller-supplied ones (proxies, mirrors,
         * custom timeouts), which is why this augments rather than replaces.
         */
        internal fun withDefaultPolicy(client: OkHttpClient): OkHttpClient =
            client
                .newBuilder()
                .dispatcher(Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST })
                // Order matters: the retry interceptor is outermost, so each retry it
                // issues re-enters the rate limiter below (rather than bypassing it).
                .addInterceptor(RetryInterceptor())
                .addInterceptor(RateLimitInterceptor(RateLimiter(MAX_REQUESTS_PER_SECOND)))
                .build()

        /** Shared, connection-pooling client tuned for parallel downloads. */
        private val DEFAULT_HTTP_CLIENT: OkHttpClient by lazy { withDefaultPolicy(OkHttpClient()) }

        /**
         * Client for fire-and-forget MangaDex@Home reports — deliberately plain (no
         * rate limiter, no retry) so telemetry stays off the download's critical path.
         */
        private val DEFAULT_REPORT_CLIENT: OkHttpClient = OkHttpClient()

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private val DEFAULT_JSON =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
    }
}

/**
 * Retries a request on HTTP 429 (rate limited), honouring the server's `Retry-After`
 * / `X-RateLimit-Retry-After` hint when present and otherwise backing off
 * exponentially. Only 429 is retried: other non-success codes (e.g. 503) are passed
 * through unchanged so [MangaDexClient]'s "unreachable" failure policy still applies.
 *
 * Runs on an OkHttp dispatcher thread, so blocking here (rather than on a coroutine)
 * to wait out the back-off is intentional and harmless.
 */
internal class RetryInterceptor(
    private val maxRetries: Int = 4,
    private val baseBackoffMillis: Long = 500,
    private val maxBackoffMillis: Long = 60_000,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var response = chain.proceed(chain.request())
        var attempt = 0
        while (response.code == HTTP_TOO_MANY_REQUESTS && attempt < maxRetries) {
            val backoff =
                (retryAfterMillis(response) ?: (baseBackoffMillis shl attempt))
                    .coerceIn(0, maxBackoffMillis)
            response.close()
            sleep(backoff)
            attempt++
            response = chain.proceed(chain.request())
        }
        return response
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while backing off before retrying a rate-limited request", e)
        }
    }

    /**
     * Milliseconds to wait per the response's rate-limit hints, or `null` if absent.
     * `Retry-After` is either delta-seconds or an HTTP date; MangaDex also sends
     * `X-RateLimit-Retry-After` as an epoch-seconds timestamp.
     */
    private fun retryAfterMillis(response: Response): Long? {
        response.header("Retry-After")?.trim()?.let { value ->
            value.toLongOrNull()?.let { return it * 1000 }
            runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
                .getOrNull()
                ?.let { return it.toEpochMilli() - System.currentTimeMillis() }
        }
        response.header("X-RateLimit-Retry-After")?.trim()?.toLongOrNull()?.let { epochSeconds ->
            return epochSeconds * 1000 - System.currentTimeMillis()
        }
        return null
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}

/**
 * Paces every request through [limiter] — both API calls and image-CDN fetches,
 * since the image servers are rate-limited at least as strictly as the API. Placed
 * *below* [RetryInterceptor] so a retried request also waits for a fresh permit.
 */
internal class RateLimitInterceptor(
    private val limiter: RateLimiter,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        limiter.acquire()
        return chain.proceed(chain.request())
    }
}

/**
 * A smooth permit limiter: it spaces permits one fixed interval apart so callers
 * never exceed [permitsPerSecond]. Smoothing (rather than allowing a burst then
 * stalling) keeps us comfortably under MangaDex's per-IP cap. Thread-safe;
 * [acquire] blocks the calling thread until its slot is due.
 */
internal class RateLimiter(
    permitsPerSecond: Double,
) {
    private val intervalNanos = (1_000_000_000.0 / permitsPerSecond).toLong()
    private val lock = Any()
    private var nextSlotNanos = System.nanoTime()

    fun acquire() {
        val waitNanos =
            synchronized(lock) {
                val now = System.nanoTime()
                val slot = maxOf(now, nextSlotNanos)
                nextSlotNanos = slot + intervalNanos
                slot - now
            }
        if (waitNanos > 0) {
            Thread.sleep(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())
        }
    }
}
