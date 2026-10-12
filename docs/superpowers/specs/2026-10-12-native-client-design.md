# Quantum Relay for Hermes: native client design pass

Status: approved by the maintainer on 2026-10-12, including Portrait studio and Voice Q.
This specifies the next design and tile implementation;
it does not claim those changes are installed. Existing pairing, speech and relay software stays
the basis of the work. The maintainer reports that current watch scrolling/controls and recording
cues work. Owner GIF artwork and the next office trial remain separate observations.

## Intent and direction

Make the phone feel like a deliberate native Android product, and give the round watch a focused
portrait and voice experience. Retain the requested name **Quantum Relay for Hermes** and use
original geometric brand artwork. Keep the working horizontal watch swipe interaction.

Three directions are explored in the interactive preview:

| Direction | Strength | Tradeoff |
|---|---|---|
| Portrait studio, recommended | Portraits, restrained color and distinct voice controls give the app identity; compact native rows keep the fleet scannable | Requires coherent typography, spacing and state treatments across every destination |
| Signal deck | A larger active-agent/voice surface emphasizes talking | Leaves less room for browsing many profiles |
| Calm directory | Very compact, conventional grouping | Less distinctive; closest to the current utilitarian presentation |

The recommended build is Portrait studio. Expression comes from portraits, the original mark,
type hierarchy, intentional tonal surfaces and short state transitions. Reading, navigation and
voice control take priority over decoration.

## Brand and icon

Use a geometric Q-shaped dialogue/orbit mark with a small voice signal inside it. Explore the
mark alongside a paired-arc relay and signal crescent before finalizing its exact geometry.
The icon must survive monochrome, a 24-dp app bar, a circular watch launcher and Android masks.
Deliver original SVG masters and Android vector foreground/background/monochrome layers, plus
the required launcher densities. Keep the essential silhouette within the adaptive safe area.
Avoid text inside the icon and continuous glow/animation. App bars can display Quantum Relay
with a smaller for Hermes line; launcher labels retain the full approved name.

The app remains an independent compatible client. No Nous mascot, upstream logo, endorsement
claim or personal profile artwork is included in public brand assets. This design does not
settle the separate publication naming/rights gate in docs/release.md.

## Three appearance choices

Provide Light, Dark and Electronic Moonlight in both clients. Persist the choice independently
on each device; switching appearance never changes a selected profile, connection, recording,
draft, picture or identity. Default existing installations to their current dark appearance.
The maintainer can choose Moonlight through the normal appearance setting. Wallpaper-derived
colors must not silently override an explicit theme.

Electronic Moonlight colors were verified from the maintainer's installed Cursor theme;
the original and border variant share these relevant values. These are semantic palette inputs,
not a copy of the editor theme or its UI. The exact app mappings below are deliberate native
adaptations. Any packaged typefaces require their own redistribution license.

| Phone role | Light | Dark | Electronic Moonlight |
|---|---|---|---|
| Background | #F7F8FC | #0B0D12 | #1D1F31 |
| Surface | #FFFFFF | #171B23 | #26293E |
| Main text | #1D263D | #E7EBF4 | #C8D3F5 |
| Secondary text | #535E76 | #AAB4C7 | #A9B6DD |
| Primary accent | #345FC8 | #AAC6FF | #82AAFF |
| On accent | #FFFFFF | #101B31 | #1D1F31 |
| Supporting accent | #086C88 | #80CDE5 | #86E1FC |

Watch tokens are tailored separately. Dark and Moonlight use a black canvas with tonal
containers/accent roles; Moonlight keeps the verified blue/cyan/pale-text family. Light is an
explicit pale interactive canvas option, as requested. Ambient always uses the existing black,
static, shifted representation regardless of theme. Measure actual Light/GIF battery cost rather
than assigning an unverified power claim. Normal text targets 4.5:1 contrast; large text and
essential boundaries target 3:1. Contrast is verified for each foreground/container relationship,
including dialogs, disabled controls, fields, selection, warnings and keyboard insets.

## Phone: structure and native behavior

Keep three native destinations: Profiles, Watch and Settings. Use consistent top app bars,
icon vocabulary, predictive/system Back, edge-to-edge insets, native fields and sheets/dialogs.
The library versions already include Material 3; this pass does not require a framework rewrite.
Use Android's native font metrics for readable body text and a restrained expressive headline
scale. Do not replace scalable text with artwork. No external font/image fetch is required.

