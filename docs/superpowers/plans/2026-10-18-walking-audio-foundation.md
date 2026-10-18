# Walking audio interruption foundation implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> to implement this plan task-by-task with fresh implementation and review agents.

**Goal:** Observe the output actually used by response playback and stop interrupted
speech without letting a stale playback callback affect its replacement.

**Architecture:** Extend the existing shared `PcmPlayback` AudioTrack owner. Track route
changes only for its current playback, listen for Android's becoming-noisy signal while
that playback exists, and clean up routing/focus/receiver resources through the same owner.
The phone and watch keep their existing scoped speech controllers and media attributes.

**Tech Stack:** Kotlin, Android AudioTrack/AudioRouting/AudioManager, existing Gradle/JUnit
and Android instrumentation; no new dependency.

## Global Constraints

- The approved expansion design is the authority. Walking mode is opt-in, uses the same
  deliberate recording gesture and haptic cues, and retains the complete textual answer.
- No background microphone or wake-word listener. No new foreground service or permission.
- Keep the phone API29 floor, Wear API33 floor, API37 target and Light/Dark/Moonlight.
- Preserve separate phone/watch identities, current artwork/GIFs and independent endpoints.
- Old recovered events never automatically speak or answer approval prompts.
- No source/profile names, device addresses/IDs, endpoints, PCM or raw platform exceptions
  enter the new public output callback. It exposes only a fixed enum.
- No installed server/provider/physical-device operation by the worker. Never reset,
  uninstall or clear an emulator; preserving updates use exact-signer `adb install -r`
  with recoverable data/APK/settings snapshots and post-update checks.

## Boundary and verified source findings

This is an independently testable part of N5, **not completion of walking mode**. It
changes the existing playback owner used by both clients. Larger walking controls,
explicit output selection, concise server/client speech, Replay/Stop/More and physical
earbud acceptance remain required by the active expansion goal and a subsequent plan.

`client/src/main/kotlin/dev/quantumink/hermesgadget/client/PcmAudio.kt` owns AudioTrack,
focus requests, bounded queued PCM/Opus, real drain and completion callbacks. It currently
handles focus loss but has no output-routing callback or becoming-noisy receiver.
`mobile/.../PhoneConversation.kt` scopes callbacks to session/playback versions;
`wear/.../GadgetService.kt` has its own endpoint epoch. Preserve these callers.
`wear/.../PetOverlayPlaybackTest.kt` provides an existing real AudioTrack/drain fixture.

