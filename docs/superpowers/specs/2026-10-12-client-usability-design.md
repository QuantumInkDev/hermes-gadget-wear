# Phone and watch usability follow-up

Status: recommended design approved by the maintainer, 2026-10-12. The existing approved carousel
design already requires usable scrolling; investigating and repairing that regression
does not depend on the new phone-client approval. App development below is authorized.

## Delivery sequence

1. Repair watch scroll preservation, replace truncated page labels, and add recording cues.
2. Organize the phone by box, display portraits inline, and add direct phone conversations.
3. Add bounded animated watch-picture overrides with static fallbacks.

These are independently testable client changes. Existing stock Gadget v0.2.0 servers,
catalogs and watch enrollments remain compatible. No multiplexed gateway is required.
Each stage records observed evidence and remaining physical checks in progress/status.

## Approaches

- Recommended: retain the current encrypted catalog and opaque watch TLS relay, and add
  a separate phone-owned Gadget client. This meets the requested phone conversation
  experience while keeping watch keys on the watch.
- Companion-only redesign: organization and portraits improve, but phone Talk/Chat is
  absent. This is smaller but does not satisfy the full request.
- Multiplexed server gateway: consolidate conversations server-side. This changes routing
  and enrollment contracts and still requires the separate v2 decision/upstream review.

## Watch usability

Retain a vertical list state per stable source/profile page, independent of temporary
pager preview composition. Vertical gestures, cancelled horizontal drags and catalog
availability refreshes must not return a page to its top. Returning to a profile restores
its prior position. Only a deliberate new page/reset or a newly presented approval can
change the relevant scroll position. Off-screen previews own neither rotary focus nor
a conversation. Profile switching remains locked while recording or answering approvals.

Keep the approved 160 dp circular portrait and 28 dp top inset. Use a single compact row
with previous chevron, page count and next chevron. Each arrow has a 48 dp touch target,
full accessibility description and a disabled state at the boundary; there is no wrap.
Talk/Type, replies and settings remain below the portrait in the scrollable page.

Distinct short start, finish and cancel cues confirm actual microphone activity. Start
cue completes before samples are captured; finish/cancel cue follows capture shutdown.
A failed/denied recording produces no success cue. Sound respects device silent/DND
and notification volume; haptics and visible Listening remain available when muted.
Expose a Sound cues preference on the watch and in the phone settings, default enabled.
Do not mix these tones into microphone frames or interrupt the profile's spoken answer.

## Phone navigation and organization

Use a shared application header and three main destinations: Profiles, Watch and Settings.
Profiles is the opening screen after setup. Watch contains relay/association controls and
explicit connection status; Settings contains server setup, speech configuration and About.
Use consistent Material surfaces with readable text, system insets and keyboard support.

Profiles are grouped in collapsible sections by configured box. Manual endpoints occupy
a separate section. A profile row shows its effective watch portrait, name, box, catalog
availability and picture source. Catalog availability is not labeled as Gadget Ready.
Talk/Chat, Picture and endpoint details are attached to that row. Picture opens the existing
normal photo picker or restores the imported box picture; it never edits the box's artwork.

Search filters names and boxes without changing persisted order. Reorder mode offers box
up/down controls and profile up/down controls within its box, usable by touch and accessibility.
Persist both orders in the encrypted catalog vault. Flatten that same order for the watch.
Refresh, offline recovery and duplicate names preserve stable keys and owner order. New
profiles append within their source; withdrawn pages remain until explicitly forgotten.
Preserve existing custom order as the initial within-box order; initial box order follows
the first occurrence in the current roster. Selection remains by stable key, not array index.

## Phone conversation client

Opening Talk/Chat reviews that profile's canonical endpoint before connecting. The phone
uses its own per-endpoint Android Keystore device identity and normal stock approval flow.
It never reads, copies or requests watch identity keys. Catalog-only credentials remain
separate from Gadget access tokens. Existing watch enrollment remains usable unchanged.

The screen has profile portrait/name/box, status, pairing instructions when necessary,
transcript and cumulative response, bounded per-endpoint drafts, a text composer and
Hold to talk. Release finishes and sends; downward drag cancels. An accessible tap action
starts/finishes recording. Transcript and reply belong to the selected endpoint only.
Default audio is server TTS with the profile's configured voice, matching the watch.
Show playback stop, cancel/reset-session and explicit server approval controls. No action
approval is inferred from a chat message. Do not replay a message on reconnect.

