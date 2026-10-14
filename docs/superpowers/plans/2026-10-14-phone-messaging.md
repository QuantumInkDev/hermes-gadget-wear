# Phone messaging refinement implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Deliver the approved compact voice-message composer, clear hierarchy, usable picture sheet and original Courier icon in a preserving phone update.

**Architecture:** Keep PhoneConversation and the existing protocol/audio adapters as the guarded connection boundary. Separate the composer's gesture ownership from changing levels and timers; reuse one native picture sheet from Directory and Catalog. Prepare layered Courier launcher resources in the existing shared client module.

**Tech Stack:** Kotlin, existing Compose Material 3 BOM 2026.09.00, Android API 29–37, JUnit and existing native instrumentation fixtures; no new runtime dependencies.

**Spec:** [Approved design](../specs/2026-10-14-phone-messaging-design.md).

## Global constraints

- Hold to record, release to send, slide left to cancel. Left threshold 64 dp, short press under 250 ms cancels, exactly one terminal action.
- Full phone title: Quantum Relay for Hermes Agents. One line, scalable/manual horizontal scroll when necessary; title larger than destination headings.
- Live microphone amplitude history; actual playback levels remain separate. No fabricated signal in the shipped app, no PCM retained for drawing.
- Minimum 48-dp controls; three existing themes, keyboard/insets, large fonts and accessibility Start/Finish/Cancel.
- Keep identities, drafts, artwork/GIF precedence, profile keys, stock SDK v0.2.0 and protocol unchanged.
- Original Courier A; adaptive foreground/background and monochrome. No personal or upstream mascot art.
- No physical phone UI takeover; preserving matching-signer installation only. Keep PR draft.

## Task 1: Compact composer and bounded waveform

**Files:**
- Create `mobile/src/main/kotlin/dev/quantumink/hermesgadget/mobile/VoiceMessageGesture.kt` and `AudioLevelHistory.kt`.
- Modify `ConversationControls.kt`, `AudioWaveform.kt`, `PhoneConversation.kt`, `ConversationPage.kt` in that directory.
- Create `mobile/src/test/kotlin/dev/quantumink/hermesgadget/mobile/VoiceMessageGestureTest.kt` and `AudioLevelHistoryTest.kt`.
- Create `mobile/src/androidTest/kotlin/dev/quantumink/hermesgadget/mobile/PhoneMessagingTest.kt`; update affected `ConversationPresentationTest.kt` selectors.

**Interfaces:** `VoiceMessageGesture.begin(now: Long): Boolean`, `move(left: Float, vertical: Float): Boolean`, `end(now: Long): VoiceMessageOutcome?`, `cancel(): VoiceMessageOutcome?`. Outcomes SEND/CANCEL. `AudioLevelHistory.add(level: Float)` exposes a bounded snapshot for drawing. UI callbacks remain `record`, `finishRecording`, `cancelRecording`; real capture-start state comes from PhoneConversation.

- [x] Write behavior regressions: left slide remains cancelled after returning right; short release cancels; repeated end/cancel emits nothing; vertical movement does not trigger left cancel. Assert literal outcomes, not source text.

```kotlin
val gesture = VoiceMessageGesture(64f)
assertTrue(gesture.begin(100))
assertTrue(gesture.move(70f, 2f))
assertEquals(VoiceMessageOutcome.CANCEL, gesture.end(800))
assertEquals(null, gesture.end(900))
```

- [x] Run `:mobile:testDebugUnitTest` with the new classes; expect a missing-feature failure before implementation. Implement ownership, short-press and finite/bounded history, then pass those tests.
- [x] Replace the stacked controls with one baseline field and 56-dp mic/Send (native field alignment; recorded decision). Use a stable gesture handler keyed by profile/session, rememberUpdatedState callbacks, a visible Cancel while listening and semantic Start/Finish/Cancel. Pointer/lifecycle cancellation must terminate once. Represent actual capture startup separately from LISTENING.

```kotlin
when (gesture.end(android.os.SystemClock.uptimeMillis())) {
    VoiceMessageOutcome.SEND -> finish()
    VoiceMessageOutcome.CANCEL -> cancel()
    null -> Unit
}
```

- [x] Feed the streaming meter from existing `conversation.level`; use bounded normalized history at 50-ms intervals. Show baseline before capture/silence, static fallback under existing motion policy, reset by composition/session ownership. Preserve real reply waveform, Cancel turn, Stop voice and approvals.
- [x] Exercise native pointer input, accessibility actions, IME/draft Send, level recomposition and cancellation using the existing synthetic fixture. Expected: one start and one terminal action, no send after cancellation; controls remain visible above keyboard.
- [x] Commit `feat: refine phone voice-message composer and live waveform` after the focused JVM/native gate passes.

## Task 2: Header hierarchy and shared picture sheet

**Files:** modify `PhoneBrandHeader.kt`, `PhoneSettingsPage.kt`, `DirectoryPage.kt`, `CatalogPage.kt`, `mobile/src/main/res/values/strings.xml`; create `ProfilePictureSheet.kt` in the phone source directory. Extend `PhoneMessagingTest.kt`.

**Interfaces:** `ProfilePictureSheet(repository, page, busy, dismiss, choose, restore)` is shared by both entry points. It receives the current stable-key page; photo results continue through `selectWatchPhoto(key, bytes)`. Header has full-width one-line title; no inline icon/subtitle.

