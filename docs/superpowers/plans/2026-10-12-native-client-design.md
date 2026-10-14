# Native clients, appearance and profile tiles implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved Portrait studio native phone/watch presentation, original Voice Q brand, three appearances and one/four-profile tiles without disturbing working conversations.

**Architecture:** Shared Android appearance tokens/preferences feed separate phone and Wear Material adapters. Presentation consumes existing repositories/reducers; watch tile configuration uses stable keys, local static art and a watch-owned revision. Existing Gadget wire and identity boundaries remain intact.

**Tech Stack:** Kotlin/JVM protocol, Android client library, Compose Material 3, Wear Compose Material 3, ProtoLayout/Tiles, Play Services Wearable, JUnit and native Compose instrumentation.

**Spec:** `docs/superpowers/specs/2026-10-12-native-client-design.md`, approved 2026-10-12.
**Visual reference:** `docs/design/native-client-preview.html`, Portrait studio / Voice Q.

**Software checkpoint:** Tasks 1–6 and 8 plus Task 7 software checks/reviews pass at `c248b03`
with gallery/evidence `6b00947`. See [review resolutions](../../native-client-review.md) and
[actual native captures](../../demo/native-clients/README.md). Checked test steps describe
recorded software/fixture work, including explicitly unavailable transport/saver capabilities;
they do not assert physical acceptance. Device delivery/configuration gates below remain open.

## Global Constraints

- Keep protocol free of Android imports and preserve stock Hermes Gadget SDK v0.2.0 wire behavior.
- Existing app/package IDs and matching signing identity remain unchanged. No uninstall, clear-data, identity copying or vault seeding of physical/personal installs, and no screenshot-security bypass. The existing guarded synthetic-only carousel harness may reset only the verified owned Hermes_M2 emulator and create localhost fixture state; it must exclude physical devices. Native phone navigation may use a separately guarded clean stock-image fixture with only reserved synthetic endpoints; ambiguous existing app data must remain untouched and no identities/user data may be copied.
- Never commit personal endpoints, tailnet details, credentials, signing keys or personal art; maintainer material stays in ignored `.local/`.
- Light, Dark and Electronic Moonlight persist independently on phone/watch; existing installs default to Dark. Explicit palettes are not overridden by wallpaper colors.
- Normal text contrast targets 4.5:1; large text/essential boundaries target 3:1. Phone background/surface/main text/secondary/accent/on-accent values are exactly the approved spec table.
- Dark/Moonlight watch canvases are black; Light is a pale interactive canvas. Ambient stays black, static and shifted, with existing pet/portrait rules.
- Preserve tested pager mounting, stable page keys, per-page vertical position/drafts, rotary focus and recording/turn/approval selection locks.
- Tiles use static local pictures and cannot initiate network work, recording or agent actions while rendering. Minimum tile/control targets are 48 dp and non-overlapping.
- Pins contain stable keys: one Focus and four ordered deduplicated grid slots. Removed/offline keys never silently redirect to an index neighbor.
- Watch owns durable configuration revision. Phone edits carry acknowledged base revision/request ID; stale edits are rejected and duplicate requests are idempotent.
- A tile launch carries only a validated stable key. Cold/warm launches select the intended page after binding; locked selection is deferred visibly. Recording always requires a foreground gesture.
- No live server extension, merge, release signing, Play upload or account messaging is authorized. Preserve physical acceptance distinctions.

## Task 1: Shared appearances and original Voice Q branding

**Files:** Create `client/src/main/kotlin/dev/quantumink/hermesgadget/client/Appearance.kt`, `AppearancePreferences.kt`; create `mobile/src/main/kotlin/dev/quantumink/hermesgadget/mobile/PhoneAppearance.kt`, `AppearancePage.kt`; create `wear/src/main/kotlin/dev/quantumink/hermesgadget/wear/WatchAppearance.kt`; modify each MainActivity theme wrapper, PhoneSettingsPage, PhoneScaffold and existing client brand resources; add original masters under `docs/store-assets/voice-q/`; add client JVM tests and native appearance tests in both app test trees. Modify `client/build.gradle.kts` only for required test dependencies.

