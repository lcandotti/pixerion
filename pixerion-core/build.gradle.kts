plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // kotlinx.serialization compiler plugin — chosen over reflection-based JSON for
    // its GraalVM native-image friendliness (no runtime reflection).
    alias(libs.plugins.kotlinPluginSerialization)

    // `shared` is the distributable library, so it is publishable to a Maven repo.
    // The `cli` module is deliberately NOT published (it has no maven-publish).
    `maven-publish`
}

// Maven coordinates: io.modernia.pixerion:pixerion-core:<version>.
// Aligned with the source namespace; the artifact id follows the JVM convention
// of `<project>-<module>` (cf. jackson-core, kotlinx-coroutines-core) so the jar
// is self-identifying on a consumer's classpath (a bare `shared.jar` would not be).
// `-core` names the foundational capability, leaving room for sibling artifacts
// (e.g. a future `pixerion-mangadex`) without renaming this one.
group = "io.modernia.pixerion"
version = "0.1.0"

dependencies {
    // Catalog contract is `suspend` and exposes `Flow` (Catalog.download), so
    // coroutines is part of the public API — `api`, not `implementation`.
    api(libs.kotlinxCoroutines)
    // JSON (de)serialization for source DTOs at the adapter boundary.
    implementation(libs.kotlinxSerialization)
    // HTTP transport for the MangaDex adapter. Plain OkHttp (mature JVM incumbent,
    // good GraalVM story) rather than a Kotlin-first client.
    implementation(libs.okhttp)

    testImplementation(kotlin("test"))
    testImplementation(libs.junitJupiter)
    testImplementation(libs.kotlinxCoroutinesTest)
    // MockWebServer stands in for the MangaDex API in adapter tests.
    testImplementation(libs.okhttpMockWebServer)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

// Publish sources (and an empty-but-present javadoc jar) alongside the binary so
// consumers get navigable sources in their IDE — and so the artifact satisfies
// Maven Central's sources/javadoc requirement if that becomes the target.
java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "pixerion-core"
            // `from(components["java"])` carries the compiled jar, the sources/javadoc
            // jars declared above, and a POM whose dependencies mirror the `api`/
            // `implementation` split (coroutines is exposed as `api`, so it lands in
            // the POM's compile scope; okhttp/serialization stay runtime).
            from(components["java"])

            pom {
                name = "Pixerion Core"
                description = "Book-catalog domain, source adapters, and downloader for Pixerion."
                // TODO: fill in before a public release.
                url = "https://github.com/<owner>/pixerion"
                licenses {
                    license {
                        name = "GNU Lesser General Public License v3.0"
                        url = "https://www.gnu.org/licenses/lgpl-3.0.txt"
                    }
                }
                developers {
                    developer {
                        id = "modernia"
                        name = "Modernia"
                    }
                }
                scm {
                    url = "https://github.com/<owner>/pixerion"
                    connection = "scm:git:https://github.com/<owner>/pixerion.git"
                }
            }
        }
    }

    repositories {
        // `publishToMavenLocal` works out of the box for local consumption/testing.
        //
        // A remote GitHub Packages repository is wired up but only registered when an
        // owner is configured (via `-Pgpr.owner=…`, gradle.properties, or CI), so the
        // build stays green without credentials. Reads use providers to remain
        // configuration-cache compatible.
        val ghOwner = providers.gradleProperty("gpr.owner").orNull
        if (ghOwner != null) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/$ghOwner/pixerion")
                credentials {
                    username =
                        providers
                            .gradleProperty("gpr.user")
                            .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                            .orNull
                    password =
                        providers
                            .gradleProperty("gpr.key")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                            .orNull
                }
            }
        }
    }
}
