package io.modernia.pixerion.server.auth.controllers;

/**
 * @param accessToken a signed JWT, to be sent back as {@code Authorization: Bearer …}
 * @param expiresIn   the token's lifetime in seconds
 */
public record LoginResponse(
        String accessToken,
        long expiresIn
) {
}
