# Android runtime coverage

Updated 2026-10-18. Minimum-version checks supplement modern Android testing. The phone
build has `minSdk = 29`; Wear has `minSdk = 33`; both target API 37. Android distinguishes
the [minimum supported runtime from the target behavior level](https://developer.android.com/build).
Neither a target declaration nor a passing JVM suite proves device behavior.

| Surface | Runtime | Current evidence | Remaining gate |
| --- | --- | --- | --- |
| Phone compatibility floor | Android 10 / API 29, owned ARM64 emulator | Quick actions: all 13 native methods pass. Settings follow-up `3ff89f6` has clean review, minimum/modern all-theme large-font Save evidence; integrated `e783611` | N6 actual maximum-tail helper fix2 and N8 minimum-runtime execution remain open |
| Modern phone emulator | Android 17 / API 37, owned ARM64 emulator | Settings corrections reviewed; N8 native health/export passes all themes, actual system font 1.8, copy/share and recreation/process cases | New walking audio and full feature acceptance; no physical routing inference |
| Physical Samsung phone | Android 17 / API 37, read directly from device | Exact reviewed `2284399` installed; 18 identity /55 art files unchanged, no missing originals and five-minute timeout retained | New native health/phone-speech UI and live voice/relay acceptance; install command error separately reconciled by current APK/data hashes |
| Wear compatibility floor | Android 13 / API 33 | Fixture `4166885` reviewed/integrated `e3afc20`: 26 affected methods, no skips; N8 health/footer/report passes both native methods in all themes/local large text | Watch health recreation/process instrumentation and physical power remain separate |
| Modern Wear emulator | Android 17 / API 37, round ARM64 emulator | Reviewed Portrait/Pet selector and admission-provenance native tests, all themes and round-layout captures; private real-PCM overlay fixture | Battery Saver capability/physical battery and full authenticated art acceptance remain open |
| Physical Watch Ultra | Android 16 / API 36, read directly from device | Reviewed b81564a preserving update; configured portrait/default and final Jump observed; isolated production-code codec checks | Current saved ADB port closed; N8 update, live pets/expressions/negotiated voice/relay/power remain open |

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
