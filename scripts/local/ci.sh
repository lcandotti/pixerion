#!/usr/bin/env bash
# Run the same checks CI runs, locally, so failures are caught before pushing
# instead of after the push-wait cycle on GitHub Actions.
#
# The CI `verify` job (`.github/workflows/ci.yml`) is two ordered steps — `all`
# mirrors them in the same order, so a green `all` predicts a green `verify`:
#     1. ktlintCheck   (lint gate)
#     2. test          (JUnit 5 + JaCoCo coverage reports)
#
# The separate Qodana workflow (`.github/workflows/qodana_code_quality.yml`) can
# also be reproduced locally via the `qodana` target — same `qodana.yaml`, so it
# catches code-quality *and* coverage issues (Qodana reads the JaCoCo XML that
# qodana.yaml's `bootstrap` stages) before they surface in CI. It drives Docker via
# the Qodana CLI; install hints are printed if the CLI is absent. The `qodana`/`full`
# targets require a QODANA_TOKEN env var — the *project* access token (not an
# organization token); see run_qodana below.
#
# Usage (from anywhere in the repo):
#     bash scripts/local/ci.sh                # all: lint + test (fast; mirrors CI verify)
#     bash scripts/local/ci.sh lint           # ktlintCheck only
#     bash scripts/local/ci.sh tests          # test + coverage; prints report paths
#     bash scripts/local/ci.sh qodana [args]  # test + Qodana scan (needs QODANA_TOKEN; extra args → `qodana scan`)
#     bash scripts/local/ci.sh full           # lint + test + Qodana scan (needs QODANA_TOKEN; complete pre-push check)
set -euo pipefail

TARGET="${1:-all}"
# Drop the target so any remaining args forward to `qodana scan` (used by the
# qodana/full targets); `|| true` keeps `set -e` happy when no args were passed.
shift || true

# Run from the repo root regardless of the caller's cwd, so `./gradlew` and the
# relative report paths resolve.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

run_lint() {
    # ktlint gate — the first CI step; fails fast before the slower ones.
    echo "=== ktlintCheck (lint gate) ==="
    ./gradlew ktlintCheck
}

run_tests() {
    # `test` finalizes each module's jacocoTestReport (wired in the kotlin-jvm
    # convention plugin and the cli/server build scripts), producing the coverage
    # XML that Qodana later consumes. All three modules have tests, so all three
    # reports are emitted.
    echo "=== test (JUnit 5 + JaCoCo coverage) ==="
    ./gradlew test
    echo "--- coverage reports ---"
    for m in pixerion-core pixerion-cli pixerion-server; do
        html="$m/build/reports/jacoco/test/html/index.html"
        [[ -f "$html" ]] && echo "    $m -> $html"
    done
}

run_qodana() {
    # Reproduces the Qodana workflow locally. Qodana reads coverage from
    # .qodana/code-coverage, which qodana.yaml's `bootstrap` fills from each module's
    # JaCoCo XML — so the tests (which produce that XML) must run first.
    run_tests

    echo "=== qodana scan (code quality + coverage) ==="
    if ! command -v qodana >/dev/null 2>&1; then
        cat >&2 <<'EOF'
The Qodana CLI is not installed. It drives Docker and reads this repo's
qodana.yaml, so a local scan matches CI exactly — including the JaCoCo coverage
wired via `bootstrap`. Install it, then re-run `bash scripts/local/ci.sh qodana`:

    brew install jetbrains/utils/qodana                    # macOS / Linuxbrew
    curl -fSsL https://jb.gg/qodana-cli/install | bash     # any *nix
    # docs: https://github.com/JetBrains/qodana-cli#installation
EOF
        exit 2
    fi

    # QODANA_TOKEN is required: qodana.yaml uses the full `qodana-jvm` linter, which
    # authenticates against Qodana Cloud. Use the *project* access token (Qodana Cloud →
    # the project → Settings → generate a token), NOT an organization-level token — the
    # scan is scoped to this project. Export it before running:
    #     export QODANA_TOKEN=<project-token>
    if [[ -z "${QODANA_TOKEN:-}" ]]; then
        cat >&2 <<'EOF'
QODANA_TOKEN is not set. The local scan uses the full qodana-jvm linter, which
authenticates against Qodana Cloud. Generate a *project* access token (Qodana
Cloud -> your project -> Settings -> Tokens; NOT an organization token) and export it:

    export QODANA_TOKEN=<project-token>
    bash scripts/local/ci.sh qodana
EOF
        exit 2
    fi

    # Uses the CLI's default results dir (under the gitignored .qodana/). Extra args
    # ("$@") pass through to the CLI.
    qodana scan "$@"
    echo "--- qodana report ---"
    echo "    view the HTML report with:  qodana show"
}

case "$TARGET" in
    lint)   run_lint ;;
    tests)  run_tests ;;
    qodana) run_qodana "$@" ;;
    # Steps run sequentially (NOT chained with `&&`) so `set -e` aborts on the first
    # failure and propagates its exit code, instead of printing success on a failure.
    all)    run_lint; run_tests ;;
    full)   run_lint; run_qodana "$@" ;;  # run_qodana runs the tests itself
    *)      echo "Unknown target: $TARGET (use lint|tests|qodana|all|full)" >&2; exit 2 ;;
esac

echo
echo "✅ Local CI check ($TARGET) completed."
