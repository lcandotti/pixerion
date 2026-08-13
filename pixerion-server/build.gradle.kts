// The `:server` module: a Java Spring Boot (Spring MVC) web front-end over `core`.
//
// It is the web analogue of `:cli` — request -> core -> serialize — and a second
// thin consumer of the reusable `core` capability. Unlike `core`/`cli` it is a
// Java module, so it applies the `java` plugin + Spring Boot plugins directly
// rather than the `kotlin-jvm` convention plugin.
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    alias(libs.plugins.springBoot)
    // Code coverage. Like `cli`, this module applies its own plugin set (it is a Java
    // Spring Boot module, not a `kotlin-jvm` convention module), so it wires JaCoCo
    // directly here — same config — emitting an XML report for the coverage tooling.
    jacoco
}

group = "io.modernia"
version = "0.1.0"

// No repositories block: settings.gradle.kts declares them centrally via
// dependencyResolutionManagement, and a project-level block would shadow it.

java {
    // Match the rest of the build (core/cli use jvmToolchain(25)).
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// The embedded Angular bundle. A dedicated configuration — not
// `implementation`/`runtimeOnly` — so the webapp jar rides along only in the runnable
// artifacts (bootJar/bootRun below) and stays OFF the test classpath: server tests
// must never trigger a npm/Node build.
val webapp: Configuration by configurations.creating

dependencies {
    // Boot's BOM via Gradle's native platform() support (the modern replacement for the
    // legacy io.spring.dependency-management plugin).
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(project(":pixerion-core"))
    // Utilities libraries. Lombok is a compile-time-only code generator: it must be on
    // the `annotationProcessor` configuration to run at all (Gradle does not execute
    // processors found on the compile classpath), and `compileOnly` keeps it out of the
    // runtime jar, where it has nothing to do.
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    // The reusable capability. `core` is coroutine-first, so this module calls it
    // through core's BlockingCatalog facade. kotlin-stdlib and coroutines
    // arrive transitively because `core` exposes them as `api`.
    implementation(libs.springBootStarterWebMvc)
    // Exposes /actuator/health, used as the container health check (see docker-compose.yml).
    implementation(libs.springBootStarterActuator)
    // Auth + persistence stack: Spring Security secures the app; oauth2-resource-server
    // validates stateless JWTs; data-jpa + PostgreSQL + flyway for a relational store.
    implementation(libs.springBootStarterSecurity)
    implementation(libs.springBootStarterOauth2ResourceServer)
    implementation(libs.springBootStarterDataJpa)
    implementation(libs.springBootStarterValidation)
    // Boot 4 moved FlywayAutoConfiguration out of the monolithic autoconfigure jar into
    // this per-technology module; without it Flyway never runs and ddl-auto=validate
    // fails against the un-migrated schema (missing table [users]).
    implementation(libs.springBootFlyway)
    implementation(libs.flywayCore)
    // API docs: springdoc generates /v3/api-docs from the controllers;
    // Scalar serves the interactive console at /scalar.
    implementation(libs.springdocOpenapiStarterWebmvcScalar)
    runtimeOnly(libs.flywayPostgresql)
    runtimeOnly(libs.postgresql)
    // The Angular SPA, packaged as a jar of static resources (see the `webapp`
    // configuration above for why it isn't a normal implementation dependency).
    webapp(project(":pixerion-webapp"))

    testImplementation(libs.springBootStarterTest)
    testImplementation(libs.springBootWebmvcTest)
    testImplementation(libs.springSecurityTest)
    // Stands in for a source API when exercising endpoints against a real catalog
    // (same MockWebServer approach as core's adapter tests) — no live network.
    testImplementation(libs.okhttpMockWebServer)
    // In-memory DB so tests run without a live Postgres.
    testRuntimeOnly(libs.h2)
    // For integration tests against a real PostgreSQL container (skipped when Docker
    // is unavailable); the Postgres driver + flyway-database-postgresql above are
    // runtimeOnly, which the test runtime classpath already extends.
    testImplementation(libs.springBootTestcontainers)
    testImplementation(libs.testcontainersPostgresql)
    testImplementation(libs.testcontainersJunitJupiter)
}

jacoco {
    // Keep in step with the convention plugin's / cli's pin (JDK 25 bytecode support).
    toolVersion = "0.8.13"
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    // The Spring Boot `main` bootstrap only calls SpringApplication.run; the context it
    // starts is already covered by the @SpringBootTest suites. Exclude the entry-point
    // class from coverage rather than double-boot the app just to touch main().
    classDirectories.setFrom(
        classDirectories.files.map {
            fileTree(it) { exclude("io/modernia/pixerion/server/Application.class") }
        },
    )
    reports {
        // The coverage tooling reads the XML; HTML is handy locally; CSV is unused.
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

// Ship only the executable Spring Boot jar under a stable name, so the Dockerfile
// (and any deploy) can reference a predictable artifact. Nothing depends on this
// module as a library, so the plain (non-executable) jar is redundant.
tasks.bootJar {
    archiveFileName.set("pixerion-server.jar")
    // Ship the SPA inside the executable jar: one artifact serves API + UI.
    classpath(webapp)
}

tasks.bootRun {
    classpath(webapp)
}

tasks.jar {
    enabled = false
}
