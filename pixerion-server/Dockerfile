# syntax=docker/dockerfile:1
#
# Multi-stage build for the Pixerion web backend (the :server module).
# Build context is the repository ROOT (the build is a multi-module Gradle build),
# so build it as: `docker build -f pixerion-server/Dockerfile .` or via docker-compose.yml.

############################
# Stage 1 — build the jar
############################
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy the Gradle wrapper + build configuration first so this layer is cached
# independently of source changes (only re-runs when build config changes).
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts gradle.properties ./
COPY buildSrc ./buildSrc

# Warm the wrapper (downloads the pinned Gradle distribution) on its own layer.
RUN ./gradlew --version --no-daemon

# settings.gradle.kts includes :pixerion-core, :pixerion-cli and :pixerion-server —
# all three module dirs must be present for Gradle to configure the build, even
# though we only build :pixerion-server.
COPY pixerion-core ./pixerion-core
COPY pixerion-cli ./pixerion-cli
COPY pixerion-server ./pixerion-server

# Build only the server's executable jar (bootJar does not run tests).
# NOTE: the build requires network access — Gradle downloads the pinned Gradle
# distribution and the project's dependencies.
RUN ./gradlew :pixerion-server:bootJar --no-daemon

############################
# Stage 2 — runtime
############################
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# curl is used by the container healthcheck to probe /actuator/health.
# Run unprivileged: the downloader writes under the run user's home (~/.pixerion),
# so create that directory up front owned by the app user — a volume mounted there
# (see docker-compose.yml) then inherits the right ownership.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --home-dir /home/app --shell /usr/sbin/nologin app \
    && mkdir -p /home/app/.pixerion \
    && chown -R app:app /home/app

COPY --from=build --chown=app:app /workspace/pixerion-server/build/libs/pixerion-server.jar /app/app.jar

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
