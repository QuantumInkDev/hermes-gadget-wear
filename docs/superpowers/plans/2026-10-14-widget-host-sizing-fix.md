# Widget host sizing correction

The configured 1×1 Nova widget shows recovery even though its saved slot and roster entry
are valid. Its advertised portrait size is taller than the actual host content. Neither
advertised orientation fits, so Android selects the smallest-area variant: the short
landscape recovery. This was absent from the earlier exact-size host matrix.

1. Reproduce the real advertised-size/content-size mismatch with native AppWidgetHostView
   and a synthetic configured portrait. Require the bot, image and full-cell target; retain
   genuine undersize recovery. Watch the regression fail before changing the renderer.
2. Supply a minimum supported responsive variant alongside exact host variants. Preserve
   slot counts, 48-dp minimum targets, bitmap sharing, fixed/system themes and the platform's
   variant limit. Verify all presets, exact sizes, mismatches and undersized hosts.
3. Run strict checks/builds and affected native widget tests. Back up and install the
   matching-signer phone APK without resetting data or replacing the placed widget. Verify
   the actual Nova portrait and exact-profile launch, return to the launcher, and record
   public sanitized results plus private preservation receipts. Keep PR #1 draft.

No endpoint, pairing, conversation send, launcher grid/layout or personal artwork change
is part of this fix. Phone launcher inspection is explicitly authorized by the owner.

Completed at production source `b264796`. Two native regressions failed then passed;
12 affected native methods and strict checks (133 JVM /38 reports, all four APKs) pass.
The actual placed widget now shows its selected portrait, its PendingIntent matches that
selection and tapping opens Talk/Chat. All 104 files are preserved at installation, with
74 protected files/config unchanged after UI inspection. The phone is back on its launcher.
See `docs/widget-host-sizing-fix.md` for measured cause, evidence and retained limits.
