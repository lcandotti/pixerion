package io.modernia.pixerion.server.catalog.dto;

/**
 * Terminal SSE {@code error} event for a download that did not complete.
 *
 * @param status  the terminal status ({@code FAILED} or {@code NOT_FOUND}).
 * @param message a human-readable explanation.
 */
public record ErrorEvent(String status, String message) {
}
