package io.modernia.pixerion.server.catalog.controllers;

import io.modernia.pixerion.server.catalog.services.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

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
 *   <li>{@code 200} with an empty array — the lookup ran fine and matched nothing. This is
 *       the <em>only</em> way absence is reported, whether the caller asked by title or by
 *       id.</li>
 *   <li>{@code 400} — unknown {@code source}, or a query naming neither {@code title} nor
 *       {@code id} (or both).</li>
 *   <li>{@code 502} — the upstream source was unreachable or unintelligible.</li>
 * </ul>
 * Note the absent 404. Books are only ever reached through a collection here, and a filter
 * that matches nothing is a successful empty filter — so the one status that would have to
 * mean "no such book" has no route to come from. 404 from these paths now means only what it
 * says at the HTTP level: no such endpoint. The 400 and 502 are mapped in
 * {@code CatalogExceptionHandler}.
 */
@RestController
@RequestMapping("/api/catalog")
@Validated
@Tag(name = "Catalog", description = "Read books from a catalog source")
@SecurityRequirement(name = "bearer-jwt")
public class CatalogController {

    /**
     * Shown as the {@code source} example in the generated OpenAPI document. Matches the
     * CLI's {@code --source} default — but note it is <em>only</em> an example here. The
     * source is a required path segment, never defaulted: the CLI defaults because a human
     * is typing, whereas every caller of this API is a frontend that already knows which
     * source it is displaying, and silently answering from MangaDex would hide the mistake.
     */
    private static final String DEFAULT_SOURCE = "mangadex";

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    /**
     * {@code GET /api/catalog} — the source names every other route accepts.
     *
     * <p>This is the discovery route for the whole controller: {@code source} is a required
     * segment of every other path here, and a client has no other way to learn which values
     * are legal. The frontend populates its source picker
     * from here rather than hard-coding {@code "mangadex"}, so registering an adapter in
     * {@code CatalogRegistry} is enough to surface it in the UI — the same promise the CLI
     * keeps by rendering its "known:" hint from the registry.
     *
     * <p>A {@link Set}, not a {@code List}: the schemes are a key set with no meaningful
     * order, and nothing downstream may depend on the order they come back in.
     *
     * <p>Always a 200, never a 404 — an empty set would mean "no sources are registered",
     * which is a coherent answer, not a missing resource. There is no 400 either: the route
     * takes no input to get wrong.
     */
    @GetMapping
    @Operation(
            summary = "Get a set of all the available catalogs",
            description = "Returns the all the catalogs source to be used for all the other endpoints")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "All catalogs")})
    public Set<String> list() {
        return catalog.all();
    }

    /**
     * {@code GET /api/catalog/{source}/books?title=…} or {@code ?id=…} — the books of one
     * source, filtered.
     *
     * <p><b>One route, two filters, by design.</b> Looking a book up by id used to be a
     * separate {@code /{source}/{id}} route returning a bare object and 404-ing on a miss.
     * Folding it in here makes id just another way to narrow the same collection, which buys
     * one uniform answer shape: a JSON array, always, and an empty one whenever nothing
     * matched. The frontend renders "no results" from {@code []} without caring which filter
     * produced it, and never has to branch on a status code to tell absence from failure.
     *
     * <p>The cost, stated plainly: a caller can no longer distinguish "this id does not
     * exist" from "this id exists but the filter returned nothing" — because with an id
     * filter those are the same statement. If a 404 for a known-missing book is ever needed
     * (a deep link that should 404 rather than render an empty page, say), that is the
     * reason to bring a dedicated {@code /books/{id}} route back, and {@code /books} is
     * named so it can be added underneath without disturbing this one.
     *
     * <p><b>Exactly one filter.</b> Neither given is a caller who forgot to say what they
     * want; both given is a caller with two ideas about what they want, and picking one
     * silently would hide the bug. Both are 400. This is checked here rather than with
     * {@code @NotBlank}, because a bean constraint can require a single parameter but cannot
     * express a rule <em>between</em> two of them.
     *
     * <p>{@code source} is a path segment, matching {@code /{source}/…} everywhere else in
     * this controller: it names <em>which catalog</em> is being read, which is structure, not
     * a filter over results. {@code title} and {@code id} narrow what comes back, so they are
     * query parameters. A miss on the source itself is still a 400 (unknown source), never an
     * empty array — that is a bad request, not a search that found nothing.
     */
    @GetMapping("/{source}")
    @Operation(
            summary = "List a source's books, filtered by title or id",
            description = "Give exactly one of title or id. An empty array means no matches, not a failure.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matching books (possibly none)"),
            @ApiResponse(
                    responseCode = "400",
                    description = "Unknown source, or not exactly one of title and id",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ProblemDetail.class))),
            @ApiResponse(
                    responseCode = "401",
                    description = "Missing or invalid token",
                    content = @Content),
            @ApiResponse(
                    responseCode = "502",
                    description = "The source could not be reached or understood",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ProblemDetail.class)))})
    public List<BookResponse> books(
            @Parameter(description = "Catalog source to read", example = DEFAULT_SOURCE)
            @PathVariable String source,

            @Parameter(description = "Title text to match. Mutually exclusive with id.", example = "berserk")
            @RequestParam(required = false) String title,

            @Parameter(
                    description = "Source-native identifier. Mutually exclusive with title.",
                    example = "801513ba-a712-498c-8f57-cae55b38cc92")
            @RequestParam(required = false) String id) {

        // hasText, not != null: "?title=" is a present-but-empty parameter, and forwarding a
        // blank query to the source would ask it for everything it has.
        var byId = StringUtils.hasText(id);
        var byTitle = StringUtils.hasText(title);

        // Equal means both or neither — the two ways of failing to name exactly one filter.
        if (byId == byTitle) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Provide exactly one of \"title\" or \"id\".");
        }

        if (byId) {
            // null is core's "no such book", which becomes [] rather than a 404: see above.
            var book = catalog.find(source, id);
            return book == null ? List.of() : List.of(BookResponse.from(book));
        }

        return catalog.searchByTitle(source, title).stream().map(BookResponse::from).toList();
    }
}
