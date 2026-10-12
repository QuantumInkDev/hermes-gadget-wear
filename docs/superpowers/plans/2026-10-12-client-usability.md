# QuantumInk Relay client usability implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Make watch controls usable and give the phone an organized, independently paired voice/text client.

**Architecture:** Preserve stock DirectConnection and the existing opaque watch relay. Share Android audio adapters between apps; keep all Android imports outside protocol. Persist stable box/profile ordering and independent phone identity records in their own encrypted vaults.

**Tech Stack:** Kotlin 2.3.10, AGP 9.2.1, Compose BOM 2026.09.00, Wear Compose 1.6.2, Android Keystore, stock Gadget v0.2.0.

## Global constraints

- Authority: [approved design](../specs/2026-10-12-client-usability-design.md).
- Watch portrait 160 dp; portrait-page top inset 28 dp; arrow targets 48 dp.
- Three catalogs; at most 32 pages; catalog credentials never become Gadget access tokens.
- Phone direct chat requires TLS; foreground only; no automatic replay or watch-key copying.
- Existing application IDs, enrollment ciphertexts and photo hashes survive preserve-data updates.
- Personal artwork, URLs, keys, captures and live receipts remain ignored under `.local/`.
- Animated-picture delivery is a separate plan against its approved bounded format contract.

## Verification commands

Use the existing JDK 17 and the `.local/venv` / `.local/extension-venv-m6` Python environments
recorded in SESSION_STATUS. `./gradlew ktlintFormat check :wear:assembleDebug :mobile:assembleDebug :wear:assembleDebugAndroidTest :mobile:assembleDebugAndroidTest --warning-mode=fail --max-workers=4` must pass.
The owned-emulator carousel runner is `tools/carousel-demo.py`; it rejects physical serials
and requires `--reset-owned-emulator`. Stop only the owned snapshot helper before a separate
instrumentation runner uses UiAutomation. This is a tooling limitation, not an app failure.

### Task 1: Watch scrolling and page controls

**Files:** `wear/.../ProfileCarousel.kt`, `WatchScreen.kt`, `WatchNavigation.kt`, `WatchCarouselTest.kt`.
**Interfaces:** Produce `WatchNavigation.scrollFor(key: String): TransformingLazyColumnState`;
pass it through the carousel conversation callback and `RoundPage(state = ...)`.

- [ ] Add `verticalTouchScrollKeepsControlsReachable`; run against existing code and observe unreachable Type control.
- [ ] Keep `val selected = index == pager.settledPage` during nested gestures. Use Wear foundation `HorizontalPager`/`rememberPagerState` so its focus hierarchy directs rotary input to the active list.
- [ ] Add cancelled-drag, return-page and metadata-refresh checks at a lower control. Save each stable page's state in the activity-owned navigation map:

```kotlin
private val scrolls = LinkedHashMap<String, TransformingLazyColumnState>()
fun scrollFor(key: String): TransformingLazyColumnState =
    scrolls.getOrPut(key) { TransformingLazyColumnState() }
fun retainScrolls(keys: Set<String>) { scrolls.keys.retainAll(keys) }
```

- [ ] Render arrows in a centered row; counter between; semantics descriptions Previous profile / Next profile. Disable at first/last page and while recording/prompt/saving. Approval lists get a separate prompt-keyed state.
- [ ] Run the touch regression plus full synthetic three-page recording/approval/rotary scenario. Expected: both `OK (1 test)`; then strict checks.
- [ ] Commit this task and record physical installation evidence if a watch is reachable.

### Task 2: Shared Android audio and recording cues

**Files:** create `client/build.gradle.kts`, `client/.../RecordingCues.kt`; move `wear/.../PcmAudio.kt` and `OpusCodec.kt` into client namespace; update imports in service/codec tests, module dependencies and root check.
**Interfaces:** shared `PcmCapture`, `PcmPlayback`, `OpusSupport`; capture `onStarted: () -> Unit`, `onStopped: (Boolean) -> Unit` callbacks. `RecordingCues.play(kind: Cue)` runs on the capture worker, with `Cue.START`, `FINISH`, `CANCEL`; sound-enabled preference is explicit.

- [ ] Add owned-device capture-boundary instrumentation: successful initialized recorder calls start once before frames; finishing/cancel stops before end cue; denied permission calls no success cue.
- [ ] Move the existing proven adapters, retaining bounded queues, drain, audio focus and optional Opus. Initial phone capability advertises PCM only.
- [ ] Play start cue after AudioRecord starts; discard queued samples through cue completion before forwarding frames. Play end/cancel after AudioRecord shutdown and before sending finish to the protocol. Use notification-volume tones, silent/DND checks and short haptics; no cue on failed start.
- [ ] Add enabled switches in both apps; synchronize only this nonsecret preference through a bounded versioned Data Layer path. Sound disabled keeps visible Listening and existing haptics.
- [ ] Run shared capture boundary checks and existing codec fixtures, then strict checks. Expected: no frame precedes readiness, stop precedes cue, no stale callback after profile switch.
- [ ] Commit adapters and cues with observed/owner-only audio gates separated.

