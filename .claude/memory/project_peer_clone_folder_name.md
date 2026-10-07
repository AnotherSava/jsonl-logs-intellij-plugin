---
name: project_peer_clone_folder_name
description: The Windows clone's folder is named intellij-jsonl-extension, so peer-pull requests from the macOS clone refuse with unknown_project
metadata:
  type: project
---

The two clones of this repo sit under different folder names: `jsonl-logs-intellij-plugin` on the
macOS machine, `intellij-jsonl-extension` under the Windows machine's projects root. Both have
`git@github.com:AnotherSava/jsonl-logs-intellij-plugin.git` as origin, so it is one repo with two
local names.

**Why it matters:** the relay derives a target project id from the *sending* clone's folder name, so
`notify_peer_pull.py` after a push from macOS refuses with `unknown_project` — an answer that reads
like "no session there" and is really "no directory of that name there". Measured 2026-10-06: a push
of two commits reached origin with the Windows clone never told. Addressing `intellij-jsonl-extension`
explicitly resolves, so the mismatch is the whole of the problem.

**How to apply:** after a push from macOS, send the pull request with
`peer_relay.py send --project intellij-jsonl-extension` rather than relying on `notify_peer_pull.py`,
whose address cannot be overridden. Renaming either folder through `/move-project` would remove the
need. Note also that SSH into the Windows machine lands in WSL, not Git Bash, so its drives are
reached under `/mnt/<drive>` and a Git-Bash-style `/d/...` path finds nothing there.