**Interfaces:** `enum class Appearance { LIGHT, DARK, MOONLIGHT }`; `data class AppearanceTokens` with ARGB Int semantic roles; `fun appearanceTokens(mode: Appearance, watch: Boolean = false): AppearanceTokens`; `AppearancePreferences(context)` exposes `val mode: StateFlow<Appearance>` and `fun set(mode: Appearance)`; `PhoneAppearance(mode, content)` and `WatchAppearance(mode, content)` wrap their own MaterialTheme; `AppearancePage(context, back)` is a native chooser. New roles may extend the token data class as needed by actual Material schemes, without changing these entry points.

- [x] Write tests before implementation. Contrast must be computed from the actual tokens, not copied expected ratios. Check all three text/action pairs, persisted mode after a fresh preferences instance and untouched unrelated preferences. Native tests probe actual foreground/container relationships through each theme wrapper.
  ```kotlin
  Appearance.entries.forEach { mode ->
      val palette = appearanceTokens(mode)
      assertTrue(contrast(palette.text, palette.background) >= 4.5)
      assertTrue(contrast(palette.secondaryText, palette.surface) >= 4.5)
      assertTrue(contrast(palette.onAccent, palette.accent) >= 4.5)
  }
  ```
- [x] Run `./gradlew :client:testDebugUnitTest :mobile:compileDebugAndroidTestKotlin :wear:compileDebugAndroidTestKotlin --warning-mode=fail --max-workers=4`; record the expected missing appearance capability/contrast regression before implementing. Native runtime follows on owned emulators; do not use the personal phone for instrumentation.
- [x] Implement the token mappings from the spec, durable preferences and native adapters. Use complete semantic ColorScheme roles, with explicit Scaffold content/container colors; remove UI hard-coded green/custom dark surfaces only where they defeat theme inheritance. Add Settings→Appearance and a watch appearance entry without changing connection callbacks. Each device keeps its independent choice.
  ```kotlin
  enum class Appearance { LIGHT, DARK, MOONLIGHT }
  // Shared tokens are ARGB integers; Compose Color/ColorScheme adaptation belongs in each app.
  // Preferences use a dedicated appearance file, default DARK, and publish after durable save.
  ```
- [x] Replace the original client icon resource artwork with the approved original Voice Q geometry, preserve resource names used by manifests, and add matching monochrome/adaptive/legacy resources. Essential foreground stays within the adaptive safe area. SVG masters and a manifest identify original geometry; no personal avatars enter these assets.
  ```xml
  <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
      <background android:drawable="@color/quantumink_relay_background" />
      <foreground android:drawable="@drawable/quantumink_relay_foreground" />
      <monochrome android:drawable="@drawable/quantumink_relay_monochrome" />
  </adaptive-icon>
  ```
- [x] Run focused tests, then `./gradlew check :mobile:assembleDebug :wear:assembleDebug --warning-mode=fail --max-workers=4` once with the repository's existing Python test environments. Keep logs in this plan workspace; commit only Task 1 files and report RED/GREEN, visual limitations and interfaces.

## Task 2: Native portrait directory and navigation chrome

**Files:** Modify `mobile/.../DirectoryPage.kt`, `DirectoryBoxHeader.kt`, `PhoneScaffold.kt`, `MainActivity.kt`, `PhoneSettingsPage.kt`, and original navigation/icon resources; create focused `ProfileRow.kt`, `ProfileActions.kt`, `PhoneBrandHeader.kt` under the mobile package. Extend `mobile/src/androidTest/.../DirectoryPresentationTest.kt` and existing directory tests.

**Interfaces:** Consume Task 1 `PhoneAppearance` and semantic scheme. Keep `DirectoryPage(context, onTalk, onBoxes, onManual, modifier)` signature and CatalogRepository operations. Export `PhoneBrandHeader(title, subtitle, actions)` for native app bars; picture/detail/retire/order functions retain existing repository contracts. MainActivity continues to own destination/lifecycle and Connect/record callbacks.

- [x] First add native tests for full-row conversation entry, profile overflow access to picture/reset/details, independent collapse and search expansion, and custom ordering persistence. Keep real repository fixtures; do not replace operations with toast/no-op controls.
  ```kotlin
  compose.onNodeWithContentDescription("Open Alpha Talk and Chat").performClick()
  compose.onNodeWithContentDescription("Options for Alpha").performClick()
  compose.onNodeWithText("Watch picture").assertIsDisplayed()
  ```
