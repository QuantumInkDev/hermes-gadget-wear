# Widget host sizing correction — 2026-10-14

An existing configured 1×1 widget on Nova displayed Open widget settings instead of its
selected bot. The saved slot, roster and portrait were valid; repeating setup was unnecessary.

The host advertised a 60.44×76.8-dp portrait layout and a 126.22×30.58-dp landscape layout,
but its actual portrait content was about 60.44×66.13 dp. Neither advertised variant fit.
Android therefore selected the smallest-area variant, which was the landscape recovery.
The earlier host tests supplied exact sizes and did not cover this discrepancy.

The adaptive map now includes a minimum supported layout of 48 dp per cell and a separate
tiny recovery layout, plus the host's exact variants. The supported minimum can fit a valid
placement between the advertised sizes. Truly undersized content retains recovery. Two
variant slots are reserved within the existing sixteen-variant limit; decoded portraits
remain shared across variants and within the existing bitmap budget. No configuration or
roster format changed.

The platform behavior is described in [Android flexible widget layouts](https://developer.android.com/develop/ui/views/appwidgets/layouts)
and the primary [RemoteViews implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/android/widget/RemoteViews.java).
The installed SDK source was also inspected. The numeric host mismatch was measured on the
actual device, rather than inferred from that documentation.

## Verification

- Two native regressions failed before the renderer fix: the actual host-size mismatch,
  and minimum target geometry with unadvertised undersized recovery.
- Twelve affected native host/render methods pass without failures or assumptions. New
  coverage includes all seven presets and four appearances at their supported minimum and
  below it (56 combinations), sixteen advertised variants, visible configured artwork and
  click targets. Existing geometry, system-night reinflation and shared bitmap-budget checks
  also pass. An additional launch fixture declined to overwrite existing emulator setup;
  it is retained as a skip and is not counted among these twelve passes.
- Warning-strict full checks and all four APK builds pass: 133 JVM tests /38 reports, zero
  failures/errors/skips. The pure protocol module is unchanged.
- Production build `b264796` was installed on the actual phone after current APK/data backups
  and matching certificate/hash checks. All 104 baseline files were unchanged at installation,
  including 18 identities and 55 artwork files. Timeout and seven providers remain unchanged.
- The existing Nova widget now visibly contains its chosen portrait/caption. Its PendingIntent
  matches the saved selected profile, and tapping it opens foreground Talk/Chat. The widget
  configuration is byte-for-byte unchanged; no re-adding, resizing, pairing or message was
  required. All 74 protected identity/artwork/config files remain unchanged after inspection.
  The phone was returned to its launcher.

Phone captures and device-specific records remain private under `.local/widget-launcher-fix/`.
No personal image is published. The newly booted test emulator is stopped after verification,
with its prior data retained. The physical watch is unchanged. Other physical widget sizes,
launchers, wallpaper changes and remaining release gates retain their earlier status.

## Owner follow-up: image-only Focus

Production `acfe414` removes the visible caption when Focus artwork is present and lets the
portrait fill the space. Accessible name/source and missing-art recovery remain. Larger
layouts keep their captions. All twelve affected native methods and strict checks/all four
APKs pass. The preserving phone update retains all 104 baseline files, 18 identities, 55
artwork files and configuration. The phone was locked at final visual inspection; the new
image-only appearance is native-tested but not claimed as physically read back. Four public
synthetic Focus exports have refreshed per-asset hashes. Private receipts stay ignored.
