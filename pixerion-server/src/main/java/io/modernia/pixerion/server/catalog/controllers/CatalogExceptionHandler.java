package io.modernia.pixerion.server.catalog.controllers;

import io.modernia.pixerion.domain.CatalogException;
import io.modernia.pixerion.server.catalog.BookNotFoundException;
import io.modernia.pixerion.server.catalog.UnknownSourceException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the catalog layer's three failure shapes into responses.
 *
 * <p>Scoped to this package with {@code assignableTypes} rather than left global: the
 * mappings below are about catalog reads, and a future controller that happens to raise a
 * {@code CatalogException} for some other reason should not silently inherit "502". (The
 * auth advice is global by contrast, but {@code BadCredentialsException} means only ever
 * one thing.)
 *
 * <p>As with {@code AuthExceptionHandler}, this only sees exceptions raised <em>inside</em>
 * a controller. Anything the security filter chain rejects never reaches here — an expired
 * token is a 401 from {@code HttpStatusEntryPoint}, not a ProblemDetail from this class.
 */
@RestControllerAdvice(assignableTypes = CatalogController.class)
class CatalogExceptionHandler {

    /**
     * A source that could not be reached or understood → <b>502 Bad Gateway</b>.
     *
     * <p>502 and not 500: nothing here is broken. We are a gateway in front of somebody
     * else's API and that API failed us, which is exactly what 502 states. Using 500 would
     * point on-call at this service; using 404 would break core's central invariant by
     * reporting a transport failure as an absent book.
     *
     * <p>Do not put {@code e.getMessage()} in the detail unexamined — adapter messages can
     * carry the upstream URL, which publishes our source topology to any caller. Prefer a
     * fixed sentence, and log the exception (with its cause) at WARN for the operator.
     */
    @ExceptionHandler(CatalogException.class)
    ProblemDetail onCatalogFailure(CatalogException e) {
        // TODO: ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY), set a title such as
        // "Catalog source unavailable" and a generic detail; log e at WARN.
        throw new UnsupportedOperationException("TODO: implement onCatalogFailure");
    }

    /**
     * A source name no adapter owns → <b>400 Bad Request</b>.
     *
     * <p>The exception's message already lists the known schemes (built from
     * {@code CatalogRegistry.getKnown()}), so putting it in the detail keeps the hint
     * accurate as adapters are added. This is our own registry, not upstream topology —
     * safe to echo.
     */
    @ExceptionHandler(UnknownSourceException.class)
    ProblemDetail onUnknownSource(UnknownSourceException e) {
        // TODO: ProblemDetail.forStatus(HttpStatus.BAD_REQUEST) + title/detail.
        throw new UnsupportedOperationException("TODO: implement onUnknownSource");
    }

    /**
     * No book for that reference → <b>404 Not Found</b>.
     *
     * <p>The successful "it isn't there" answer, and the only reason a 404 should ever come
     * out of this controller.
     */
    @ExceptionHandler(BookNotFoundException.class)
    ProblemDetail onBookNotFound(BookNotFoundException e) {
        // TODO: ProblemDetail.forStatus(HttpStatus.NOT_FOUND) + title/detail.
        throw new UnsupportedOperationException("TODO: implement onBookNotFound");
    }
}
