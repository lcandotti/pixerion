package io.modernia.pixerion.server.catalog.dto;

/**
 * Wire representation of a completed download (mirrors {@code Downloader.Summary}).
 *
 * @param book      the downloaded book's title.
 * @param directory absolute path the pages were written under.
 * @param pages     number of pages written.
 * @param chapters  number of distinct chapters those pages span.
 */
public record DownloadResponse(String book, String directory, int pages, int chapters) {
}
