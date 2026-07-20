package io.modernia.pixerion.server.catalog.download;

/** Lifecycle of a server-side download job. */
public enum DownloadStatus {
    /** The download is in progress. */
    RUNNING,
    /** Every page was written; a summary is available. */
    COMPLETED,
    /**
     * The download did not finish: the source was unreachable (a {@code CatalogException}
     * surfaced), a local failure occurred (e.g. disk I/O), or the executor rejected the job.
     */
    FAILED,
    /** The book does not exist — the terminal, async analog of an immediate 404. */
    NOT_FOUND
}
