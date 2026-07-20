// The `:webapp` module: the Angular SPA served by `:server` (ADR-0013).
//
// This module is only the Gradle↔npm bridge — the Angular application itself is
// scaffolded with the Angular CLI, in this directory:
//
//   cd pixerion-webapp
//   npx -y @angular/cli@latest new pixerion-webapp --directory . --skip-git
//
// (The project name must stay `pixerion-webapp`: the dist path below depends on it.)
// Until that `package.json` exists, every task here skips and the module is inert.
//
// What it does once the app exists: `npm run build` → dist/pixerion-webapp/browser →
// packaged into this module's jar under META-INF/resources/, where Spring Boot serves
// it from the classpath. `:pixerion-server` pulls that jar in through its dedicated
// `webapp` configuration (bootJar/bootRun only — never the test classpath).
//
// Day-to-day frontend development doesn't go through Gradle at all: run
// `ng serve --proxy-config proxy.conf.json` and iterate against a running backend.
import com.github.gradle.node.npm.task.NpmTask

plugins {
    // Applied for its `jar` + `processResources` lifecycle only — there are no Java
    // sources; the jar is just a vehicle for the compiled Angular bundle.
    java
    alias(libs.plugins.nodeGradle)
}

group = "io.modernia"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

node {
    // Pin and auto-download the Node runtime (like Foojay does for JDKs) so the
    // Gradle build never depends on whatever `node` happens to be on PATH.
    download = true
    version = libs.versions.nodejs.get()
}

// The `onlyIf` guards below re-resolve package.json as a local java.io.File inside each
// task's configuration block: capturing a script-level helper instead would put a script
// object reference into the task snapshot, which the configuration cache rejects.

tasks.npmInstall {
    // Without a package.json, npm walks *up* the directory tree looking for one —
    // never run it before the Angular app has been scaffolded (`ng new`, see header).
    val packageJson = projectDir.resolve("package.json")
    onlyIf("the Angular app has been scaffolded (package.json exists)") { packageJson.exists() }
}

val npmBuild = tasks.register<NpmTask>("npmBuild") {
    group = "build"
    description = "Compiles the Angular bundle (npm run build)."
    dependsOn(tasks.npmInstall)
    npmCommand.set(listOf("run", "build"))
    val packageJson = projectDir.resolve("package.json")
    onlyIf("the Angular app has been scaffolded (package.json exists)") { packageJson.exists() }
    // Angular CLI file layout; fileTree()/files() tolerate absence pre-scaffold.
    inputs.files("package.json", "package-lock.json", "angular.json", "tsconfig.json", "tsconfig.app.json")
        .withPropertyName("angularConfig")
    inputs.files(fileTree("src")).withPropertyName("angularSources")
    inputs.files(fileTree("public")).withPropertyName("angularAssets")
    outputs.dir(layout.projectDirectory.dir("dist")).withPropertyName("angularDist")
}

tasks.register<NpmTask>("run") {
    group = "application"
    description = "Runs the Angular dev server (ng serve) with the backend proxied to localhost:8080."
    dependsOn(tasks.npmInstall)
    // `npm run start -- --proxy-config …`: the `--` forwards the flag to `ng serve`,
    // so the default `ng new` scaffold works without touching angular.json. Uses the
    // pinned Node above, so it runs even without a local node install. Long-running —
    // stop with Ctrl-C; plain `ng serve --proxy-config proxy.conf.json` is equivalent.
    npmCommand.set(listOf("run", "start"))
    args.set(listOf("--", "--proxy-config", "proxy.conf.json"))
    val packageJson = projectDir.resolve("package.json")
    doFirst {
        if (!packageJson.exists()) {
            throw GradleException(
                "No Angular app here yet — scaffold it first (see the header of " +
                    "pixerion-webapp/build.gradle.kts):\n" +
                    "  cd pixerion-webapp && npx -y @angular/cli@latest new pixerion-webapp --directory . --skip-git",
            )
        }
    }
}

tasks.processResources {
    dependsOn(npmBuild)
    // META-INF/resources/ is one of Spring Boot's classpath static-resource roots, so
    // the server serves the bundle without any wiring of its own (see SpaConfig for
    // the history-API fallback). The dist path follows the Angular project name.
    from(layout.projectDirectory.dir("dist/pixerion-webapp/browser")) {
        into("META-INF/resources")
    }
}
