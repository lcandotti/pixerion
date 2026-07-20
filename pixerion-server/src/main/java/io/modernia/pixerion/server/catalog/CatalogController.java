package io.modernia.pixerion.server.catalog;

import io.modernia.pixerion.domain.Book;
import io.modernia.pixerion.domain.CatalogException;
import io.modernia.pixerion.domain.SourceRef;
import io.modernia.pixerion.interop.BlockingCatalog;
import io.modernia.pixerion.interop.Refs;
import io.modernia.pixerion.server.catalog.download.DownloadJob;
import io.modernia.pixerion.server.catalog.download.DownloadJobService;
import io.modernia.pixerion.server.catalog.dto.BookResponse;
import io.modernia.pixerion.server.catalog.dto.JobResponse;
import io.modernia.pixerion.server.catalog.dto.JobStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * HTTP front-end over {@code core}'s {@code Catalog}, mirroring the CLI subcommands.
 * Preserves the contract's absence-vs-failure distinction as HTTP status: a missing
 * book is 404, a source-level {@link CatalogException} is 502, an unknown source 400.
 *
 * <p>Everything here lives under {@code /api} (ADR-0013): the server also serves the
 * embedded SPA, and the prefix is what separates backend routes from client-side ones
 * (any unknown non-API GET falls back to the SPA's {@code index.html}).
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Catalog", description = "Search, books, and download jobs")
// The @ExceptionHandler mappings at the bottom apply to every operation here; springdoc
// merges these class-level responses into each one. Both handlers return text/plain bodies.
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Unknown source"),
        @ApiResponse(responseCode = "502", description = "Source-level failure (timeout, bad payload, non-2xx)"),
})
public class CatalogController {

    private static final Logger log = LoggerFactory.getLogger(CatalogController.class);

    private final CatalogProvider catalogs;
    private final DownloadJobService downloads;

    public CatalogController(CatalogProvider catalogs, DownloadJobService downloads) {
        this.catalogs = catalogs;
        this.downloads = downloads;
    }

    private static BookResponse toResponse(Book book) {
        return new BookResponse(
                Refs.idOf(book),
                Refs.render(book.getRef()),
                book.getTitle(),
                book.getSynopsis());
    }

    /**
     * {@code GET /api/search?q=…&source=…} — discovery by title.
     */
    @Operation(summary = "Search a catalog by title")
    @GetMapping("/search")
    public List<BookResponse> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "mangadex") String source) {
        return resolve(source).search(Map.of("title", q)).stream()
                .map(CatalogController::toResponse)
                .toList();
    }

    /**
     * {@code GET /api/books/{id}?source=…} — fetch one book; 404 if absent.
     */
    @Operation(summary = "Fetch one book")
    @ApiResponse(responseCode = "404", description = "Book not found")
    @GetMapping("/books/{id}")
    public ResponseEntity<BookResponse> find(
            @PathVariable String id,
            @RequestParam(defaultValue = "mangadex") String source) {
        Book book = resolve(source).find(new SourceRef(source, id));
        return book == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(toResponse(book));
    }

    /**
     * {@code POST /api/downloads?id=…&source=…} — start a background download and return
     * {@code 202 Accepted} with a job handle. Absence surfaces asynchronously as the
     * job's {@code NOT_FOUND} status (see {@link #downloadStatus}); an unknown source is
     * still rejected synchronously with 400. Admin-only.
     */
    @Operation(summary = "Start a background download job (admin)")
    @ApiResponse(responseCode = "202", description = "Job accepted; poll its status or subscribe to its events")
    @ApiResponse(responseCode = "403", description = "Requires the ADMIN role")
    @PostMapping("/downloads")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<JobResponse> download(
            @RequestParam String id,
            @RequestParam(defaultValue = "mangadex") String source) {
        BlockingCatalog catalog = resolve(source);
        // resolve() has vetted the source; echo the registry's canonical name (not the raw request
        // param) so the response body carries no request-derived, potentially tainted content.
        String vetted = catalogs.canonicalName(source);
        DownloadJob job = downloads.start(vetted, catalog, new SourceRef(vetted, id));
        return ResponseEntity.accepted().body(new JobResponse(job.id(), job.status().name(), vetted));
    }

    /**
     * {@code GET /api/downloads/{id}} — a download job's status and, once finished, its outcome; 404 if unknown. Admin-only.
     */
    @Operation(summary = "Download job status and outcome (admin)")
    @ApiResponse(responseCode = "404", description = "Unknown job")
    @ApiResponse(responseCode = "403", description = "Requires the ADMIN role")
    @GetMapping("/downloads/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<JobStatusResponse> downloadStatus(@PathVariable String id) {
        DownloadJob job = downloads.find(id);
        return job == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(job.toStatusResponse());
    }

    /**
     * {@code GET /api/downloads/{id}/events} — live progress as Server-Sent Events: periodic
     * {@code state} snapshots, then a terminal {@code completed} or {@code error} event.
     * 404 if the job is unknown. Admin-only; authenticates via the normal Bearer header.
     */
    @Operation(
            summary = "Live download progress as Server-Sent Events (admin)",
            description = "A text/event-stream of periodic 'state' snapshots (StateEvent), then one terminal "
                    + "'completed' (DownloadResponse) or 'error' (ErrorEvent) event. See ADR-0011 for the contract.")
    // The bare-media-type 200 suppresses springdoc's default SseEmitter schema, which is noise.
    @ApiResponse(responseCode = "200", description = "Event stream",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE))
    @ApiResponse(responseCode = "404", description = "Unknown job")
    @ApiResponse(responseCode = "403", description = "Requires the ADMIN role")
    @GetMapping(value = "/downloads/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SseEmitter> downloadEvents(@PathVariable String id) {
        SseEmitter emitter = downloads.subscribe(id);
        return emitter == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(emitter);
    }

    private BlockingCatalog resolve(String source) {
        BlockingCatalog catalog = catalogs.catalogFor(source);
        if (catalog == null) {
            throw new UnknownSourceException(source);
        }
        return catalog;
    }

    // Package-private to match UnknownSourceException's own (package-private) scope — Spring
    // discovers and invokes @ExceptionHandler methods reflectively, so public isn't required.
    @ExceptionHandler(UnknownSourceException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String unknownSource(UnknownSourceException e) {
        return e.getMessage();
    }

    @ExceptionHandler(CatalogException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public String sourceUnreachable(CatalogException e) {
        // Adapter messages can embed upstream URLs or payload fragments — log the detail
        // for operators, but only ever hand clients a generic body.
        log.warn("Source-level failure", e);
        return "The upstream source failed";
    }
}
