plugins {
    kotlin("jvm")
    // kapt runs picocli's annotation processor over the Kotlin @Command classes.
    kotlin("kapt")
    application
    // GraalVM native-image build (./gradlew :cli:nativeCompile).
    alias(libs.plugins.graalvmNative)
    // Code coverage. The `core` module gets this via the kotlin-jvm convention plugin;
    // `cli` applies its own plugin set (kapt/application/graalvm) and so wires JaCoCo
    // directly here, with the same config, so its commands are measured for coverage too.
    jacoco
}

group = "io.modernia"
version = "0.1.0"

dependencies {
    implementation(project(":pixerion-core"))
    // runBlocking bridges the `suspend` Catalog contract to picocli's blocking `call()`.
    implementation(libs.kotlinxCoroutines)
    implementation(libs.picocli)
    // picocli-codegen generates GraalVM reachability metadata for the command classes
    // at build time, so the native image needs no handwritten reflect-config.
    kapt(libs.picocliCodegen)

    testImplementation(kotlin("test"))
    testImplementation(libs.junitJupiter)
    testImplementation(libs.kotlinxCoroutinesTest)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

kotlin {
    jvmToolchain(25)
}

// Tell picocli-codegen where to place its generated metadata
// (META-INF/native-image/<group>/<artifact>/...), keeping it namespaced.
kapt {
    arguments {
        arg("project", "${project.group}/${project.name}")
    }
}

// picocli's @Command classes live only in the main source set, so the picocli-codegen
// processor has nothing to do for tests. Skip kapt on the test source set: it removes a
// spurious "options were not recognized by any processor" warning (the global `project`
// arg above is passed to a test apt round with no processor) and shaves a bit off the build.
tasks.matching { it.name == "kaptGenerateStubsTestKotlin" || it.name == "kaptTestKotlin" }
    .configureEach { enabled = false }

application {
    // Main.kt lives in the CLI's default (root) package — the cli module is not
    // published, so it is not reverse-DNS namespaced. The generated class is `MainKt`.
    mainClass = "MainKt"
    // The runnable distribution's launcher is `bin/pixerion`, matching the native
    // image name (graalvmNative.imageName) so both artifacts are invoked the same way.
    applicationName = "pixerion"
}

// In CI the workflow installs a real GraalVM (graalvm/setup-graalvm), which exports
// GRAALVM_HOME. When that's set we turn OFF Gradle toolchain detection so the plugin
// uses that GraalVM directly. Otherwise Gradle resolves `vendor = GRAAL_VM` to the
// Foojay auto-provisioned distribution cached in ~/.gradle/jdks, whose `bin/native-image`
// is a broken (0-byte) symlink — failing with "a problem occurred starting process …
// native-image". Disabling toolchain auto-download isn't enough: that cached toolchain
// is still *selected* once present. Locally GRAALVM_HOME is usually unset, so the
// auto-provisioning toolchain path below still applies.
val graalvmHome = providers.environmentVariable("GRAALVM_HOME")

graalvmNative {
    // Pull reachability metadata (e.g. OkHttp's) from the GraalVM metadata
    // repository so transitive deps work in the native image without manual config.
    metadataRepository {
        enabled = true
    }
    if (graalvmHome.isPresent) {
        toolchainDetection.set(false)
    }
    binaries.named("main") {
        imageName = "pixerion"
        if (!graalvmHome.isPresent) {
            // Build the image with a GraalVM toolchain matching the project's JDK.
            // Gradle auto-provisions it via the Foojay resolver (see settings.gradle.kts),
            // so no manual GraalVM install / GRAALVM_HOME is required. The language
            // version must match the bytecode target (jvmToolchain(25) above).
            javaLauncher = javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(25)
                vendor = JvmVendorSpec.GRAAL_VM
            }
        }
    }
}

// The GraalVM native-build-tools plugin (0.10.x) is not compatible with Gradle's
// configuration cache, which this project enables in gradle.properties. Marking its
// tasks as incompatible makes the cache degrade gracefully (skip-store with a warning)
// when a native build runs, instead of failing — so `:cli:nativeCompile` works without
// passing `--no-configuration-cache` by hand, including when launched from the IDE.
listOf("generateResourcesConfigFile", "nativeCompile", "nativeRun").forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        notCompatibleWithConfigurationCache(
            "GraalVM native-build-tools is not configuration-cache compatible",
        )
    }
}

jacoco {
    // Keep in step with the convention plugin's pin (JDK 25 bytecode support).
    toolVersion = "0.8.13"
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    // The `main` entry point calls exitProcess, so it cannot run inside the test JVM;
    // exclude its generated facade from coverage rather than game the metric. The
    // PixerionCommand parent it wires up is covered by PixerionCommandTest.
    classDirectories.setFrom(
        classDirectories.files.map {
            fileTree(it) { exclude("MainKt.class") }
        },
    )
    reports {
        // The coverage tooling reads the XML; HTML is handy locally; CSV is unused.
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}
