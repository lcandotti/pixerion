package io.modernia.pixerion.domain

/**
 * A reference scoped to the source that issued it.
 *
 * The handle for fetching a book directly from a specific source, regardless of
 * any cross-source identity. [scheme] identifies the source namespace and lets an
 * aggregating catalog route the lookup; [value] is opaque and meaningful only
 * within that scheme.
 *
 * @property scheme the source namespace, e.g. `"mangadex"`, `"google-books"`.
 * @property value the source-native identifier within [scheme], treated as opaque.
 */
data class SourceRef(
    val scheme: String,
    val value: String,
) : BookRef