Reuse the pure JVM DirectConnection reducer/stock framing and shared Android PCM adapters.
Advertise only phone capabilities actually implemented. Protocol remains Android-free.
Phone conversation audio is independent of RelayService and watch BYOK channels. Initial
phone client uses server speech; watch BYOK settings continue to operate as before.

Foreground-only phone conversations are the first release of this feature: leaving the
conversation, locking the phone or switching profile stops capture/playback and closes
that phone socket without cancelling the watch relay. Preserve drafts and displayed reply
through rotation. Show this behavior before initial pairing. Background conversation
playback requires a later explicitly designed media service; do not claim support now.

TLS is required for this new phone conversation client, matching the phone's packaged
cleartext-denied network policy. Existing manually reviewed private ws watch endpoints
retain the owner's explicit LAN/tailnet warning; never weaken TLS after certificate/auth failures.
Permissions are requested only after the relevant user action. Recording is bounded by
the existing protocol turn limits. Network/auth/permission failures are visible and cannot
create duplicate submissions, cross-profile responses or hidden capture.

## Branding for review

Maintainer-selected family title: **Quantum Relay for Hermes**, for both phone and watch.
This supersedes the earlier QuantumInk Relay proposal.
The repository/package IDs and matching phone/watch signing relationship do not change.
Use an original orbit/speech icon, readable in adaptive, round and monochrome launcher
masks. The concept combines a communication bubble with three connected points; it does
not trace the Nous mascot or use the official wordmark. App About describes compatibility
with Hermes Agent and the independent community relationship.

The current SDK [affiliation and trademarks section](https://github.com/Adolanium/hermes-gadget-sdk#affiliation-and-trademarks)
says its MIT license grants no rights to Nous names or marks. Its
[mascot README](https://github.com/Adolanium/hermes-gadget-sdk/blob/main/assets/mascot/README.md)
advises checking with Nous before broader use. These are SDK-maintainer notices, not a
Nous-issued blanket add-on license. No official blanket permission was found in this
bounded review. Permission to use the Nous character as a published launcher/store asset
is therefore not assumed. A name proposal/search is not trademark clearance; store-name
and rights review remain a publication gate. Personal profile artwork stays ignored.

## Animated pictures

Current phone normalization rejects animated files and watch cache accepts PNG only.
GIF support is a separate final stage with the contract in
[animated-picture design](2026-10-12-animated-picture-design.md). All existing round PNGs
remain valid; choosing animation is optional. Existing sprite-based pet animations remain
separate and retain their optional live server-extension gate.

## Acceptance

- Reproduce scroll failure before fixing it; verify touch, rotary, cancelled pager drags,
  page return and metadata refresh with the production composables on an owned emulator.
- Verify arrow readability, accessible recording actions, muted cues and capture-boundary
  ordering. Physical timing/audibility is an owner observation gate.
- Verify persisted box/profile order, source outage recovery and inline effective pictures.
- Synthetic independent stock servers exercise phone pairing, text, microphone framing,
  server audio, approvals, reconnect, profile isolation and foreground shutdown. Never
  run destructive instrumented fixtures against the owner's existing phone vault.
- Strict Gradle/lint/JVM checks and APK signature checks precede preserve-data installation.
  Compare owner watch identity/picture hashes before and after any device installation.
- Real phone/watch speech, radio behavior, battery and publication checks remain separate
  from emulator or typed-message results. Do not silently enroll all live phone profiles.

## Owner UI refinement after installation — 2026-10-12

The maintainer confirms the three box groups, portraits and Talk/Chat are visible, then requests
clearer grouping/collapse, readable top text and the title Quantum Relay for Hermes. Box headers
are full-width tappable panels with a machine icon, name, profile count, catalog status and an
animated chevron. Expanded profile cards share a contiguous box background. Search and Reorder
force relevant sections open and leave collapse disabled, so a tap cannot appear to do nothing.
Sorting arrows remain confined to Reorder. Existing stable keys, owner order and collapse state
are retained. The phone shell explicitly supplies onSurface foreground for its dark background.
These are authorized refinements of the approved design, not a new pairing/deployment decision.
