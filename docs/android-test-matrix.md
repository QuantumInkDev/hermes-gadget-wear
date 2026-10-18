# Android runtime coverage

Updated 2026-10-18. Minimum-version checks supplement modern Android testing. The phone
build has `minSdk = 29`; Wear has `minSdk = 33`; both target API 37. Android distinguishes
the [minimum supported runtime from the target behavior level](https://developer.android.com/build).
Neither a target declaration nor a passing JVM suite proves device behavior.

| Surface | Runtime | Current evidence | Remaining gate |
| --- | --- | --- | --- |
| Phone compatibility floor | Android 10 / API 29, owned ARM64 emulator | Root `37aa867`: all 13 quick-action native methods pass, zero failures/skips; affected APK/lint/format checks pass | Corrected profile-aware speech settings, widgets and other new native features require their own affected checks |
| Modern phone emulator | Android 17 / API 37, owned ARM64 emulator | Settings candidate `5b73a3e`: 31 native executions / 28 distinct methods and 36 source-bound captures, zero failures/skips | Review found corrupt-sidecar preservation and large-font label defects; fix and fresh review are required before acceptance |
| Physical Samsung phone | Android 17 / API 37, read directly from the device | Quick-action installation/entry checks and isolated production-code codec checks are recorded in progress | Corrected speech settings preserving update, live voice/relay and remaining feature acceptance |
| Wear compatibility floor | Android 13 / API 33 | Declared minimum; no expansion runtime pass claimed | Owned minimum-version Wear execution remains open |
| Modern Wear emulator | Android 17 / API 37, round ARM64 emulator | Reviewed Portrait/Pet selector and admission-provenance native tests, all themes and round-layout captures | One explicit Battery Saver capability skip is still a physical gate; expression renderer work is separate |
| Physical Watch Ultra | Android 16 / API 36, read directly from the device | Reviewed `b81564a` preserving update; configured portrait, default Portrait mode and final Jump footer observed; isolated production-code codec checks | Live pets/expressions, negotiated voice/relay, power and remaining feature acceptance |

The physical runtime inventory is private under `.local/expansion/device-os-matrix.json`.
Source-bound test, APK and capture receipts remain in the per-task locations recorded in
`SESSION_STATUS.md` and `docs/progress.md`. Phone and Wear runtime release numbers above
come from `ro.build.version.release` and `ro.build.version.sdk`; they do not infer a
Samsung One UI or Wear OS product version.

For each new native increment, run the affected behavior on the supported floor and a
modern runtime, then the relevant physical-device acceptance. Exercise permissions,
foreground/background transitions, audio focus/routing, process recreation, keyboard,
large text and the three themes where that increment changes those behaviors. Test real
associated-device delivery separately from emulators without the Wear Data Layer API.

Keep runtime, feature and source identifiers together. A quick-action pass does not
establish speech, widgets, history, pets or battery acceptance, and a pre-fix receipt does
not validate its corrected successor. Explicit capability skips remain open gates.
