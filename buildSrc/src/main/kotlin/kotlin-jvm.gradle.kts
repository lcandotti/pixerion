// The code in this file is a convention plugin - a Gradle mechanism for sharing reusable build logic.
// `buildSrc` is a Gradle-recognized directory and every plugin there will be easily available in the rest of the build.
package buildsrc.convention

import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin in JVM projects.
    kotlin("jvm")

    // ktlint: Kotlin formatter/linter. `ktlintCheck` is the lint gate (wired into `check`),
    // `ktlintFormat` auto-fixes. Applied here so every module is linted with one config.
    id("org.jlleitschuh.gradle.ktlint")

    // JaCoCo code coverage. Every module that applies this convention plugin emits an
    // XML coverage report under build/reports/jacoco/, which the code-quality tooling
    // (Qodana) can consume. Wired here once so no per-module coverage setup is needed.
    jacoco
}

kotlin {
    // Use a specific Java version to make it easier to work in different environments.
    // Kotlin 2.4.0 supports JVM target 25, so the toolchain version is used as-is (the
    // bytecode target is inferred from it — major version 69). No explicit jvmTarget pin
    // is needed here; only buildSrc's own compile (Gradle-bundled Kotlin 2.1.0) does.
    jvmToolchain(25)
}

jacoco {
    // Pin a JaCoCo that understands the project's JDK 25 bytecode (jvmToolchain(25));
    // the version bundled with Gradle can lag new Java releases. Bump as the toolchain
    // moves forward.
    toolVersion = "0.8.13"
}

tasks.withType<Test>().configureEach {
    // Configure all test Gradle tasks to use JUnitPlatform.
    useJUnitPlatform()

    // Log information about all test results, not only the failed ones.
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED
        )
    }

    // Always (re)generate the coverage report after a test run, so `./gradlew test`
    // (and CI) leaves a fresh JaCoCo XML on disk for the coverage tooling to pick up.
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    // Running the report alone first runs the tests that produce the execution data.
    dependsOn(tasks.named("test"))
    reports {
        // The coverage tooling reads the XML; HTML is handy for local inspection; CSV is unused.
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}
