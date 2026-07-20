package io.modernia.pixerion.server.catalog.dto;

/**
 * Body of {@code GET /downloads/{id}}: a job's current status and outcome.
 *
 * @param id      the job id.
 * @param status  the job's status.
 * @param source  the catalog source the download targets.
 * @param summary the completed download's summary, or {@code null} until {@code COMPLETED}.
 * @param error   the failure/absence message, or {@code null} unless {@code FAILED}/{@code NOT_FOUND}.
 */
public record JobStatusResponse(String id, String status, String source, DownloadResponse summary, String error) {
}