- [x] Add native tests that open the picture chooser and require a reachable footer Cancel with large fonts/small height. Exercise cancellation without a write and normal restore. Record the current clipping/missing sheet failure before replacing the dialog.
- [x] Implement Material 3 ModalBottomSheet, constrained scroll body and separate footer; hide IME/clear focus on open. Current portrait/name and full-width Choose picture/Use box picture or Use default picture precede Cancel. Resolve Catalog's stored page by current key; abandon stale imports safely.

```kotlin
pages.singleOrNull { it.key == pictureKey }?.let { page ->
    ProfilePictureSheet(repository, page, busy, { pictureKey = null }, choose, restore)
}
```

- [x] Use full phone name in header/launcher/About. Use a scalable 18-sp minimum title role with full semantics/manual scroll when necessary; destination headings use titleMedium. Keep conversation profile toolbar.
- [x] Verify 320/360 dp, large font, landscape, three themes and IME. Expected: footer inside visible sheet, title larger than section heading, no clipped or off-screen action.
- [x] Commit `fix: clarify phone headings and profile picture controls` after native acceptance.

## Task 3: Production Courier icon

**Files:** create `docs/store-assets/courier/` masters/prompt/manifest; replace existing launcher resources under `client/src/main/res/` and About icon credit. Retain design concept unchanged.

**Interfaces:** preserve existing resource names `ic_quantumink_relay`, `_round`, `quantumink_relay_foreground`, `_monochrome` so phone/watch references remain compatible.

- [x] Use built-in image generation to remove only the selected concept's background and prepare a transparent foreground, preserving the approved face/headset/style. Keep original source and prompt in project assets. Raster export is deterministic resizing/compositing, not redrawing.
- [x] Fit the essential silhouette inside adaptive safe bounds over midnight blue; provide true alpha foreground and a dedicated single-color recognizable head/headset silhouette for OS theming. Export existing density/legacy resources and retain provenance/hashes.
- [x] Render round, squircle and monochrome at launcher size; inspect native resolved drawables and 48-pixel output. Expected: recognizable Courier face/headset with no essential clipping or old Q fallback.
- [x] Commit `feat: brand clients with original Courier launcher icon` after resource compilation and mask inspection.

## Task 4: Acceptance, review and preserving delivery

**Files:** synthetic gallery `docs/demo/phone-messaging/`, `docs/progress.md`, `SESSION_STATUS.md`, relevant PRD/architecture presentation notes; private logs/backups/ledger stay ignored.

- [x] Run warning-strict `check`, both debug apps and both instrumentation APK builds with the existing SDK/JDK and pinned Python fixture environments. Expected zero failures/errors/skips in applicable JVM reports and no warnings.
- [x] Run scoped native composer/picture/theme tests and existing conversation audio/vault acceptance against stock TLS fixtures on an owned emulator. Preserve original emulator settings and stop only a newly owned emulator. Export raw synthetic captures with source/APK hashes.
- [x] Obtain one fresh whole-change review, focused on the input/lifecycle boundaries below. Re-grade findings; fix Critical/Important issues in one test-backed pass, keep suite green. Document remaining material limitations.
- [x] Back up exact paired phone APK/data; compare certificate, install `-r`, verify installed APK SHA and unchanged identities/artwork/preferences/timeout. No uninstall, launch, reset, re-pairing or private screenshot. If phone is absent, finish all software work and stage the APK with honest pending-install status.
- [x] Update docs/plan with actual evidence, commit/push existing draft PR, link/unwatch it and report delivered versus owner-observation gates.

## Review focus

Delayed permission/start callbacks after release; gesture cancellation during level recomposition,
connection/profile/lifecycle changes; end/cancel idempotence; accessibility action bypasses;
multitouch; keyboard-only sends; waveform history/privacy/motion reset; sheet footer/insets at
large fonts/landscape; stable-key picture import after withdrawal/reorder; current override/GIF
precedence; adaptive/monochrome fallbacks; preserving installation/signing and private-art hygiene.

## Verification commands

Use `JAVA_HOME=/Users/quantumdev/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.17+10/Contents/Home`, `ANDROID_HOME=/Users/quantumdev/Library/Android/sdk`.
For the full suite also set `HERMES_GADGET_PYTHON=$PWD/.local/venv/bin/python` and `HERMES_GADGET_EXTENSION_PYTHON=$PWD/.local/extension-venv-m6/bin/python`.

```sh
./gradlew ktlintFormat check :mobile:assembleDebug :wear:assembleDebug :mobile:assembleDebugAndroidTest :wear:assembleDebugAndroidTest --warning-mode=fail --max-workers=4
```

Native runs use the explicit owned emulator serial, `am instrument -w -r -e class ...`, and
must reject assumptions/skips. Never run isolated-vault instrumentation against the owner's phone.

## Completion receipt — 2026-10-14

Completed through production source `d26e790` and stock fixture timing `74a7ddf`.
Strict checks: 133 JVM /38 reports, no failures/errors/skips; all four APKs.
Native: 42 final executions /30 distinct methods, 39 original gallery PNGs.
Preserving physical phone installation verifies unchanged 102 files /18 identities /54 art
files, timeout and seven widget providers. One independent review, three Important fixes,
no second review or deferred minors. The existing PR stays draft; physical speech/gesture
comfort and release gates remain. See progress.md and phone-messaging-review.md.
