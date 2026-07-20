package io.modernia.pixerion.server.catalog.dto;

import java.util.List;

/**
 * Snapshot of a download's progress, sent as the SSE {@code state} event on a fixed
 * cadence while the job runs — the payload a client renders progress bars from.
 *
 * @param status        the job's current status.
 * @param totalChapters how many chapters the download spans (0 until known).
 * @param chaptersDone  how many chapters are fully written.
 * @param chapters      per-chapter progress for chapters seen so far.
 */
public record StateEvent(String status, int totalChapters, int chaptersDone, List<ChapterProgress> chapters) {
}
