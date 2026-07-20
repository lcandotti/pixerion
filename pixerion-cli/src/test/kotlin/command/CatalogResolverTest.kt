package command

import io.modernia.pixerion.domain.SourceRef
import io.modernia.pixerion.mangadex.MangaDexCatalog
import org.junit.jupiter.api.Test
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
    fun `catalogFor resolves the known mangadex source`() {
        assertIs<MangaDexCatalog>(catalogFor(MangaDexCatalog.SCHEME))
    }

    @Test
    fun `catalogFor reports an unknown source and returns null`() {
        var resolved: Any? = "unset"
        val captured = captureOutput { resolved = catalogFor("nope"); if (resolved == null) 2 else 0 }
        assertEquals(2, captured.code)
        assertNull(resolved)
        assertEquals(true, captured.err.contains("Unknown source"))
    }
}
