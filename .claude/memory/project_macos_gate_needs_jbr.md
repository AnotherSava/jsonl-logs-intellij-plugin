---
name: project_macos_gate_needs_jbr
description: On the macOS machine the commit gate's Gradle step needs JAVA_HOME set to IntelliJ's bundled JBR — no JDK is installed there
metadata:
  type: project
---

On the macOS machine there is no JDK: `java` resolves to the `/usr/bin/java` stub with no runtime
behind it, `/Library/Java/JavaVirtualMachines` is empty, `JAVA_HOME` is unset and no JDK cask is
installed. So `.claude/commit-checks.sh` fails its Gradle step with "Unable to locate a Java Runtime"
unless the run exports `JAVA_HOME` first. The IntelliJ IDEA app bundle's own runtime satisfies the
wrapper's JDK 17+ requirement — `/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home`, OpenJDK
25 as of 2026-10-06 — and with it the whole gate exits 0, tests and plugin zip included.

**Why:** the gate script names "a JDK 17+" as a prerequisite and deliberately fails rather than
skipping when one is missing, which is correct but says nothing about where to find one here. Without
this, a commit from the macOS clone stops at a failure that reads like a build defect. The Windows
clone has its own JDK and needs none of this.

**How to apply:** prefix any `gradlew` or `.claude/commit-checks.sh` run on macOS with
`export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"`. Stop the daemon
afterwards (`bash ./gradlew --stop`) so nothing is left running. Installing a real JDK, or teaching
the gate script to fall back to a bundled JBR, would both remove the need — neither has been decided.
See [[project_archived_repo_push]] for the other macOS-side constraint on finishing a commit here.
