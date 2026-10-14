# Phone messaging, picture controls and identity refinement

Status: approved by the maintainer, 2026-10-14, including **A: Courier**. The maintainer selected **hold to
record, release to send, slide left to cancel**, and requested a waveform while recording.
Those choices, the layout and the replacement icon direction are approved.
This document and its mockup describe a proposal, not an installed native update.

## Problem and scope

The current phone composer stacks a text field, a separate Send row and a large voice
button. Its downward cancellation conflicts with the familiar voice-message interaction
the maintainer wants. The app name is subordinate to oversized section headings. Picture
dialogs put multiple buttons inside the dismiss slot, which can clip Cancel. The existing
Voice Q launcher icon is too generic for the maintainer.

Refine these four areas within the approved Portrait Studio direction and existing Light,
Dark and Electronic Moonlight palettes. Keep the profile directory, custom ordering,
widgets, watch controls, transport, identity isolation, GIF limits and server voices intact.
No new endpoint, enrollment or server behavior is required.

The source inspection and existing synthetic native captures support these layout findings.
No new capture or UI takeover of the maintainer's personal phone was performed.

## Approaches considered

| Approach | Benefit | Tradeoff |
|---|---|---|
| Inline voice-message composer, recommended | Text and voice share one baseline; the mic stays within thumb reach; more room for the conversation | Requires careful gesture, keyboard and accessibility state handling |
| Separate full-width hold surface | Large voice target and simple explanation | Retains the stacked layout and occupies more reading space |
| Separate voice mode | Gives recording a dedicated surface | Adds a mode switch and extra navigation for a short message |

Use the inline composer with the maintainer's selected hold/release/left gesture.
The interactive [mockup](2026-10-14-phone-messaging-mockup.html) shows an idle composer,
recording state, corrected title, picture sheet and two original icon options. All profiles
and turns in it are synthetic. Its waveform is an illustrative animation; it does not access
the microphone or send messages.

## Composer and gesture

Place a flexible rounded text field and a 52-dp mic button on the same bottom baseline,
with an 8-dp gap. The field grows to four lines. When a typed draft is nonblank, the same
primary button becomes Send. Remove the separate Send row and standalone Hold to talk bar.
Keep the composer above the keyboard and navigation/system insets. Do not hide the mic
merely because the keyboard is visible. Preserve per-profile drafts and the normal IME.

Pressing the mic starts the existing recording flow immediately. While recording, replace
the text field with a timer, a bounded waveform and a clearly labeled Cancel control; keep
the held mic in the same position. The hint reads **Slide left to cancel · release to send**.
Do not add a mic overlay to the profile portrait.

Releasing sends the voice turn through the existing finish path. Sliding at least 64 dp
physically left, with horizontal travel greater than vertical travel, cancels immediately.
Cancellation is final for that gesture: returning right or releasing cannot send or restart
it. The visible Cancel target is on the physical left, matching the requested gesture even
in RTL. A release before 250 ms discards the short press with a brief hold-to-record hint;
it must not submit an empty voice turn. Native capture/session validation still applies.

Pointer cancellation, lost input ownership, a consumed gesture, profile change, background,
lock or disposal cancels capture. Downward movement is no longer the cancellation gesture.
Additional pointers cannot finish or restart the active recording. Snapshot the gesture's
profile/session and keep the handler stable across level/timer recompositions. Delayed
microphone permission or startup callbacks cannot start capture after the gesture ended.
Each gesture has at most one terminal finish/cancel action.

Show Starting until actual capture is active, then Recording and elapsed time. If startup
or permissions fail, show the existing actionable error and return to idle. Disable Send
and recording when the current connection cannot accept a turn. Keep explicit Cancel turn,
Stop voice, approvals and muted-volume guidance reachable without moving the mic target.
Sending a typed message never also starts recording.

Provide semantic Start recording, Finish and send, and Cancel recording actions so a
TalkBack, switch or keyboard user can operate without holding or dragging. These invoke
the same guarded session flow. Do not require a touch gesture for accessibility operation.
Keep minimum 48-dp targets, visible pressed/disabled states and existing recording cues.

## Waveform and voice

Use the **real microphone level**, already supplied by the conversation state. Make the
recording waveform a short stream of recent amplitude values advancing across the composer,
so speech visibly differs from silence. Keep only a bounded transient history of normalized
levels, not PCM samples or a saved visualization recording. Clamp invalid values. Reset the
history on every start, stop, cancellation, profile change and lifecycle exit.

Before capture starts and during silence, show a quiet baseline. Never fabricate active
speech in the shipped app. The mockup may use fake data because it is explicitly labeled.
Respect the existing reduced-motion and power-saver policy with a static recording indicator
and elapsed timer instead of continuous animation. Do not announce every bar/timer update to
accessibility services; expose recording state and named actions instead.

