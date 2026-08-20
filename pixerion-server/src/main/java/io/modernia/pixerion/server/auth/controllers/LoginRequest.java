package io.modernia.pixerion.server.auth.controllers;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Credentials used to obtain a JWT")
public record LoginRequest(
        @Schema(description = "User email", example = "admin@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Email String email,

        @Schema(description = "Plain-text password", example = "__unsafe__+$password", format = "password")
        @NotBlank String password
) { }