### Task 3: Phone organization, inline portraits and brand

**Files:** create `protocol/.../CatalogOrder.kt`, `mobile/.../ProfileDirectory.kt`; modify CatalogVault/Repository, MainActivity, app strings and launcher resources.
**Interfaces:** `CatalogOrder.group(pages)` preserves first source occurrence and per-source order;
`CatalogOrder.moveProfile(pages,key,delta)` changes order only within its source;
`CatalogOrder.moveSource(pages,id,delta)` moves a whole group. Repository persists and publishes this flattened order, and exposes hash-addressed portrait file lookup.

- [ ] Write JVM ordering tests with interleaved sources and duplicate names; instrument restart/refresh/offline preservation. Expected ordering literals are independent of the implementation.
- [ ] Group pages while retaining source/profile stable keys and old within-source order. For a profile move:

```kotlin
val groups = group(pages).map { it.toMutableList() }
val source = groups.single { group -> group.any { it.key == key } }
val index = source.indexOfFirst { it.key == key }
val target = (index + delta).coerceIn(0, source.lastIndex)
source.add(target, source.removeAt(index))
return groups.flatten()
```

- [ ] Render lazy, collapsible box groups with search and explicit reorder mode. Each profile includes effective portrait, name, box/status, Talk/Chat, Picture, endpoint detail. Use the normal native photo picker and existing override APIs. Move source editor and advanced manual endpoint setup under Settings.
- [ ] Use Profiles / Watch / Settings navigation with safe insets and keyboard padding. Watch owns existing relay/association controls. Preserve FLAG_SECURE and existing service behavior.
- [ ] Use QuantumInk Relay / QuantumInk Relay Watch labels, original orbit/speech adaptive + round + monochrome icon, and About compatibility/independence/third-party attribution.
- [ ] Verify owned-phone rendering, order, normal picker and existing setup/voice flows; strict checks. Expected: effective image is inline and custom order persists after a source failure/return.
- [ ] Commit phone directory/branding.

### Task 4: Independent phone text/voice conversations

**Files:** create `mobile/.../ConversationVault.kt`, `PhoneConversation.kt`, `ConversationPage.kt`, `PhoneConversationTest.kt`; modify activity lifecycle and microphone permission.
**Interfaces:** `ConversationVault.identity(endpoint, create)` uses its own Keystore alias/directory; `PhoneConversation` is an AndroidViewModel retaining bounded per-endpoint draft/response state through rotation; `open(profile)`, `send()`, `startRecording()`, `finishRecording()`, `cancelRecording()`, `answer(id,yes)`, `hide()` and `close()` drive DirectConnection with shared audio.

- [ ] Add synthetic stock-server tests: first phone pairing, authenticated reconnect, fresh text marker, distinct second identity/response, stale observer rejection and foreground shutdown. Vault corruption must fail closed rather than rotate an identity. Existing watch records are never accessed.
- [ ] Create identity only after endpoint review and explicit Connect. Reuse manual Gadget token only for that exact canonical URL; never use source credentials. Construct `DirectConnection(endpoint, identity, observer, accessToken, availableActions = emptySet())` with actual phone PCM capabilities.
- [ ] Serialize session replacement with an epoch. Every observer/audio callback compares its captured epoch before publishing or sending. `hide()` increments epoch, stops capture/playback and closes only the phone connection. It leaves RelayService untouched.
- [ ] Render profile header, connection/pairing state, transcript/cumulative reply, approval controls, bounded composer, hold/tap recording, cancel/reset-session and playback Stop. Preserve rejected text drafts, clear only accepted sends, prevent duplicates while submission is pending, and show TLS/permission/network failures.
- [ ] Request RECORD_AUDIO only after recording action; request local-network permission only after Connect where needed. No microphone starts following a permission dialog unless the visible selected conversation still matches the request.
- [ ] Run owned-phone integration, audio boundary and existing relay/phone-storage checks, then strict checks. Expected: text/voice frames use the correct independently paired endpoint, audio is stopped on hide, and watch relay remains enabled.
- [ ] Commit and document foreground behavior, phone-specific pairing instructions and physical speech gates.

## Review focus

Inspect lifecycle races, token namespaces, consent boundaries, duplicate submissions, source reorder with manual fallbacks, captured-tone contamination, focus after swipe, cache eviction and image decoding. Synthetic audio cannot prove actual phone microphone/STT/voice quality or office radio behavior. Compare owner identity/portrait hashes around any physical update. Review optional animation separately before its final stage.