- [x] Compile/run the focused native cases on an owned emulator and record missing entry/menu failures before UI changes. Existing stable-order/picture/vault cases must remain passing after the presentation change.
- [x] Implement portrait-led compact rows, coherent headline/app bar, tonal surfaces, grouped count/catalog status and an overflow replacing the permanent button cluster. All Refresh/Reorder/Manage boxes/Manual setup and picture override/reset/details/retire actions remain reachable. Native search uses the actual query; offline/empty/error states retain art/order and recovery actions. Larger text wraps rather than truncating essential actions.
  ```kotlin
  // Profile row primary click invokes onTalk(page); its separate labeled overflow opens actions.
  // Expanded state remains keyed by source ID; list items and drafts remain keyed by page.key.
  ```
- [x] Run targeted directory/order tests, theme/large-font render checks, and strict mobile build/lint. Commit only Task 2 changes, with an honest fixture/native rendering report.

## Task 3: Native foreground conversation, relay dashboard and settings

**Files:** Modify `mobile/.../ConversationPage.kt`, `MainActivity.kt`, `PhoneSettingsPage.kt`, `SpeechSettingsPage.kt`, `ProfilesPage.kt`, `CatalogPage.kt`; create focused `WatchDashboard.kt`, `ConversationControls.kt` and `AudioWaveform.kt`; modify `PhoneConversation.kt` only for guarded playback-level/mute presentation state. Existing PCM recording levels already come from ConversationState.level. Extend `PhoneConversationTest.kt`, `DirectoryPresentationTest.kt` and native presentation fixtures.

**Interfaces:** Preserve `ConversationPage(model, page, connect, record, back, modifier)`, PhoneConversation methods/state and foreground lifecycle. Extend guarded presentation state with bounded real capture/playback levels for the owner-requested waveforms. Existing PCM ConversationState.level and PcmPlayback onLevel callbacks are the source; no new audio session/controller or stored recording is introduced. `WatchDashboard` consumes enabled/status and existing enable/disable/associate/stop callbacks; it does not own or replace the relay controller. Task 6 adds tile configuration to this dashboard. Appearance chooser from Task 1 remains reachable.

- [x] First cover actual composer/send, permission/review/pairing visibility, listening/cancel/Stop voice through playback, and Back/background/lock termination. Reuse controlled TLS server/playback fixtures for concurrency; show native content at keyboard/large-font sizes.
  ```kotlin
  // During actual PCM playback:
  compose.onNodeWithText("Stop voice").assertIsEnabled().performClick()
  assertFalse(model.state.value.speaking)
  // Leaving via native Back preserves the draft and closes foreground socket/audio work.
  ```
- [x] Record expected missing/native-layout failures without weakening existing phone-conversation regression assertions.
- [x] Build a compact profile toolbar, text conversation, comfortable typed composer and prominent hold-to-talk surface. Setup remains accessible but does not dominate ready conversations. Preserve live transcript/reply/card/image/prompt/error rendering, approval guard, downward/sliding cancellation, lifecycle shutdown and per-endpoint isolation. Restyle relay and all setup/settings surfaces using the same semantic scheme; no generic raw green/dark containers remain.
  ```kotlin
  // Native UI delegates Connect/record/finish/cancel/send/answer/stop directly to existing callbacks.
  // No new network, microphone or lifecycle controller is introduced for presentation.
  ```
- [x] Add real level-driven capture and response waveforms, guarded by session/playback versions; reset on cancel/stop/end/background/profile change. Reduced motion and power saver show static meters. Cover stale callbacks, real nonzero PCM envelope, lifecycle reset and media-muted hint in focused tests. Preserve the profile’s existing server speech voice. Owner reported silence; read-only physical evidence found STREAM_MUSIC speaker volume 0/15, so verify with owner after raising volume before inferring a TTS defect. Never alter media volume automatically.
- [x] Run actual phone TLS/playback cases and native theme/inset/permission presentations, then strict mobile checks/build. Commit Task 3 and identify remaining tile dashboard work as Task 6, without showing unfinished controls to users.

## Task 4: Swipe-first watch portrait, page indicator and profile picker

**Files:** Modify `wear/.../ProfileCarousel.kt`, `WatchScreen.kt`, `WatchNavigation.kt`; create focused `ProfilePicker.kt` and `ProfilePageIndicator.kt`; extend existing carousel/native surface tests under `wear/src/androidTest/`.

**Interfaces:** Retain existing ProfileCarousel service/pager contracts. `ProfilePicker(pages, selectedKey, locked, select, close, pin)` lists stable pages; `pin` is supplied by Task 5 when the store exists. `ProfilePageIndicator(index, count)` draws a bounded indicator and exposes position/count semantics. Until Task 5 the picker only selects; never show a no-op Pin control.

