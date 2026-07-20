package io.modernia.pixerion.domain

/**
 * A failure to *reach* or *understand* a source, as opposed to a legitimately
 * absent result.
 *
 * The [Catalog] contract draws a hard line between "this book does not exist"
 * (a successful `null`/empty result) and "the catalog could not be reached or
 * its response could not be understood". Adapters translate transport-level
 * problems — timeouts, connection failures, non-success HTTP statuses, malformed
 * payloads — into this domain-level exception so callers never have to know about
 * a specific source's transport or serialization details.
 *
 * @param message a human-readable description of what went wrong.
 * @param cause the underlying source-level failure, if any.
 */
class CatalogException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
