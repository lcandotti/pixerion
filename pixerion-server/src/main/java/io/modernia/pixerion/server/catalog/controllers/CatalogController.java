package io.modernia.pixerion.server.catalog.controllers;

import io.modernia.pixerion.server.catalog.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reads from the catalog sources, for the frontend to render.
 *
 * <p><b>Read-only by design.</b> A catalog here is an upstream source, not something this
 * application owns, so there is nothing for a client to create, update or delete — only
 * {@code GET} routes exist and no write route should be added. The one seam that does
 * mutate anything (downloading a book to disk) is a separate concern and not part of this
 * controller.
 *
 * <p><b>Security.</b> These paths sit under {@code /api/**}, which {@code SecurityConfig}
 * already gates with {@code .authenticated()} — so a valid bearer token is required and no
 * SecurityConfig change is needed to make that true. {@code @SecurityRequirement} below is
 * what tells springdoc the same thing, so the Scalar console offers the "Authorize" box
 * instead of firing unauthenticated requests that come back 401. It references the
 * {@code bearer-jwt} scheme declared in {@code OpenApiConfig}.
 *
 * <p><b>Status codes</b>, mirroring core's absence-vs-failure contract:
 * <ul>
 *   <li>{@code 200} with an empty array — searched fine, nothing matched.</li>
 *   <li>{@code 404} — that reference resolves to no book.</li>
 *   <li>{@code 400} — unknown {@code source}, or a blank {@code title}.</li>
 *   <li>{@code 502} — the upstream source was unreachable or unintelligible.</li>
 * </ul>
 * The last two are mapped in {@code CatalogExceptionHandler}; the 404 is this class's job.
 */
@RestController
@RequestMapping("/api/catalog")
@Validated
@Tag(name = "Catalog", description = "Read books from a catalog source")
@SecurityRequirement(name = "bearer-jwt")
public class CatalogController {

    /** Queried when the caller does not name one. Matches the CLI's {@code --source} default. */
    private static final String DEFAULT_SOURCE = "mangadex";

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    /**
     * {@code GET /api/catalog/search?title=…&source=…} — discovery by title.
     *
     * <p>Note that no-matches is a 200 with {@code []}, not a 404. An empty result is a
     * successful search that found nothing, and the frontend renders "no results" from it;
     * a 404 would say the <em>endpoint</em> does not exist.
     *
     * <p>{@code @NotBlank} is enforced because the class carries {@code @Validated} —
     * without it, constraints on method parameters are ignored entirely and a blank title
     * would sail through to the source. The violation surfaces as
     * {@code HandlerMethodValidationException}, which Spring MVC already renders as a 400.
     */
    @GetMapping("/search")
    @Operation(summary = "Search a source by title",
            description = "Returns the books whose title matches. An empty array means no matches, not a failure.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matching books (possibly none)"),
            @ApiResponse(responseCode = "400", description = "Blank title, or unknown source",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token",
                    content = @Content),
            @ApiResponse(responseCode = "502", description = "The source could not be reached or understood",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<BookResponse> search(
            @Parameter(description = "Title text to search for", example = "berserk", required = true)
            @RequestParam @NotBlank String title,

            @Parameter(description = "Catalog source to query", example = DEFAULT_SOURCE)
            @RequestParam(defaultValue = DEFAULT_SOURCE) String source) {
        // TODO: catalog.searchByTitle(source, title) -> map each Book through BookResponse.from
        throw new UnsupportedOperationException("TODO: implement search");
    }

    /**
     * {@code GET /api/catalog/{source}/{id}} — fetch one book by its source-native id.
     *
     * <p>The source is a path segment rather than the {@code "scheme:id"} string the CLI
     * accepts: a colon inside a path segment is legal but needs escaping by every client,
     * and two segments keep the route readable and unambiguous.
     *
     * <p>A miss must become a 404 here. {@code CatalogService.find} returns {@code null} for
     * "no such book" — returning that straight out would serialize as a 200 with an empty
     * body, which reads to the frontend as "found, but blank".
     */
    @GetMapping("/{source}/{id}")
    @Operation(summary = "Fetch a book by source id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The book"),
            @ApiResponse(responseCode = "400", description = "Unknown source",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "No book for that reference",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "The source could not be reached or understood",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public BookResponse find(
            @Parameter(description = "Catalog source that issued the id", example = DEFAULT_SOURCE)
            @PathVariable String source,

            @Parameter(description = "Source-native identifier", example = "801513ba-a712-498c-8f57-cae55b38cc92")
            @PathVariable String id) {
        // TODO: catalog.find(source, id); if null -> raise the 404 (see CatalogExceptionHandler
        // for how it is rendered), else BookResponse.from(book).
        throw new UnsupportedOperationException("TODO: implement find");
    }
}
