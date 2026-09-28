---
created: 2026-09-28 00:56:46
---

# Offer a Kotlin linter (detekt or ktlint) for the commit gate, measured before any rules are set

The repo has no Kotlin linter: no detekt, ktlint or .editorconfig, and build.gradle.kts sets no warnings-as-errors. .claude/commit-checks.sh says so in its header ('There is no linter in this repo'). The gate runs the conventions checker, gradlew build buildPlugin and, on Windows, the capture-script check. The offer was made once during the /adopt walk on 2026-09-26 and never answered, so it counts as still pending, not declined. Next step: run detekt and ktlint against src/ as they stand, count findings per rule, and present both baselines. Then adopt whichever the user picks with a clean baseline: fix or explicitly scope every existing finding, and document every rule switched off inside the tool's config. Wire it into .claude/commit-checks.sh as the gate step. If the user declines, record that in project memory so it is not offered again.