Profiles becomes a compact portrait directory. A search affordance and overflow replace the
permanent cluster of Refresh/Reorder/Manage/Manual buttons. Preserve all those actions in clear
menus and setup entry points. Keep grouped boxes with independent expansion and saved custom
ordering. Group labels show profile count and actual catalog state; catalog availability must
never be presented as proof that each Gadget endpoint is online.

Each profile row has its effective watch portrait, name, concise state and one obvious
conversation action. Details, endpoint review, picture override/reset, retirement and pinning
are in a profile sheet/overflow. Use simple list divisions/tonal group boundaries instead of a
separate generic card for every control. Offline boxes retain profiles, their order and art.
Empty/search/error states have concise copy and a relevant recovery action. Long labels and
larger fonts wrap correctly; reorganizing never silently changes the watch selection.

The conversation screen has a profile toolbar and portrait, visible text conversation and a
comfortable bottom composer. Talk/Chat is foreground only, with independent phone TLS pairing.
Show a prominent hold-to-talk control, clear listening/thinking/speaking states and explicit
Cancel/Stop voice. Typed drafts, reply text and approval prompts keep their current isolation
and guards. Endpoint review and pairing instructions remain reachable, but connection setup
does not occupy the normal conversation's reading area. The keyboard, system Back, background
and lock lifecycle still terminate phone audio/socket work according to the approved client.

Watch is a concise relay dashboard with actual readiness, connection controls, association,
profile tile previews/editing and the existing disclosure/settings entry points. Keep details
available through progressive disclosure. Settings gains an Appearance page with three real
previews and consistent native sections for recording cues, speech, connections and About.

## Watch: portrait, swipes and deliberate controls

Remove the visual Previous/Next buttons from the portrait pages. Keep the existing tested pager,
stable page keys, per-page vertical position/drafts, locks and rotary focus behavior. The earlier
pager-wrapper gesture regression is evidence against replacing that interaction without a
specific benefit and new regression checks.

Use a bounded, quiet horizontal page indicator at the lower safe edge, with at most six marks
for a large roster and an accessible position/count label. A separate 48-dp profile-name/picker
target opens a round native profile list. That list provides a direct way to jump to a profile
and pin it; it is not necessary to swipe through eighteen pages to reach a distant profile.
Expose previous/next selection as named accessibility actions where appropriate, with the same
busy/approval/recording guards. Keep system swipe-to-dismiss and picker Back behavior distinct
from horizontal profile swipes.

Preserve the centered large portrait and top clock relationship. Hold the portrait/explicit
talk surface to record; release sends and downward cancellation retains its established behavior.
A restrained outline/level treatment appears only while listening. Thinking and speaking states
have clear text and short motion, with reduced-motion fallback. Replies and extra controls are
available by vertical scrolling; moving state never remounts the scrollable conversation.
State transitions do not consume a tap, start the microphone or clear a draft accidentally.

## Profile tiles: one and four

Provide two selectable tile entries: Focus (one pinned profile) and Pinned profiles (up to four,
in a two-by-two portrait layout). Keep the existing GadgetTileService component for the Focus
entry so an installed tile survives the update. Add a separate four-profile provider. This is
a cross-version baseline; do not promise arbitrary duplicate instances or automatic placement
without device/launcher evidence. The user adds/reorders either entry through the system tile
carousel reached by swiping left from the watch face.

Configure pins from the watch profile picker and the phone Watch destination. Focus holds one
stable page key; the grid holds four ordered, deduplicated stable keys. Empty slots show a clear
setup affordance. Offline profiles retain their pin/art. Removed profiles stay visibly unavailable
until replaced; they must not launch a different profile that happens to occupy the same index.

Tiles read local snapshots and normalized static portrait fallbacks only. Never decode/run GIFs,
open a socket, start microphone capture, execute an agent action or fetch a remote portrait while
rendering. Portrait/name touch targets are at least 48 dp and non-overlapping; labels remain
readable in the circular safe area. Missing art uses an original initials/brand fallback.
Resource versions change when the selected portrait/configuration changes, and background
updates remain bounded.

