package io.modernia.pixerion.server.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param secret HMAC key for HS256. The 32-character floor is not a style rule: Nimbus
 *               refuses to build a MAC verifier below 256 bits, and a short secret fails
 *               at request time rather than at startup.
 * @param ttl    how long an issued access token stays valid.
 */
@Validated
@ConfigurationProperties("pixerion.security.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32) String secret,
        @NotNull Duration ttl
) {}
