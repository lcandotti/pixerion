package command

import io.modernia.pixerion.domain.SourceRef
import io.modernia.pixerion.source.CatalogRegistry
import io.modernia.pixerion.source.mangadex.MangaDexCatalog
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Covers the source registry and reference parsing shared across commands. */
class CatalogResolverTest {
    @Test
    fun `parseRef scopes a bare token to the default source`() {
        val ref = parseRef("abc123", defaultSource = "mangadex")
        assertEquals(SourceRef("mangadex", "abc123"), ref)
    }

    @Test
    fun `parseRef splits an explicit scheme-id pair`() {
        val ref = parseRef("mangadex:abc123", defaultSource = "other")
        assertEquals(SourceRef("mangadex", "abc123"), ref)
    }

    @Test
    fun `parseRef treats a leading colon as a bare token`() {
        // indexOf(':') == 0 is not a scheme separator, so the whole string is the id.
        val ref = parseRef(":weird", defaultSource = "mangadex")
        assertEquals(SourceRef("mangadex", ":weird"), ref)
    }

    @Test
    fun `the registry resolves the known mangadex source`() {
        assertIs<MangaDexCatalog>(CatalogRegistry[MangaDexCatalog.SCHEME])
    }

    @Test
    fun `the registry returns null for a source no adapter owns`() {
        assertNull(CatalogRegistry["nope"])
    }

    @Test
    fun `reportUnknownSource lists the schemes the registry resolves`() {
        val captured = captureOutput { reportUnknownSource("nope") }
        assertEquals(2, captured.code)
        assertContains(captured.err, "Unknown source")
        // The hint is rendered from the registry, so it cannot drift from what resolves.
        CatalogRegistry.known.forEach { assertContains(captured.err, it) }
    }
}
