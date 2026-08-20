package io.modernia.pixerion.server.auth.controllers;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @param accessToken a signed JWT, to be sent back as {@code Authorization: Bearer …}
 * @param expiresIn   the token's lifetime in seconds
 */
@Schema(description = "JWT token to access protected endpoints")
public record LoginResponse(
        @Schema(description = "JWT token", example = "__unsafe__+$token")
        String accessToken,

        @Schema(description = "Time to live of the token in seconds", example = "3600")
        long expiresIn
) {
}
