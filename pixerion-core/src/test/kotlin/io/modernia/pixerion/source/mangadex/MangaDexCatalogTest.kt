package io.modernia.pixerion.source.mangadex

import io.modernia.pixerion.domain.BookId
import io.modernia.pixerion.domain.CatalogException
import io.modernia.pixerion.domain.DownloadEvent
import io.modernia.pixerion.domain.SourceRef
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MangaDexCatalogTest {
    private lateinit var server: MockWebServer
    private lateinit var catalog: MangaDexCatalog

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        catalog = MangaDexCatalog(baseUrl = server.url("/"))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `the no-arg constructor wires a client for the live MangaDex endpoint`() {
        // Construction only — the default client is built lazily and issues no request here.
        assertEquals(MangaDexCatalog.SCHEME, "mangadex")
        assertNotNull(MangaDexCatalog())
    }

    @Test
    fun `search maps manga onto domain books`() =
        runTest {
            server.enqueue(MockResponse().setBody(SEARCH_BODY))

            val books = catalog.search(mapOf("title" to "berserk"))

            assertEquals(1, books.size)
            val book = books.single()
            assertEquals(BookId(MANGA_ID), book.id)
            assertEquals(SourceRef(MangaDexCatalog.SCHEME, MANGA_ID), book.ref)
            assertEquals("Berserk", book.title)
            assertEquals("Guts wields a giant sword.", book.synopsis)

            // The query map is forwarded as query parameters to GET /manga.
            val request = server.takeRequest()
            assertEquals("/manga?title=berserk", request.path)
        }

    @Test
    fun `search returns empty list when there are no matches`() =
        runTest {
            server.enqueue(MockResponse().setBody(EMPTY_SEARCH_BODY))

            assertTrue(catalog.search(mapOf("title" to "nothing")).isEmpty())
        }

    @Test
    fun `find resolves a source-scoped reference`() =
        runTest {
            server.enqueue(MockResponse().setBody(SINGLE_BODY))

            val book = catalog.find(SourceRef(MangaDexCatalog.SCHEME, MANGA_ID))

            assertEquals("Berserk", book?.title)
            assertEquals("/manga/$MANGA_ID", server.takeRequest().path)
        }

    @Test
    fun `find resolves a portable book id minted by this adapter`() =
        runTest {
            server.enqueue(MockResponse().setBody(SINGLE_BODY))

            val book = catalog.find(BookId(MANGA_ID))

            assertEquals(BookId(MANGA_ID), book?.id)
        }

    @Test
    fun `find returns null for a reference scheme this adapter does not own`() =
        runTest {
            val book = catalog.find(SourceRef("google-books", "abc"))

            assertNull(book)
            // No request should have been issued for an unresolvable reference.
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `find returns null when the book does not exist`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))

            assertNull(catalog.find(SourceRef(MangaDexCatalog.SCHEME, MANGA_ID)))
        }

    @Test
    fun `unreachable source surfaces as a CatalogException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503))

            assertFailsWith<CatalogException> {
                catalog.find(SourceRef(MangaDexCatalog.SCHEME, MANGA_ID))
            }
        }

    @Test
    fun `malformed payload surfaces as a CatalogException`() =
        runTest {
            server.enqueue(MockResponse().setBody("not json"))

            assertFailsWith<CatalogException> {
                catalog.search(mapOf("title" to "berserk"))
            }
        }

    @Test
    fun `title falls back to a non-english language when english is absent`() =
        runTest {
            server.enqueue(MockResponse().setBody(JAPANESE_ONLY_BODY))

            assertEquals("ベルセルク", catalog.find(BookId(MANGA_ID))?.title)
        }

    @Test
    fun `download emits a manifest, chapter, and page and fetches image bytes lazily`() =
        runTest {
            val image = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
            server.enqueue(MockResponse().setBody(FEED_BODY)) // chapter feed
            server.enqueue(MockResponse().setBody(atHomeBody(server.url("/").toString().trimEnd('/'))))
            server.enqueue(MockResponse().setBody(Buffer().write(image))) // the page image

            val events = catalog.download(SourceRef(MangaDexCatalog.SCHEME, MANGA_ID)).toList()

            // The structure the source knows: one chapter of one page, manifest first.
            assertEquals(DownloadEvent.Manifest(1), events.first())
            val started = events.filterIsInstance<DownloadEvent.ChapterStarted>().single()
            assertEquals(CHAPTER_ID, started.chapterId)
            assertEquals("1", started.label)
            assertEquals(1, started.pages)
            val pageEvent = events.filterIsInstance<DownloadEvent.PageReady>().single()
            assertEquals(started.chapterId, pageEvent.chapterId)
            val page = pageEvent.page
            assertEquals("1", page.chapter)
            assertEquals(1, page.number)
            assertEquals("p1.png", page.filename)

            // Collecting the flow walked the feed + at-home only; bytes are still lazy.
            assertEquals(2, server.requestCount)
            assertContentEquals(image, page.bytes())
            assertEquals(3, server.requestCount)
        }

    @Test
    fun `download keeps one version per chapter when several groups uploaded it`() =
        runTest {
            server.enqueue(MockResponse().setBody(DUPLICATE_FEED_BODY))
            // One at-home lookup per surviving chapter; bodies are identical so the
            // parallel resolution order doesn't matter.
            val atHome = atHomeBody(server.url("/").toString().trimEnd('/'))
            server.enqueue(MockResponse().setBody(atHome))
            server.enqueue(MockResponse().setBody(atHome))

            val events = catalog.download(SourceRef(MangaDexCatalog.SCHEME, MANGA_ID)).toList()

            // Three uploads, but only two distinct chapter labels ("1" twice, "2" once).
            assertEquals(DownloadEvent.Manifest(2), events.first())
            val started = events.filterIsInstance<DownloadEvent.ChapterStarted>()
            // The first upload of chapter 1 wins; the duplicate is never resolved.
            assertEquals(setOf(CHAPTER_ID, SECOND_CHAPTER_ID), started.map { it.chapterId }.toSet())
            assertEquals(3, server.requestCount) // feed + 2 at-home, none for the duplicate
        }

    @Test
    fun `download is empty for a reference this adapter cannot resolve`() =
        runTest {
            val events = catalog.download(SourceRef("google-books", MANGA_ID)).toList()

            assertTrue(events.isEmpty())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `download of a missing manga is an empty flow, not a zero-chapter manifest`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404)) // the feed: no such manga

            val events = catalog.download(SourceRef(MangaDexCatalog.SCHEME, MANGA_ID)).toList()

            assertTrue(events.isEmpty())
        }

    @Test
    fun `a non-UUID reference resolves to null instead of a spurious failure`() =
        runTest {
            // A foreign BookId (or a typo) can never name a MangaDex book; issuing the
            // request would draw a 400 and surface as a CatalogException.
            assertNull(catalog.find(BookId("gutenberg-123")))
            assertNull(catalog.find(SourceRef(MangaDexCatalog.SCHEME, "not-a-uuid")))
            assertTrue(catalog.download(BookId("gutenberg-123")).toList().isEmpty())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `download surfaces an unreachable image server as a CatalogException`() =
        runTest {
            server.enqueue(MockResponse().setBody(FEED_BODY))
            server.enqueue(MockResponse().setResponseCode(503)) // at-home server down

            assertFailsWith<CatalogException> {
                catalog.download(SourceRef(MangaDexCatalog.SCHEME, MANGA_ID)).toList()
            }
        }

    private companion object {
        const val MANGA_ID = "801513ba-a712-498c-8f57-cae55b38cc92"
        const val CHAPTER_ID = "11111111-2222-3333-4444-555555555555"
        const val DUPLICATE_CHAPTER_ID = "66666666-7777-8888-9999-000000000000"
        const val SECOND_CHAPTER_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

        val FEED_BODY =
            """
            {
              "result": "ok",
              "data": [
                {
                  "id": "$CHAPTER_ID",
                  "attributes": { "chapter": "1", "translatedLanguage": "en", "pages": 1 }
                }
              ],
              "limit": 100, "offset": 0, "total": 1
            }
            """.trimIndent()

        /** Chapter 1 uploaded by two groups (distinct ids, same label) plus a chapter 2. */
        val DUPLICATE_FEED_BODY =
            """
            {
              "result": "ok",
              "data": [
                {
                  "id": "$CHAPTER_ID",
                  "attributes": { "chapter": "1", "translatedLanguage": "en", "pages": 1 }
                },
                {
                  "id": "$DUPLICATE_CHAPTER_ID",
                  "attributes": { "chapter": "1", "translatedLanguage": "en", "pages": 1 }
                },
                {
                  "id": "$SECOND_CHAPTER_ID",
                  "attributes": { "chapter": "2", "translatedLanguage": "en", "pages": 1 }
                }
              ],
              "limit": 100, "offset": 0, "total": 3
            }
            """.trimIndent()

        fun atHomeBody(baseUrl: String) =
            """
            {
              "result": "ok",
              "baseUrl": "$baseUrl",
              "chapter": { "hash": "abchash", "data": ["p1.png"], "dataSaver": [] }
            }
            """.trimIndent()

        val SEARCH_BODY =
            """
            {
              "result": "ok",
              "response": "collection",
              "data": [
                {
                  "id": "$MANGA_ID",
                  "type": "manga",
                  "attributes": {
                    "title": { "en": "Berserk" },
                    "description": { "en": "Guts wields a giant sword." }
                  }
                }
              ],
              "limit": 10, "offset": 0, "total": 1
            }
            """.trimIndent()

        val EMPTY_SEARCH_BODY =
            """
            { "result": "ok", "response": "collection", "data": [], "limit": 10, "offset": 0, "total": 0 }
            """.trimIndent()

        val SINGLE_BODY =
            """
            {
              "result": "ok",
              "response": "entity",
              "data": {
                "id": "$MANGA_ID",
                "type": "manga",
                "attributes": {
                  "title": { "en": "Berserk" },
                  "description": { "en": "Guts wields a giant sword." }
                }
              }
            }
            """.trimIndent()

        val JAPANESE_ONLY_BODY =
            """
            {
              "result": "ok",
              "response": "entity",
              "data": {
                "id": "$MANGA_ID",
                "type": "manga",
                "attributes": { "title": { "ja": "ベルセルク" }, "description": {} }
              }
            }
            """.trimIndent()
    }
}