Android documents that `getRoutedDevice()` is meaningful while playback is active;
`setPreferredDevice()` does not establish the actual output. Observe the former; do not
claim an earbud route from a preference or enumerated device alone.
[AudioRouting reference](https://developer.android.com/reference/android/media/AudioRouting).
Android can reroute removed headphones/Bluetooth to speakers; apps can stop on the
becoming-noisy signal. This is a reaction to an observed platform event, not a promise
of zero leaked samples. [Output changes](https://developer.android.com/media/platform/output).
Maintain `USAGE_MEDIA`/`CONTENT_TYPE_SPEECH` and the current focus/lifecycle eligibility;
API35+ foreground rules are not bypassed with a new service.
[Audio focus](https://developer.android.com/media/optimize/audio-focus).

## File map

| Path | Responsibility |
| --- | --- |
| `client/src/main/kotlin/dev/quantumink/hermesgadget/client/PlaybackOutput.kt` | Fixed output enum and, only if needed, a small pure current-owner interruption guard |
| `client/src/main/kotlin/dev/quantumink/hermesgadget/client/PcmAudio.kt` | Actual AudioTrack routing callback, scoped becoming-noisy receiver, cleanup/interruption fencing |
| `client/src/test/kotlin/dev/quantumink/hermesgadget/client/PlaybackOutputTest.kt` | Behavioral current-owner/race policy tests if that policy is extracted |
| `mobile/src/androidTest/kotlin/dev/quantumink/hermesgadget/mobile/PlaybackOutputTest.kt` | Real native playback, routed-device observation, noisy/focus interruption and replacement |
| `wear/src/androidTest/kotlin/dev/quantumink/hermesgadget/wear/PlaybackOutputTest.kt` | Real Wear playback/drain and interrupted mouth/level reset |

No constructor factory/caller change is needed: new trailing callback has a default.
No UI, protocol, server, preference persistence, Bluetooth connection management, media
session or audio cache belongs to this task.

### Task 1: Actual output and interruption belong to the current playback

**Consumes:** Existing `PcmPlayback.start(rate, opusHeader)`, `offer`, `finish`, `stop`,
`onInterrupted`, `onLevel` and `onFinished` semantics, plus the current playback object.
**Produces:** A new typed callback on that same owner, with the following public shape:

```kotlin
enum class PlaybackOutput { UNKNOWN, HEADPHONES, SPEAKER, OTHER }
```

Add a trailing constructor argument:

```kotlin
private val onOutputChanged: (PlaybackOutput) -> Unit = {}
```

HEADPHONES covers routed wired headphones/headset, A2DP, supported BLE headset and hearing
aid/USB headset. Guard newer constants/APIs at the compatibility floor. Built-in speaker
maps SPEAKER; a null/unobserved route maps UNKNOWN; unclassified devices map OTHER. Do
not change the AudioManager mode, call SCO APIs, enumerate Bluetooth addresses or request
BLUETOOTH_CONNECT. Route uncertainty must not throw or falsely report headphones.

- [ ] Write native RED cases using real foreground fixture activity and production
  `PcmPlayback`. Assert route comes from that AudioTrack after `play`, real silence PCM
  drains normally, and a bounded becoming-noisy event stops the current stream and
  produces one interruption, final level zero and completion. Normal drain must not
  produce interruption. Do not synthesize a claimed physical Bluetooth connection.
- [ ] Run only those cases on an owned, freshly inventoried/opened emulator. Retain the
  failing assertion and APK/source binding; a setup/import failure is not behavioral RED.
- [ ] Implement actual route observation with `AudioRouting.OnRoutingChangedListener` and
  `getRoutedDevice()` after `play`. Register the listener before starting; remove it before
  releasing that track. Deduplicate the fixed enum callback. UNKNOWN on finish/stop is
  scoped to the same owner and cannot reset a newer playback's state.
- [ ] Register a receiver for `AudioManager.ACTION_AUDIO_BECOMING_NOISY` only for the
  current playback; use Android33+ receiver flags compatibly. A noisy signal, focus loss,
  decoder/queue failure and deliberate Stop must converge on idempotent teardown.
  Noisy/focus/error emits interruption once; user Stop and normal drain do not. Do not
  restart or automatically replay. Keep existing PCM/Opus queue and drain bounds.
- [ ] Fence every focus/routing/receiver callback to its captured playback object and
  current-owner status. A late event from the previous track must not stop/reset/report
  a replacement. Ensure cleanup still occurs if creation/play fails and that stop during
  initialization cannot register a leaked receiver/listener afterward. Do not hold an
  owner lock while blocking native release/join or calling arbitrary UI callbacks.
- [ ] Exercise two consecutive starts, concurrent stop/noisy/focus, route callback after
  replacement, null route, constructor/play failure and repeated finish/stop. Use focused
  pure tests for an extracted guard only if production uses it; avoid tests mirroring a
  fake second playback implementation. Native tests must exercise production teardown.
- [ ] Run real foreground PCM and available platform Opus playback, normal drain and
  interruption on API29 phone and API33 Wear, plus API37 phone when an owned slot is
  available. If a device is owned by another task, request controller allocation rather
  than take it. No physical earbud route is inferred from synthetic speaker playback.
- [ ] Verify original APK/data/settings restoration. Runtime install marker differences
  are disclosed, never repainted. Capture typed callback/AudioTrack route evidence,
  source/APK hashes and exact interpreter/SDK/runtime/commands/counts/skips in the report.
- [ ] Run affected checks with JDK17, `--warning-mode=fail`, `:client:testDebugUnitTest`,
  `:client:lintDebug`, both app/test APK compilations, affected native lint and `ktlintCheck`.
  Repeat broader checks only for a new failure or code change. No dependency/tool upgrade.
- [ ] Commit only focused files and append the report to this plan's SDD workspace.
  Controller performs fresh combined review, integration and authorized device delivery.

## Native recipe and evidence semantics

Build the native test APKs using the unchanged project wrapper. Use the exact owned
serial and the existing runner; for example, after safe preserving installation:

```sh
adb -s OWNED_SERIAL shell am instrument -w -r -e class \
  dev.quantumink.hermesgadget.mobile.PlaybackOutputTest \
  dev.quantumink.hermesgadget.test/androidx.test.runner.AndroidJUnitRunner
```

The worker records the actual resolved serial, APK/source hashes and literal command
privately. No permission, pairing, real server, synthesis key or user speech is required.
Real physical earbud connection/disconnection, phone/watch call interruption and route
choice remain later acceptance gates; an injected broadcast proves only the implemented
reaction and owner fence.

## Preflight and self-review

| Pair/task | Shared boundary | Ruling |
| --- | --- | --- |
| Task1 internal | Focus, route listener, receiver and output queue | One captured Playback owns all callbacks; teardown is idempotent, late events cannot affect replacement |
| Task1 / existing clients | Constructor and callbacks | Trailing default callback preserves callers; old session/epoch fences remain unchanged |
| Task1 / N8 | Native playback versus received protocol audio | New actual-output enum stays local; do not silently add it to the canonical diagnostics report |
| Task1 / future walking controls | Actual output callback | Later UI may display this enum; it must not equate connected/preferred with routed |
| Task1 / physical acceptance | Synthetic event and real AudioTrack | Record which is observed; no claimed real Bluetooth/headset pass |

Scope check: this plan covers the playback interruption foundation only. N5 concise
speech, user-facing walking controls/output selection and Replay/More remain in the goal.
No placeholder API is exported for those later features, and no capability is activated.
