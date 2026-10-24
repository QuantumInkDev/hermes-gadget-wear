# Progress

Short milestone log. One or two lines per delivered change; details are in git history.
The previous long-form log (through 2026-10-24) is in git history before this commit.

- 2026-10-24: Consolidated the relay work onto `relay/delivery`, dropped the interrupted
  FIX4 edits, removed 139 stale worktrees and the bulky `.local` scratch data. Baseline:
  1,234 tests pass, 1 skipped; phone and watch debug APKs build.
- 2026-10-24: Installed relay/delivery debug builds on phone and watch (in-place, same signer, backups taken).
