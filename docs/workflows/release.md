# Release & artifacts

What Pixerion produces, where each artifact comes from, and how the library is
published. There is **no formal release process yet**: no tags, no changelog, no
GitHub Releases. Artifacts are CI byproducts of merges. This page documents
what exists and marks the gaps.

## Artifacts

| Artifact | Built by | Where it lands | Retention |
|---|---|---|---|
| CLI JVM distribution (`bin/pixerion` + jars, needs a JRE 25) | `distribution` job on merge → `./gradlew :pixerion-cli:distZip` | GitHub Actions artifact `pixerion-distribution` | 7 days |
| CLI native binaries | `native` job on merge → `:pixerion-cli:nativeCompile` | Actions artifacts `pixerion-native-linux-x86_64`, `-macos-arm64`, `-windows-x86_64` | 7 days |
| `pixerion-core` library jar (+ sources, javadoc) | manual `publish*` task | Maven local or GitHub Packages | — |
| Server jar `pixerion-server.jar` (with SPA) | `./gradlew :pixerion-server:bootJar` | `pixerion-server/build/libs/` | not built in CI |
| Server container image | `docker compose build` / `docker build -f pixerion-server/Dockerfile .` | local Docker | not built in CI |

To build any of them locally, run the same Gradle command.

## Versioning

Every module is hard-coded to `version = "0.1.0"` in its `build.gradle.kts`. The
CLI's MangaDex `User-Agent` (`pixerion/0.1.0`) is a separate constant in
`MangaDexClient`. Bump them together.

> **TODO (owner to confirm):** the versioning scheme (SemVer?), whether versions
> should come from git tags, whether a `CHANGELOG.md` is wanted (Conventional
> Commits make generating one straightforward), and whether native binaries should
> be attached to GitHub Releases instead of expiring after 7 days.

## Publishing the core library

Coordinates: **`io.modernia.pixerion:pixerion-core:<version>`** (LGPL-3.0). The
POM mirrors the `api`/`implementation` split, so coroutines lands in compile scope
and OkHttp and serialization in runtime scope.

**Locally** (for trying it out from another project):

```sh
./gradlew :pixerion-core:publishToMavenLocal
# consumer: repositories { mavenLocal() }  dependencies { implementation("io.modernia.pixerion:pixerion-core:0.1.0") }
```

**GitHub Packages** is wired up but only activates when an owner is given:

```sh
./gradlew :pixerion-core:publish \
  -Pgpr.owner=<github-owner> -Pgpr.user=<github-user> -Pgpr.key=<token-with-write:packages>
# gpr.user / gpr.key fall back to the GITHUB_ACTOR / GITHUB_TOKEN env vars (CI)
```

Without `gpr.owner` the repository isn't registered, so the build stays green
without credentials.

> **Warning:** the POM `url` and `scm` still contain the placeholder
> `https://github.com/<owner>/pixerion` (`pixerion-core/build.gradle.kts`). Fill
> them in before any public release.

## Deploying the server

There is no deployment pipeline. The only defined runtime is Docker Compose
(see [Operations](../operations.md)).

> **Warning:** the Dockerfile doesn't currently build. It copies `pixerion-core`,
> `-cli` and `-server`, but not `pixerion-webapp`, and Gradle 9 refuses to configure
> a project whose directory is missing. See
> [Troubleshooting](../troubleshooting.md#docker).

## Related

- [Development workflow](development.md)
- [Operations](../operations.md)
- [Back to the hub](../index.md)
