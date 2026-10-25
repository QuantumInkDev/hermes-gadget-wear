# Progress

Short milestone log. One or two lines per delivered change; details are in git history.
The previous long-form log (through 2026-10-24) is in git history before this commit.

- 2026-10-24: Consolidated the relay work onto `relay/delivery`, dropped the interrupted
  FIX4 edits, removed 139 stale worktrees and the bulky `.local` scratch data. Baseline:
  1,234 tests pass, 1 skipped; phone and watch debug APKs build.
- 2026-10-24: Installed relay/delivery debug builds on phone and watch (in-place, same signer, backups taken).
- 2026-10-24: Fleet updated to Hermes v0.22.5. m5 server live: pets/Opus/client speech (all profiles), relay history/inbox/outbox (Cthulhu, Kitana, Akuma, Viper), remote wake (root). Fixed one-word watch speech (edit-stream preview). Added N3 sharing, N5 walking mode, N7 handoff, N9 wake client; installed on phone and watch.
