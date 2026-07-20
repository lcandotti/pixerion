package io.modernia.pixerion.server.catalog.dto;

/**
 * Body of {@code POST /downloads} (202 Accepted): a handle to the started job.
 *
 * @param id     the job id to poll ({@code GET /downloads/{id}}) or subscribe to
 *               ({@code GET /downloads/{id}/events}).
 * @param status the job's status at creation (always {@code RUNNING}).
 * @param source the catalog source the download targets.
 */
public record JobResponse(String id, String status, String source) {
}