Keep the existing response waveform driven by real playback level and the profile's actual
server-provided voice, including ElevenLabs. Place it with the response/Stop voice state,
separate from the recording strip. Reset on stop/end. This is a visual refinement, not a
change to audio encoding, provider selection, volume or playback ownership.

## Title and headings

Use **Quantum Relay for Hermes Agents** as the phone's full title and launcher label.
Show that exact name on one line in the main phone header, above each non-conversation
destination. Give it the full content width; remove the competing inline logo/subtitle.
Use an 18–22-sp title role and 14–16-sp destination headings so Your profiles and Settings
are visually subordinate. Retain the profile toolbar within a conversation.

Keep the full title in accessibility semantics. At narrow widths or large system fonts,
allow manual horizontal title scrolling instead of truncating, wrapping or defeating font
scaling. The title must fit without scrolling at the ordinary 360-dp/default-font size.
Phone naming does not lengthen the watch's compact title. Update phone About copy consistently.

## Shared picture sheet

Replace both Directory and Catalog picture AlertDialogs with a shared native Material 3
modal bottom sheet. Show the selected profile's current portrait and name, then full-width
Choose picture and Use box picture actions. For a manual profile, use Use default picture.
Choose picture continues to open Android's normal photo/GIF picker; do not introduce a
custom media browser. Preserve effective-picture precedence, bounded GIF validation and
the existing static fallback.

Put a full-width **Cancel** in its own footer outside the scrolling body. Respect bottom
system/IME insets and make the body scroll at small heights, large fonts and landscape.
Hide the keyboard when opening the sheet without clearing the directory search query.
Cancel, Back, outside dismissal and picker cancellation preserve the prior picture.
Disable conflicting picture actions while an operation is busy; cancellation/dismissal stays
available. Use the stable profile key, not a list index, and safely abandon an operation if
the profile disappears. Never apply another profile's picture after reorder or refresh.

## Original icon choice

Two proposed masters are in [the design asset folder](../../design/phone-messaging-2026-10-14/README.md).

- **A: Courier, recommended.** An original android courier with an indigo bob, cyan winged
  headset and amber transmitter. A recognizable character addresses the generic-icon
  complaint and relates to the portrait-led product.
- **B: Winged relay.** An original geometric messenger emblem. Simpler at tiny sizes and in
  monochrome, with less personality than the mascot.

Both use original work without an upstream logo, Nous mascot or personal profile art.
Selection approves a direction, not the current concept crop as final production assets.
For the chosen option, prepare proper adaptive foreground/background and monochrome layers,
safe-area composition and density exports. Verify round, squircle and themed launcher masks
at actual small sizes before replacing the shared icon sources. Shared branding resources
may be used by future watch builds; this delivery installs only the phone update. Preserve
the independence disclosure and the separate publication naming/rights gate in release.md.

## Implementation boundaries and verification

Keep capture/playback and TLS pairing in PhoneConversation/the existing client. A focused
composer owns gesture and presentation state. Reuse the shared picture sheet from both
entry points. Header/heading changes stay in presentation, and icon masters have a provenance
manifest. Add no Android dependency to the protocol module and change no Gadget protocol.

Acceptance must cover:

- Real native captures at 320/360-dp and landscape, default and large fonts, IME open/closed,
  multiline drafts and all three themes. Title hierarchy, composer controls and picture
  Cancel remain readable and reachable; no accidental horizontal page overflow.
- Gesture tests for hold/release, left cancellation followed by return/right/release,
  short press, vertical travel, cancelled/consumed/multiple pointers, asynchronous startup,
  recomposition, connection loss and lifecycle exit. Assert terminal action counts and no
  recording/send after cancellation. Cover accessibility Start/Finish/Cancel and typed Send.
- Bounded real-level history, silence/reset and reduced-motion/power-saver state. Keep
  playback levels separate and retain existing voice/cue tests. Emulator evidence does not
  establish physical microphone or audio comfort.
- Both picture entry points with large fonts/small heights, restored box/manual default,
  photo/GIF selection, cancellation, busy state and profile reorder/removal.
- Adaptive icon masks, 48-pixel launcher legibility and monochrome; a native screenshot
  gallery uses synthetic profiles and original assets only.
- Appropriate existing strict checks and a matching-certificate preserving debug phone
  update, with private backup/receipt and data/artwork checks. No uninstall, reset or new
  pairing. Do not launch or capture the owner's phone without authorization.

Update progress and SESSION_STATUS with proven checks and remaining owner observations.
The PR stays draft; no merge, Play upload or live server change is part of this refinement.

## Primary platform references

- [Android Compose gesture handling](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)
  supports the custom hold/drag design; add the semantics that raw pointer input does not supply.
- [Material 3 bottom sheets](https://developer.android.com/develop/ui/compose/components/bottom-sheets)
  supplies the native picture chooser surface and dismissal lifecycle.
- [Android adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)
  supplies the layered/masked launcher delivery requirements.

These references were checked on 2026-10-14. Gesture thresholds and layout dimensions above
are this app's proposed design choices, not platform mandates.
