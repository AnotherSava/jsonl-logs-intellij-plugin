#!/usr/bin/env bash
# Commit gate, run from the repo root by /commit before it drafts a commit plan. A non-zero exit
# stops the commit. Every commit here goes straight to main, so this is the only thing that reads a
# change before it is published.
#
# Runs three things:
# - the conventions checker: every rule this repo's adopted number entitles it to, plus the
#   universal rules, against the working tree;
# - `gradlew build buildPlugin`: `build` compiles and runs the JUnit suite, and `buildPlugin` is the
#   task the deploy runs to package the zip, which `build` alone never reaches. Both run on the
#   Gradle version the wrapper pins;
# - on Windows, the docs-relevance skill's check of the screenshot capture scripts.
#
# A pass does not cover `verifyPlugin`, which downloads every recommended IDE and is too slow for a
# commit, and nothing here loads the plugin into a running IDE. There is no linter in this repo.
#
# Prerequisites are `python3` on PATH, a JDK 17+ for the wrapper to run on and, on Windows, `pwsh`. Their absence fails
# rather than skips: a check that cannot tell "passed" from "never ran" turns an open problem into a
# closed-looking one.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2

status=0

# Buffer each step and print its detail only when it fails; the tally line always shows, so a pass
# is never silent.
run() {
  local label="$1" out
  shift
  out="$(mktemp)"
  if "$@" >"$out" 2>&1; then
    echo "==> $label — $(tail -n 1 "$out")"
  else
    echo "==> $label — FAILED"
    cat "$out"
    status=1
  fi
  rm -f "$out"
}

run "Conventions" python3 ~/.claude/conventions/check.py .

# Through bash rather than ./gradlew so a checkout that lost the executable bit still runs it. Gradle's
# last line reports its configuration cache rather than the build, so the tally is written here.
gradle_build() {
  bash ./gradlew build buildPlugin && echo "compiled, tests passed, plugin zip built"
}
run "Gradle build" gradle_build

# The Windows capture scripts borrow their helpers from the docs-relevance skill; this checks every
# path, command and parameter they use still resolves, which nothing else here runs. It loads a
# Windows-only library, so elsewhere it is reported as not covered rather than passed.
if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
  run "Capture scripts" pwsh -NoProfile -NonInteractive -File ~/.claude/skills/docs-relevance/scripts/check-capture-scripts.ps1
else
  echo "==> Capture scripts — NOT COVERED (the checker runs on Windows only)"
fi

exit "$status"
