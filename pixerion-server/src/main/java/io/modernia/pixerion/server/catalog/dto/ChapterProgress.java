package io.modernia.pixerion.server.catalog.dto;

/**
 * Per-chapter progress within a download snapshot.
 *
 * @param chapterId a stable identifier for the chapter (distinct even when two share a label).
 * @param label     the source's human label for the chapter.
 * @param written   how many of the chapter's pages are written so far.
 * @param total     the chapter's total page count.
 */
public record ChapterProgress(String chapterId, String label, int written, int total) {
}
