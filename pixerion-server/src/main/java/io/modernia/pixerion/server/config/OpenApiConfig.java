package io.modernia.pixerion.server.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for the generated spec (ADR-0012). springdoc derives paths and
 * schemas from the controllers and their records; this bean supplies what it cannot
 * infer: the API identity and the bearer-JWT security scheme. The scheme is applied
 * globally so every operation is documented as JWT-protected — matching the security
 * default of {@code anyRequest().authenticated()} — and the few public endpoints opt
 * out per-operation with an empty {@code @SecurityRequirements}.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI pixerionOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Pixerion Server API")
                        .version("0.1.0")
                        .description("HTTP front-end over the Pixerion catalog: auth, search, and download jobs."))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT from POST /auth/login")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