- [x] Add tests before removing controls: no visual Previous/Next buttons, accessible count, picker selection by stable key, locked/deferred behavior and per-page scroll retention through return/refresh/cancelled drag. At least one case uses eighteen synthetic pages and larger fonts.
  ```kotlin
  compose.onNodeWithContentDescription("Choose a profile").performClick()
  compose.onNodeWithText("Profile 17").performClick()
  assertEquals(expectedStableKey, service.state.value.selectedPage)
  ```
- [x] Observe the pre-change missing picker/visible arrow failure, then implement native round rows and at-most-six indicator marks. Remove PageControls buttons, retain the actual tested pager/focus hierarchy and mounted conversation list. Add named accessible selection actions with existing locks. Preserve 160-dp portrait, clock relationship, ambient rules and the current hold/release/cancel recorder semantics.
  ```kotlin
  // Selection uses stable page key through the existing service operation, never a cached index.
  // Overlay changes do not conditionally replace the conversation's vertical scrolling content.
  ```
- [x] Run complete carousel and vertical-touch/return/refresh/abort regressions, picker/large-font rendering and strict Wear checks/build. Commit Task 4 with no physical claim until the maintainer sees it on the Ultra.

## Task 5: Durable one/four-profile tile store, rendering and launch routing

**Files:** Create pure `protocol/.../TilePins.kt` and tests; create `wear/.../TilePinStore.kt`, `ProfileTileSnapshot.kt`, `ProfileTileLayout.kt`, `PinnedProfilesTileService.kt`, `TileProfileLaunch.kt`; modify `GadgetTileService.kt`, `WatchSurfaces.kt`, `MainActivity.kt`, `ProfilePicker.kt`, manifest and tile preview resources. Add focused Wear tile/storage/launch tests.

**Interfaces:** `data class TilePins(revision: Long, focus: String?, grid: List<String?>)`; `data class TilePinEdit(baseRevision: Long, requestId: String, focus: String?, grid: List<String?>)`; `TilePinStore.read(): TilePins`, `editLocal(focus, grid): TilePins`, `apply(edit, knownKeys): TilePinResult` (accepted/current/conflict/rejected results); `TileProfileLaunch` validates a stable key and routes/defer-selects after binding. The store owns watch revision and idempotence. Task 6 consumes these contracts.

- [x] Write pure CAS/ordering/duplicate/capacity/membership tests and native durability/layout/launch tests first. At most four grid slots, no duplicate non-null keys, no unknown injected key, stale base rejected, duplicate accepted request returns the same revision without reapplying, and removed keys remain visibly unavailable rather than selecting another index.
  ```kotlin
  val newer = store.editLocal("a", listOf("a", "b", null, null))
  val result = store.apply(TilePinEdit(newer.revision - 1, "old-request", "b", emptyList()), setOf("a", "b"))
  assertEquals(newer, store.read())
  assertTrue(result is TilePinResult.Conflict)
  ```
- [x] Observe failures before implementing bounded atomic persistence and static snapshot/layout builders. The actual local normalized PNG is downsampled/bounded for tile resources with versions derived from current pins/art. Render zero/one/four entries, missing art and unavailable/offline keys in safe circles with separated 48-dp targets. Do not perform network work or animate/decode GIFs in tile rendering.
  ```kotlin
  // LaunchAction contains one stable page-key string extra and no endpoint/key/token.
  // GadgetTileService remains the installed Focus component; a new provider supplies the grid.
  ```
- [x] Handle onCreate/onNewIntent, service attachment, unknown keys and selection locks with visible deferred target behavior. Add local watch pin editing via the picker; update both tile providers on pin/art changes. Keep normal Connect/review and microphone gestures explicit.
- [x] Run pure/native tile, cold/warm launch, unknown/removed/deferred selection, resource invalidation and no-side-effect cases, plus existing surface/carousel checks; strict Wear builds. Commit Task 5 and document that actual launcher addition is still a physical gate.

## Task 6: Phone tile composer and associated Data Layer synchronization

**Files:** Create `mobile/.../ProfileTilesPage.kt`, `TilePinSync.kt`; create `wear/.../TilePinSync.kt`; modify mobile `WatchDashboard.kt`, `RelayService.kt`, manifest listeners if necessary and Wear service/listener entry points. Extend pure tile tests and associated-phone/watch native fixtures.

