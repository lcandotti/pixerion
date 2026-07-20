plugins {
    // The Kotlin DSL plugin provides a convenient way to develop convention plugins.
    // Convention plugins are located in `src/main/kotlin`, with the file extension `.gradle.kts`,
    // and are applied in the project's `build.gradle.kts` files as required.
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(25)
    // buildSrc is compiled by the Kotlin version BUNDLED with Gradle (2.1.0 in Gradle 9.3),
    // which has no JVM 25 target — unlike the project's own Kotlin (2.4.0). Without pinning,
    // it infers 25 from the toolchain and warns "falling back to JVM_24" on every compile.
    // 24 bytecode runs fine on JDK 25; drop this pin when Gradle bundles a Kotlin that
    // supports JVM 25.
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_24
    }
}

// Keep Java bytecode in step with the JVM_24 Kotlin target above so Gradle's Kotlin/Java
// target consistency check stays quiet (compileJava is NO-SOURCE here, but the check
// compares configured targets regardless). Drop alongside the pin above.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(24)
}

dependencies {
    // Add a dependency on the Kotlin Gradle plugin, so that convention plugins can apply it.
    implementation(libs.kotlinGradlePlugin)
    // ktlint, applied by the kotlin-jvm convention plugin so all modules share one lint config.
    implementation(libs.ktlintGradlePlugin)
}
