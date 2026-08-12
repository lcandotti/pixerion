package io.modernia.pixerion.source.mangadex

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Covers the MangaDex wire DTOs directly. The production mapping only reads a
 * handful of fields (mostly `.data`), so envelope metadata (`result`/`limit`/
 * `offset`/`total`) and chapter/image details that model the wire format are
 * otherwise never exercised. Each test decodes a representative payload with the
 * same lenient config the client uses — validating the serialization contract and
 * every generated getter — and reconstructs the tree via the primary constructors
 * (which JSON decoding, using a synthetic constructor, would otherwise skip).
 */
class MangaDexDtoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `MangaListResponse decodes envelope metadata and data`() {
        val body =
            """
            {"result":"ok","limit":10,"offset":20,"total":42,
             "data":[{"id":"m-1","type":"manga",
                      "attributes":{"title":{"en":"Berserk"},"description":{"en":"D"}}}]}
            """.trimIndent()

        val decoded = json.decodeFromString<MangaListResponse>(body)

        assertEquals("ok", decoded.result)
        assertEquals(10, decoded.limit)
        assertEquals(20, decoded.offset)
        assertEquals(42, decoded.total)
        val manga = decoded.data.single()
        assertEquals("m-1", manga.id)
        assertEquals("manga", manga.type)

        val expected =
            MangaListResponse(
                result = "ok",
                data =
                    listOf(
                        MangaData(
                            id = "m-1",
                            type = "manga",
                            attributes =
                                MangaAttributes(
                                    title = mapOf("en" to "Berserk"),
                                    description = mapOf("en" to "D"),
                                ),
                        ),
                    ),
                limit = 10,
                offset = 20,
                total = 42,
            )
        assertEquals(expected, decoded)
    }

    @Test
    fun `MangaResponse decodes result and single manga`() {
        val body = """{"result":"ok","data":{"id":"m-2","type":"manga","attributes":{}}}"""

        val decoded = json.decodeFromString<MangaResponse>(body)

        assertEquals("ok", decoded.result)
        assertEquals("m-2", decoded.data.id)
        assertEquals("manga", decoded.data.type)
        assertEquals(MangaResponse(result = "ok", data = MangaData(id = "m-2")), decoded)
    }

    @Test
    fun `ChapterListResponse decodes chapter attributes`() {
        val body =
            """
            {"result":"ok","limit":100,"offset":0,"total":1,
             "data":[{"id":"c-1",
                      "attributes":{"chapter":"12.5","volume":"2","translatedLanguage":"en","pages":20}}]}
            """.trimIndent()

        val decoded = json.decodeFromString<ChapterListResponse>(body)

        assertEquals("ok", decoded.result)
        assertEquals(100, decoded.limit)
        assertEquals(0, decoded.offset)
        assertEquals(1, decoded.total)
        val attributes = decoded.data.single().attributes
        assertEquals("12.5", attributes.chapter)
        assertEquals("2", attributes.volume)
        assertEquals("en", attributes.translatedLanguage)
        assertEquals(20, attributes.pages)

        val expected = ChapterAttributes(chapter = "12.5", volume = "2", translatedLanguage = "en", pages = 20)
        assertEquals(expected, attributes)
    }

    @Test
    fun `AtHomeResponse decodes image-server details`() {
        val body =
            """
            {"result":"ok","baseUrl":"https://node.example",
             "chapter":{"hash":"abc","data":["p1.png"],"dataSaver":["p1s.png"]}}
            """.trimIndent()

        val decoded = json.decodeFromString<AtHomeResponse>(body)

        assertEquals("ok", decoded.result)
        assertEquals("https://node.example", decoded.baseUrl)
        assertEquals("abc", decoded.chapter.hash)
        assertEquals(listOf("p1.png"), decoded.chapter.data)
        assertEquals(listOf("p1s.png"), decoded.chapter.dataSaver)

        val expected =
            AtHomeResponse(
                result = "ok",
                baseUrl = "https://node.example",
                chapter = AtHomeChapter(hash = "abc", data = listOf("p1.png"), dataSaver = listOf("p1s.png")),
            )
        assertEquals(expected, decoded)
    }

    // Encoding then decoding must reproduce the original. Beyond asserting serialization
    // symmetry, this exercises the generated serializers (the `write$Self` path), which
    // the decode-only cases above never reach because the client only ever reads these.
    @Test
    fun `DTOs round-trip through encode and decode`() {
        val manga =
            MangaData(
                id = "m-1",
                type = "manga",
                attributes = MangaAttributes(title = mapOf("en" to "Berserk"), description = mapOf("en" to "D")),
            )
        assertRoundTrips(MangaListResponse(result = "ok", data = listOf(manga), limit = 10, offset = 20, total = 42))
        assertRoundTrips(MangaResponse(result = "ok", data = manga))
        assertRoundTrips(
            ChapterListResponse(
                result = "ok",
                data =
                    listOf(
                        ChapterData(
                            id = "c-1",
                            attributes = ChapterAttributes(chapter = "12.5", volume = "2", translatedLanguage = "en", pages = 20),
                        ),
                    ),
                limit = 100,
                total = 1,
            ),
        )
        assertRoundTrips(
            AtHomeResponse(
                result = "ok",
                baseUrl = "https://node.example",
                chapter = AtHomeChapter(hash = "abc", data = listOf("p1.png"), dataSaver = listOf("p1s.png")),
            ),
        )
    }

    private inline fun <reified T> assertRoundTrips(value: T) {
        assertEquals(value, json.decodeFromString<T>(json.encodeToString(value)))
    }
}