**Interfaces:** Consume Task 5 TilePins/TilePinEdit/TilePinStore. Define a separate bounded versioned configuration item/message path; payload includes request ID, base revision and stable slots only. Phone state exposes acknowledged current pins, pending edit and conflict for ProfileTilesPage. Watch response carries request ID/result/current revision/pins. Authenticated associated peers/signatures are checked through the existing Wear boundary.

- [x] First test offline queued edit, reconnection delivery, duplicate delivery, reordered ack, concurrent watch edit, unknown key/capacity rejection and unrelated peer rejection. Use two real associated owned emulators for the Data Layer acceptance path when available; unit message tests alone do not prove delivery.
  ```kotlin
  // Phone submits revision R; watch edits locally to R+1 before delivery.
  // Deliver the old edit: Conflict is shown and watch R+1 pins remain unchanged.
  // Deliver its acknowledgement twice: no newer phone request is retired accidentally.
  ```
- [x] Implement complete native Focus/grid slot chooser, reorder/replace/remove and pending/conflict/retry states in Watch→Profile tiles. Cache only the acknowledged roster/revision; verify current watch membership before acceptance. Bounded serialization rejects malformed/version/oversize input. Local watch edits work without phone connectivity; no vault/credential data is sent.
  ```kotlin
  // Apply only edit.baseRevision == current.revision; commit pins plus revision atomically.
  // A conflict is reviewed explicitly; timestamps never resolve revision ordering.
  ```
- [x] Run synchronization fixtures and native composer interactions with actual static pictures, then strict both-client checks/builds. Commit Task 6, with any transport/environment limitation explicit rather than marked passed.

## Task 8: Portrait Hold to talk, recording feedback and bounded reply visibility

Execute this maintainer-requested follow-up after Task 6 review and before Task 7 final
acceptance. The task number preserves existing review/receipt identities.

**Files:** Modify Wear `WatchScreen.kt`, `ProfileCarousel.kt`, `MainActivity.kt`,
`GadgetService.kt` only where required; add focused Wear recording portrait and foreground
screen-policy helpers/tests. A small configurable haptic policy may extend shared client
`RecordingCues.kt`, with its existing phone behavior retained. Add native tests using the
existing non-exported synthetic host and actual MainActivity/stock localhost server fixtures.

**Interfaces:** Consume existing start/finish/cancel callbacks, actual ConversationMode/capture
state, generation-safe RecordingFeedback and existing GIF/pet rendering. Preserve Gadget
protocol, pairing, media codecs, pager ownership, app signatures and pin APIs.

- [x] First reproduce the requested missing portrait action and the foreground screen-sleep
  risk with meaningful failing native tests. Read the verified content-free private server/
  watch lifecycle summary in `.local/native-design/away-reply-diagnostics/`; it establishes
  generated replies and short sleeps, not frame receipt or a proved network root cause.
- [x] Make the portrait/artwork itself the discoverable hold target: sustained hold starts,
  release sends, downward drag cancels once. Short taps and pre-hold horizontal/vertical drags
  never record. Preserve normal swipes, per-page scroll, selection locks, prompt actions,
  permission denial, unavailable/offline states and pointer cancellation/disposal. Keep real
  accessible Start/Finish semantics and an immediate concise hint without duplicate captures.
- [x] Draw three concentric accent rings flowing outward only while actual recording is
  active. Keep existing160dp portrait/top spacing. The rings fit the round safe area and never
  cover clock/labels/controls. Keep160dp art/top; rings extend81→88dp into surrounding
  space without moving the original168dp frame or its labels. Reuse the existing Ready/status
  line for Hold/Release, plus a compact visible mic affordance and native Start/Finish
  semantics; do not insert a new pre-label text row or weaken existing source-readiness
  assertions. Reduced motion/power saver use a static outline/level cue;
  stopped/background/ambient/unselected pages have no continuous ring animation. Verify static,
  GIF and pet branches and all three appearances.
- [x] Add a watch-only verylight haptic tick at actual capture start and successful recorder
  release/accepted finish, one per token. Use supported TICK primitive at0.15 scale; otherwise
  use an8ms amplitude32 pulse only with amplitude control, otherwise a supported EFFECT_TICK
  or a6ms default fallback. Respect system haptic enablement. Do not create a repeating haptic,
  change reply/prompt haptics, sound preference or phone cues, or falsely tick success after
  rejected/short/canceled/stale capture. Retain the joined RecordingFeedback outcome guards.