A portrait tap launches the watch app with the stable page key. Handle cold onCreate and warm
onNewIntent after service binding; validate the key against the current local roster. No URL,
credential, pairing key or unreviewed connection comes from the launch payload. Select the
requested profile and show its existing conversation/review state. No microphone starts until
an explicit foreground gesture. If a turn/approval/recording prevents selection, keep that state
and show the pending target; never switch endpoints behind an active interaction.

## Implementation boundaries

Create focused shared appearance-token/preference types in the Android client layer, with
phone/Wear Material adapters. Keep protocol Android-free and Gadget SDK v0.2.0 wire behavior
unchanged. Extract visual directory/conversation/relay/settings surfaces without rewriting
the tested repositories, recorder, playback or connection reducers.

Add a bounded versioned local tile preference store and tile resource/layout builders. Phone
pin changes use a separate versioned Data Layer configuration item for the associated watch,
containing stable keys and revision only. The watch validates membership and ordered capacity
before accepting it; offline phone changes show pending delivery. No phone/watch credentials
are copied or shared. Watch edits remain usable without the phone. The watch owns the monotonic configuration revision. A phone edit carries the last
acknowledged base revision and a request ID. The watch applies it only when that base matches
the current revision, increments its revision, and acknowledges the resulting ordered pins.
Duplicate request IDs are idempotent. A conflicting older phone edit is rejected with the
current watch configuration and shown for review on the phone; it cannot undo a newer local
watch edit. Local watch edits increment the same durable revision. No wall-clock ordering is
used. Validate peer/signature association through the existing Data Layer boundary.

Personal art, endpoints and physical receipts stay ignored under .local/. The public preview
uses original synthetic avatars; the owner's private preview may substitute supplied portraits.
The design approval covers app/local configuration development and preserving debug updates.
It does not authorize server extensions, account messages, merge, release signing or Play upload.

## Acceptance and delivery

1. Render all three themes across directory, conversation, setup, relay, settings, dialogs and
   errors. Verify contrast, large fonts, round safe areas, smaller screens and keyboard layouts.
2. Re-run meaningful directory order/collapse/search/picture and conversation lifecycle/voice
   regressions after changing their presentation. Preserve vaults, drafts and cached artwork.
3. Verify watch touch/rotary scrolling, cancelled swipes, selection locks, page return and
   metadata refresh without resets. Check the picker and accessible selection actions.
4. Test Focus/grid layouts, static art/resource invalidation, empty/offline/removed keys and
   durable ordered pins. Exercise cold/warm tile launches, unknown keys and deferred selection
   during a recording/approval. Rendering must have no connection/microphone side effects.
5. Exercise phone/watch pin synchronization, out-of-order updates, offline recovery and local
   watch edits. Keep stale revisions from replacing newer local choices.
6. Run strict checks/builds and zero-warning gates. Review the actual native renderings, then
   install matching-signature debug updates with backups and unchanged existing identity and
   portrait hashes. Use normal UI pairing for any new independent phone identity.
7. Record maintainer physical visual/voice/tile observations separately from synthetic tests.
   GIF art/power and the office trial remain their own gates. PR stays draft until those and
   the remaining release prerequisites have been assessed.

## Research used

- [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3): native theme roles, type and component structure.
- [Wear Material 3](https://developer.android.com/jetpack/androidx/releases/wear-compose-m3): native round scaffolds, expressive components and focus/pager primitives already available to this project.
- [Paging on Wear](https://developer.android.com/training/wearables/compose/pagination): page indicators and coordinated clock/page presentation. Preserve this app's proven gesture mounting while applying the visual guidance.
- [Wear color](https://developer.android.com/design/ui/wear/guides/styles/color): black watch foundations and distinct watch tokens; the requested Light mode is an explicit interactive alternative, never an ambient mode.
- [Tile principles](https://developer.android.com/design/ui/wear/guides/surfaces/tiles): concise, predictable shortcuts.
- [Tile interactions](https://developer.android.com/training/wearables/tiles/interactions): activity launch extras, exported target requirements and spaced 48-dp touch targets.
- [TileService](https://developer.android.com/reference/androidx/wear/tiles/TileService): providers, updates and active tile lifecycle. Multiple instance behavior remains a device verification boundary.
- [Adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive): separate vector layers, adaptive safe area and monochrome support.

Spec self-review: no placeholders; all three requested themes and both requested tile layouts
are defined. The distinction between catalog reachability, endpoint authorization and active
connection is retained. Design/preview evidence is separated from installed/native acceptance.
