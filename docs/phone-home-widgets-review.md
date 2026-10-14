# Phone home-widget review and verification

The maintainer approved the [seven-layout design](superpowers/specs/2026-10-13-phone-home-widgets-design.md)
on 2026-10-13. The implementation follows its rows × columns convention, independent
configuration, clear portrait treatment, OS/fixed themes and exact-profile Talk/Chat launch.

## Fresh final review

One independent GPT-6 Astra reviewer inspected `da3784d..bdaf3af` read-only, including
the approved spec, plan, source, local receipts and recorded implementation decisions.
It found no Critical or Minor items and three Important items. The review verdict was
With fixes. The following single corrective pass addresses all three; no second review
is substituted for the recorded tests. The PR remains draft for physical/release gates.

1. **Restore-chain collision:** a partially rejected overlapping mapping could overwrite
   a source whose own move was blocked. A native regression reproduced lost state before
   the fix. Safe moves now prune to a fixed point before one atomic map write; blocked
   chains retain every original configuration while unrelated valid moves still complete.
2. **Busy navigation coverage:** real MainActivity/PendingIntent delivery now exercises
   sending, speaking, pairing, busy replies, listening, thinking and approval. The test
   injects the controller's observable state boundary, without opening a mic/socket or
   creating an identity. It checks preserved session/prompt/draft, disabled pending action,
   no automatic switch at idle, explicit opening, replacement and dismissal. Actual
   capture/playback remains covered independently by the stock TLS fixture.
3. **OS-theme transition coverage:** the same real AppWidgetHostView instances retain
   their previously published RemoteViews across actual OS day/night changes. A host
   content rebuild reinflates those views without a new render/provider publication.
   System background/text/accent change; Light/Dark/Moonlight stay fixed; the app's
   appearance is unchanged and the original OS mode is restored. This is independent
   of the still-open two-wallpaper test.

Early acceptance also corrected landscape caption/target geometry, dark resource
qualification, narrow editor targets, overlapping ID revisions, corrupt full-cache
replacement, current-endpoint resolution and concurrent configuration lookup. Their
negative receipts precede the corrected native runs. Fixture import/format failures,
missing server assumptions and inaccessible text-child checks are not counted as
product regression failures or passing acceptance.

## Evidence

The corrected run passes 125 JVM tests across 35 reports with zero failures/errors/skips,
all four APK builds, 32 affected widget/navigation/presentation native methods and the
blocked-watch publication method. The independent stock TLS audio/vault run covers five
more methods, giving 38 distinct native methods; the host matrix has 112 geometry cases.

One combined native run failed its initial cold-launch selection. The failure did not
reproduce in the 18-case controlled sequence or the final 32-case combined replay; diagnostic
assertions now retain lifecycle, payload-validity and unavailable-state information. No
product cause is assigned to that transient, and the failed receipt is retained.

Final source/APK-bound exports are in [the native gallery](demo/home-widgets/README.md)
and [progress](progress.md). Private raw commands, negative/positive instrumentation
receipts, strict build logs, signer/hash checks and the staged delivery APK remain under
ignored `.local/home-widgets/`. There is no personal art or endpoint in the public evidence.

The strict command uses the pinned stock SDK environment and the existing M6 extension
environment, which includes client speech; the older Opus-only environment cannot run
that integration fixture. All applicable checks use `--warning-mode=fail --max-workers=4`.

## Implementation decisions and costs

These preserve the execution ledger's chronological rulings, including its explicit
limits rather than silently marking untested work complete.

- Continue inline on the authorized feature branch; no duplicate worktree. Cost if wrong:
  the work remains on the existing isolated review branch.
- Use executing-plans with one fresh final reviewer, without per-task agents. Cost:
  defects rely on tests and the final review rather than per-task second opinions.
- Retain fresh check receipts instead of repeating identical tests for a wrapper. Cost:
  raw receipts, rather than that wrapper, establish the result.
- Finish orientation and whole-object bitmap acceptance in the final task. Cost:
  initial renderer acceptance was provisional; final geometry/budget checks now pass.
- Use the guarded owned phone emulator and preserve older fixtures. Cost:
  emulator evidence does not establish physical launcher behavior.
- Fall back to isolated ADB/native instrumentation after T3 device discovery failed. Cost:
  Device-panel observation was unavailable; no shared server or secure window was reset.
- Split preview/chooser and reuse palette mapping without nested window side effects.
  Cost: another focused file boundary, without changing application appearance ownership.
- Consume cold requests at actual Lifecycle RESUMED. Cost if wrong: late navigation;
  the real cold/warm regression passes.
- Use one bounded atomic map for at most 128 configs and preserve legacy per-ID debug
  data. Cost: shared-map corruption recovers all instances to setup; malformed input
  fails closed. Atomic overlapping/colliding restore and stale revisions are covered.
- Fence lookups with a process-local config-write epoch. Cost: an unrelated simultaneous
  edit may require retrying a shortcut; it cannot silently select a different bot.
- Prove coverage-only findings directly instead of deliberately breaking working
  production code to manufacture a negative test. Cost: controller-boundary tests do
  not replace actual mic/voice or physical launcher checks.
- Keep two real wallpaper palettes open because backup was unavailable; no mutation.
  Cost: wallpaper-specific behavior still needs device acceptance.
- Keep API 29–30 runtime open because no such image is installed. Cost: qualified
  fallback resources/lint cannot establish older-device runtime behavior.
- Keep physical install/placement/reboot open until the phone reconnects. Cost:
  Samsung launcher differences remain for physical testing.
- Exclude unchanged client redesign outside widget integration from this review. Cost:
  unrelated old issues are not rediscovered; affected navigation/audio regressions pass.

Deferred minors: none from this review. Signing, privacy hosting, Play publication,
physical speech/away replies, watch tile acceptance and quantitative battery gates
remain separate in [release preparation](release.md).