- [x] Implement foreground-only FLAG_KEEP_SCREEN_ON while LISTENING, busy processing,
  speechPending or speaking, with a120000ms monotonic cap per user-started conversation.
  Clear on completion/failure/hidden/ambient/disposal/cap; do not reset the deadline for status
  or meter updates. No wake lock, global setting change or background screen wake. Existing
  socket idle limits stay unchanged; timeout during unfinished work shows a concise recoverable
  notice rather than silently looking Ready. Cover actual window flag transitions and a delayed
  stock reply longer than the15second device default; do not claim the historical issue fixed
  without receipt/physical proof.
- [x] Run focused JVM/native RED→GREEN gesture, ring/lifecycle, haptic token/outcome and screen
  lease cases plus existing carousel vertical/horizontal/aborted/refresh regressions. Run strict
  affected builds/lint/format once. Produce synthetic native visual receipts in ignored local
  artifacts for final Task7; public captures never contain owner portraits/endpoints. Self-review,
  scoped commit and full report, then independent task review. Root owns live devices/docs.

## Task 7: Whole-branch review, native visual acceptance and preserving delivery

**Files:** Tests/captures under `docs/demo/native-clients/` use synthetic original art only; original store assets under `docs/store-assets/voice-q/`; update `docs/PRD.md`, `docs/architecture.md`, `docs/profile-setup.md`, `docs/release.md`, `docs/progress.md`, `SESSION_STATUS.md`. Private device backups/owner art/receipts stay `.local/native-design/`.

**Interfaces:** All prior task interfaces are implemented and reviewed. Public docs describe actual behavior, not prototype-only states.

- [x] Run `./gradlew ktlintFormat check :mobile:assembleDebug :wear:assembleDebug :mobile:assembleDebugAndroidTest :wear:assembleDebugAndroidTest --warning-mode=fail --max-workers=4` with the established JDK/Python environments. Inspect all test reports for failures/errors/skips; native tests run on owned emulators with isolated session flags.
- [x] Capture/inspect all three themes, phone directory/Talk/Chat/relay/settings/setup/errors, narrow/large-font/keyboard layouts, watch portrait/picker/reply/ambient and both tile layouts. Verify actual semantics, clipping and contrast beyond the prototype's nine pairs. Fix visual defects before delivery, with covering tests for behavior regressions.
- [x] Dispatch one broad whole-branch review on the most capable configured model with full spec/ledger/diff evidence; resolve findings in one fix wave and scoped re-review. Per-task review does not replace this gate.
- [x] Compare existing device certificates, back up APK/data and store protected-file hashes; install with preserving updates only. Verify APK hash and unchanged existing identities/portraits/preferences except the explicitly changed appearance/pins and normal new phone identities. Never bypass a signature mismatch.
- [x] Import the maintainer’s supplied eighteen bounded watch-256 GIFs through normal native picture selection; verify independent descriptors/caches on phone/watch and static ambient/tile/reduced-motion fallbacks. Personal assets and receipts remain ignored; originals are unchanged and no app cache is seeded.
- [ ] Through normal UI, choose the maintainer's preferred Moonlight appearance and configure Focus/four pins when devices are available. Verify physical tile addition and each portrait launch on the watch; request owner speech/visual observations. Restore temporary timeout settings after testing. Keep owner GIF/office and quantitative battery/audio gates distinct.
  Both devices now use Moonlight. Watch Focus is configured; physical phone snapshot readback
  matches revision 1 and all 19 keys. Four favorites, phone edit acknowledgement, system tile
  addition/launches and owner observations remain open. Original device timeouts are retained.
- [x] Record verified checks/demo notes and review results, update draft PR, run public secret/private-value scans, commit/push only authorized public files. Do not merge or publish a release. If hardware is unavailable, continue software work and leave only its exact hardware gates open.

## Pre-flight coverage

Tasks 1–3 cover brand/themes and every phone destination. Task 4 covers the watch navigation
and layout while preserving its interaction. Task 5 covers tile storage/static resources/launch;
Task 6 covers complete phone pin configuration and revision synchronization. Task 8 covers
the owner-requested portrait recording/rings/light haptics and bounded reply visibility.
Task 7 verifies
native/physical delivery and updates the current-state documentation. Prototype artifact work
is complete and is not repeated as native validation.
