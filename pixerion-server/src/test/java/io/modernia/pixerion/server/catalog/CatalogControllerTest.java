package io.modernia.pixerion.server.catalog;

import io.modernia.pixerion.domain.Book;
import io.modernia.pixerion.domain.BookRef;
import io.modernia.pixerion.domain.Catalog;
import io.modernia.pixerion.domain.CatalogException;
import io.modernia.pixerion.domain.DownloadEvent;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

/**
 * Drives the catalog endpoints against a fake {@link Catalog} — parse → service → render —
 * with no network involved, the same approach {@code MangaDexCatalogTest} takes with
 * MockWebServer one layer down.
 *
 * <p>{@code @SpringBootTest} rather than {@code @WebMvcTest} to match the auth suites: the
 * real security filter chain has to be in the picture, since "these routes require a token"
 * is half of what is worth asserting here.
 *
 * <p><b>What to cover</b> — the contract that matters is core's absence-vs-failure line, so
 * the pairs below are the point of the suite, not the happy paths:
 * <ul>
 *   <li>search with matches → 200 + array; search with none → 200 + {@code []} (<b>not</b> 404)</li>
 *   <li>find a known id → 200; find an unknown id → 404</li>
 *   <li>a source that throws {@link CatalogException} → 502 (<b>not</b> 404, not 500)</li>
 *   <li>an unregistered {@code source} → 400, listing the known schemes</li>
 *   <li>a blank {@code title} → 400, rejected by {@code @Validated} before the source is touched</li>
 *   <li>no token → 401 on both routes</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Disabled("TODO: enable once CatalogController and CatalogService are implemented")
class CatalogControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CatalogService catalog;

    /**
     * Points the service at a fake source instead of the live registry.
     *
     * <p>Reassigning the field is safe because {@code CatalogService} caches per scheme —
     * use a scheme name no real adapter owns, or the cache may already hold a real adapter
     * from an earlier test in the same context.
     */
    @BeforeEach
    void useFakeSource() {
        // TODO: catalog.sources = scheme -> "fake".equals(scheme) ? new FakeCatalog(...) : null;
    }

    @Test
    void search_with_no_matches_is_200_and_an_empty_array() {
        // TODO
    }

    @Test
    void find_by_id_returns_the_book() {
        // TODO
    }

    @Test
    void find_of_an_unknown_id_is_404() {
        // TODO
    }

    @Test
    void an_unreachable_source_is_502_not_404() {
        // TODO
    }

    @Test
    void an_unregistered_source_is_400() {
        // TODO
    }

    @Test
    void a_blank_title_is_400() {
        // TODO
    }

    @Test
    void the_routes_require_a_token() {
        // TODO
    }

    /**
     * A {@link Catalog} that answers from a fixed list.
     *
     * <p><b>The awkward part, up front.</b> {@code Catalog.find} and {@code Catalog.search}
     * are Kotlin {@code suspend} functions, and implementing one in Java means implementing
     * its <em>compiled</em> signature: an extra trailing {@link Continuation} parameter, and
     * a return type of {@code Object} (the value itself, since returning it directly is what
     * a coroutine that never suspends does). Returning the value and ignoring the
     * continuation is correct here — nothing in this fake suspends.
     *
     * <p>If that proves more friction than it is worth, the alternative is to write the fake
     * in Kotlin under {@code pixerion-core}'s test fixtures, or to test the controller with a
     * mocked {@code CatalogService} instead and cover the interop seam separately.
     */
    static class FakeCatalog implements Catalog {

        private final List<Book> books;

        FakeCatalog(List<Book> books) {
            this.books = books;
        }

        @Override
        public Object find(BookRef ref, Continuation<? super Book> continuation) {
            // TODO: match on the rendered ref (Refs.render) and return the Book, or null.
            return null;
        }

        @Override
        public Object search(Map<String, String> query, Continuation<? super List<Book>> continuation) {
            // TODO: filter `books` on query.get("title"); return an empty list when nothing
            // matches — never throw, that is what CatalogException means.
            return List.of();
        }

        @Override
        public Flow<DownloadEvent> download(BookRef ref) {
            // Not exercised: these endpoints are read-only and never download.
            return FlowKt.emptyFlow();
        }

    }

    /*
     * ------------------------------------------------------------------------------
     * OPEN QUESTION — how do the Book fixtures get built?
     *
     * They cannot be built here. `new Book(...)` does not compile from Java: its first
     * parameter is a BookId, a @JvmInline value class whose constructor is not callable
     * from Java (verified — javac reports "cannot find symbol: constructor BookId(String)").
     * The interop facade only solves the outbound direction: Refs.idOf / Refs.render read
     * an existing Book, and nothing in core mints one for a Java caller.
     *
     * Three ways out, in rough order of how well they fit the architecture:
     *
     *  1. Extend the interop seam. A `Books.of(id, scheme, value, title, synopsis)` factory
     *     in io.modernia.pixerion.interop is the natural counterpart to Refs and keeps the
     *     "Java touches core only through interop" rule (ADR-0007) intact. It is a change to
     *     `core`, not to this module, so it is a separate decision.
     *
     *  2. Write the fake in Kotlin, as a core test fixture the server test depends on.
     *     No production code changes; costs a test-fixtures wiring in the build.
     *
     *  3. Skip the domain entirely here: mock CatalogService with Mockito and assert only
     *     the HTTP mapping, covering the interop seam in a separate, narrower test.
     *
     * Until one is picked, FakeCatalog above has no way to be handed any books.
     * ------------------------------------------------------------------------------
     */
}
