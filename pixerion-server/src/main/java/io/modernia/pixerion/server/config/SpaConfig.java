package io.modernia.pixerion.server.config;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Serves the embedded Angular SPA (ADR-0013). The webapp jar (built by
 * {@code :pixerion-webapp}) puts the compiled bundle under {@code META-INF/resources/},
 * and this resolver adds the one behavior plain static serving lacks: the
 * <em>history-API fallback</em>. The SPA uses pushState routes ({@code /library},
 * {@code /books/42}, …) that only exist client-side, so a deep link or a browser
 * refresh must be answered with {@code index.html} and left to the Angular router.
 *
 * <p>Controllers (and the actuator/docs endpoints) always win over this handler —
 * resource handling is registered at lowest precedence — so the fallback only sees
 * requests nothing else claimed. Backend prefixes are still excluded explicitly:
 * an unknown {@code /api/**} path must stay a 404, not become a 200 with HTML.
 *
 * <p>When the webapp jar is absent (server tests don't carry it — see the
 * {@code webapp} configuration in {@code pixerion-server/build.gradle.kts}), the
 * fallback resource doesn't exist and requests simply 404 as before.
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    /** Path prefixes that belong to the backend and must never fall back to the SPA. */
    private static final String[] BACKEND_PREFIXES = {"api/", "auth/", "actuator/", "v3/", "scalar"};

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/META-INF/resources/")
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    private static final class SpaFallbackResolver extends PathResourceResolver {

        private static final Resource INDEX = new ClassPathResource("/META-INF/resources/index.html");

        @Override
        protected @Nullable Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = super.getResource(resourcePath, location);
            if (requested != null) {
                return requested;
            }
            for (String prefix : BACKEND_PREFIXES) {
                if (resourcePath.startsWith(prefix)) {
                    return null;
                }
            }
            return INDEX.exists() ? INDEX : null;
        }
    }
}
