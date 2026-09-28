---
name: project_archived_repo_push
description: The GitHub repo is archived, so every push is unarchive, push, wait for Pages, re-archive — raised with the push question
metadata:
  type: project
---

The GitHub repo is archived, so a plain `git push` is rejected as read-only. Pushing means
`gh repo unarchive`, `git push`, waiting for the Pages build to finish, then `gh repo archive` again.
That is the procedure the user gave on 2026-09-27 ("unarchive, push and archive back").

**Why:** the archive is deliberate: the repo is kept read-only on GitHub between pushes, and the
docs site is published from it by GitHub Pages, whose build runs on the push.

**How to apply:** raise the unarchive together with the push question, not as a separate decision,
and re-archive only after the Pages build has concluded, so the site deploy is not cut off.
