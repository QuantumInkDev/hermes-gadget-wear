# Quantum Relay expansion: fifteen-feature program

Status: approved by the owner on 2026-10-18, with the explicit Akuma-only artwork
proof-of-concept restriction below. The full program is an active goal; implementation
and physical acceptance are not claimed by approval. The owner answered the complete
initial decision batch. Routine implementation choices can now be made within it.

## Recorded owner decisions

1. Implement all fifteen features, including reviewed/backed-up staged live rollout on
   the three existing boxes, scoped listener restarts, preserving device updates and
   parallel implementation/review. No multiplexed Gadget gateway or public release.
2. Relay conversations/tasks are the default scope. Existing Hermes history/tasks require
   an explicit opt-in per profile.
3. Configurable defaults: ninety-day encrypted phone text cache, seven-day temporary
   attachment copies, twenty-four-hour bounded server outbox. Canonical Hermes history
   retention is unchanged. Include deletion and optional encrypted history export.
4. Per-profile Portrait/Pet selection initially preserves existing pictures/GIFs.
5. **Generate/build Akuma only as the pet proof of concept.** Build a reusable pipeline
   for later custom pets. Do not generate or modify the other profiles' personal pets;
   future custom pets can be created from scratch. Preserve the original Akuma base art.
6. Existing private ElevenLabs credentials may be used for at most ten short synthesis
   tests totaling at most 2,500 input characters. No new subscription/purchase. Server
   voices remain the default, with phone BYOK opt-in per profile.
7. Private notification previews and no automatic spoken playback.
8. Walking mode prefers connected earbuds and otherwise uses the watch speaker. Keep
   short spoken answers and full text plus Replay, Stop and Tell me more.
9. Use M5 Max as the authenticated home wake sender while awake/reachable. Do not install
   a keep-awake helper or change Mac power settings.
10. After wireless connectivity confirmation, preserving installs, app UI inspection,
    harmless typed probes and short response playback are authorized. Owner-observed
    microphone/earbud checks may wait until their return.
11. Seed useful editable action templates. Each requires an explicitly selected profile;
    show the expanded prompt before sending.

## Scope and existing evidence

The request includes original backlog items O1–O5 and new ideas N1–N10 below. The
one-port/multiplexed gateway proposal is excluded. Existing individual profile URLs,
phone/watch enrollments, watch-owned TLS relay, custom artwork and GIF assignments,
native branding and Light/Dark/Electronic Moonlight appearances remain the foundation.

Current checkout: feature/multi-box-profile-carousel, clean at discovery. The last
recorded phone delivery is the image-only Focus widget refinement; the last recorded
watch delivery is the clear-portrait/centered-Jump build. The owner reports several
days of successful use on 2026-10-18. This is an overall owner observation, not a
substitute for individual codec, battery, tile, provider or new-feature checks.

Existing SDK patches implement optional Opus, pet delivery and client speech against
stock v0.2.0, but are isolated from live gateways. Native clients already have pet
rendering/cache/fallbacks and phone BYOK storage. General offline outbox, durable
conversation history and the new task/sharing/continuity features need new integration.
Current local Hermes source differs from the originally inspected pet snapshot; its
session/task/media interfaces must be audited before designing concrete adapters.

## Approach

Recommended: evolve the existing native apps, keep independent profile endpoints and
add authenticated, optional, profile-scoped server capabilities. Reuse actual Hermes
session/task/media APIs through a narrow adapter. Capability absence is visible and
does not break stock conversations. Publish protocol documentation, reproducible server
patches/packages, tests and rollback instructions with every integration.

Alternatives considered:

- Client-only implementation avoids server work but cannot deliver genuine shared
  sessions, authoritative task progress, real pet delivery or offline server retention.
- A new host-wide multiplexed gateway could centralize these features, but expands
  authorization and routing scope and is explicitly outside this request.

Group work into independently testable plans: server capabilities and persistence;
live pets/expressions/audio; durable history/tasks/handoff; native conversation and
sharing experiences; diagnostics/wake; setup/migration; integrated delivery/acceptance.
This is an umbrella design, not one giant implementation task.

## Feature contracts and evidence

| ID | Deliverable | Completion evidence |
| --- | --- | --- |
| O1 | Fetch the selected profile's real active pet, authenticate and validate assets, cache per endpoint, retain portrait fallback | Actual opted-in server and watch transfer, offline cache and profile isolation; malformed/unsupported assets retain usable conversations |
| O2 | Akuma listening, playback-driven talking and sleeping companion poses; reusable pipeline preserves base art and registration | Akuma art review kept private; measured pose registration, real playback mouth response, reduced-motion/ambient behavior; other personal pets untouched |
| O3 | Negotiate Opus independently in each direction with stock PCM fallback | Actual phone/watch/server negotiation and same-phrase STT, payload, latency and battery comparison; unsupported encoder/server fallback |
| O4 | Optional phone-managed ElevenLabs shared/per-profile keys and voice overrides | Real bounded provider synthesis, correct profile voice and phone/watch playback, cancellation and quota/error behavior; default server speech remains usable |
| O5 | Bounded reconnect outbox with IDs, expiry, acknowledgement and deduplication | Real disconnect/reconnect, lost acknowledgement, restart/expiry, eviction and cross-device/profile isolation; never replay recordings, actions, approval answers or paid synthesis |
| N1 | Durable named/searchable/bookmarked conversations; continue the same server session on phone or watch | Process restart and actual two-device continuation into the same session, stable event IDs, duplicate/conflict handling, opt-in history scope and deletion |
| N2 | Cross-box task inbox with Working/Needs input/Finished/Failed and notifications | Actual task/run IDs and server lifecycle events, offline source retention, correct-profile open, expiry-safe approval, permission-denied notification fallback |
| N3 | Share text/links, then supported images/documents, into a reviewed profile conversation | Native Android sharesheet, bounded URI reads and MIME validation, explicit recipient/content/send review, real server attachment ingestion and readable unsupported-type errors |
| N4 | Editable profile-bound prompt actions and Android shortcuts | Create/edit/reorder/remove, exact profile routing, missing-profile recovery, explicit launch and one send; no hidden scheduled execution |
| N5 | Walking mode with larger controls, concise spoken replies, preferred audio routing, Replay/Stop/More | Actual earbuds/speaker routing and interruption checks, preserved full text, accessible phone/watch layouts and no unattended always-listening microphone |
| N6 | Structured reply cards for links, addresses, code and checklists | Strict parsing and bounded rendering, safe URI/action dispatch, copy/open/check behavior, full phone detail and concise watch presentation, plain-text fallback |
| N7 | Explicit agent handoff of selected question, summary and attachments | Preview recipient/content, deliberate send, distinct target conversation and provenance, wrong-profile/late-callback isolation; no invisible whole-history transfer |
| N8 | Connection and request-stage health plus redacted diagnostics | Real stage transitions and timeouts, source/transport/auth distinction, copy/export report verified free of credentials, transcripts, artwork and private endpoints by default |
| N9 | Authenticated remote wake for configured supported boxes | Explicit target allowlist, actual capability probe and real supported sleeping-host wake, authorization/rate-limit tests and clear unsupported/unavailable states |
| N10 | Reviewed QR setup and safe configuration/art/theme/action/widget migration | QR expiry/replay/host-review tests, fresh-device import, tamper/size validation, identity exclusions, no destructive merge and truthful launcher-placement limits |

Each feature must have a source-bound evidence entry. A mock server, screenshot or green
unit test alone does not prove real Hermes integration, speech quality, remote wake or
physical battery behavior. Hardware/firmware changes that cannot be performed remotely
remain explicit external dependencies; they are not marked complete.

## Server and data boundaries

- Preserve stock v0.2.0 behavior with every optional capability disabled. Keep the Kotlin
  protocol module Android-free. Do not depend on the excluded multiplexed gateway.
- Use authenticated enrollment and explicit profile grants for history, tasks, assets,
  attachments and wake. An existing conversation key does not silently authorize all
  historical conversations from unrelated Hermes surfaces.
- Server session/run IDs and ordered event IDs are authoritative. Phone and watch retain
  separate device identities. A visible continuation operation binds an approved device
  to the chosen session; do not merge transcripts by matching text or timestamps.
- Do not infer task cancellation from closing a socket. Expose cancellation only where
  the actual adapter confirms it. Current approval expiry remains authoritative.
- Keep the phone byte-forwarding watch TLS. Optional phone synthesis intentionally has
  its own scoped text/audio channel and never obtains the watch's pairing key.
- Avoid opening Hermes administrative/dashboard APIs to clients. No public endpoint,
  broad tailnet policy, unrelated service upgrade or publisher account change is needed
  by this design.
- Use bounded, encrypted device caches and bounded server retention. Defaults are
  proposed in the decision batch. Deletion scope must distinguish a device cache from
  the server's canonical conversation. Never retain microphone PCM as history by default.
- Validate attachment byte/dimension/type limits before decoding or ingesting. Keep
  uploads and temporary files profile-bound, expiring and inaccessible through arbitrary
  client filesystem paths. No unauthenticated URL fetching from a supplied attachment.
- QR setup carries reviewed connection metadata or a short-lived limited setup grant,
  never a permanent pairing key, provider key or administrative credential. Enrollment
  still requires the normal intended-device approval.
- Migration preserves settings/artwork/actions and widget mappings. Credentials and
  device identity are excluded; a new installation enrolls independently. Android's
  launcher owns widget placement, so import must explain when re-adding is necessary.

## Native experience

Use existing Courier branding, theme tokens and accessible native components. Phone
navigation gains conversations and tasks without crowding profile management. The watch
keeps horizontal profile swipes and its clear portrait/pet hold-to-talk surface; no new
floating controls or microphone badge cover art. The Jump footer remains at the end.

Conversation detail offers thread selection/history, attachments, action templates,
structured results and an explicit handoff sheet. Busy turns and live approvals keep
the existing selection locks. Every asynchronous response is scoped to profile/session/
request generation so switching cannot insert stale content or audio.

Pet/portrait presentation is selectable per profile; the owner's decision controls the
initial mode. Only the selected foreground page animates. Static portrait/pet fallbacks
remain available for unsupported sheets, offline, reduced motion, Battery Saver and
ambient. Existing portrait overrides and GIF files are retained even in Pet mode.

Walking mode is opt-in, uses the same deliberate recording gesture and haptic cues,
and retains the complete textual answer. Concise speech must not silently replace the
stored answer. Playback supports user-selected routing and Stop/Replay/More; permissions
and audio-focus loss remain explicit. No background microphone or wake-word listener.

Notifications default to private lock-screen content and visual/haptic delivery, with
per-profile controls. Old recovered events never automatically speak or answer approval
prompts. The task inbox represents real adapter state, not a guessed progress percentage.

Remote wake uses configured hardware addresses through an authenticated allowed home
sender. Use the existing M5 Max only while it is awake/reachable unless the owner names
another sender. Do not install the previously declined persistent Mac awake helper.
Wake support depends on the actual NIC/network/firmware; report capability honestly.

## Delivery and autonomy

The recorded answers above control design preference, rollout scope, existing provider/art
usage, data scope/retention and physical tests. Routine compatible choices do not need
repeated approval. Device operations await the owner's promised wireless confirmation;
software and server preparation can proceed independently.

Before a server rollout, prepare the concrete reviewed changes, backup/rollback and
compatibility checks. A rollout scope selection may authorize applying those changes
after the checks, starting with one profile and expanding only after actual smoke tests.
Do not modify unrelated Hermes configuration or restart unrelated services.

Build and install only matching-signer preserving updates. Back up APK/data first and
verify identities/artwork/settings afterward. Do not uninstall/reset either device to
work around signing, pairing, test fixtures or a dropped debugging connection. Device
control and real service/provider test calls follow the owner's selected scope.

Keep personal artwork, endpoints, private receipts, credentials and deployment mapping
inside ignored .local/. Public docs/screenshots use synthetic profiles/art only. Keep
the draft PR reviewable; merging, release signing and Play publication are separate.

## Verification and tracking

For each workstream: focused failing regression or integration check where appropriate,
implementation, meaningful targeted checks, strict build/lint/format/warnings, capability-
off stock compatibility and an evidence receipt. Use native emulator verification for
layout, large fonts, three themes, permissions and process/lifecycle paths; use actual
authorized boxes/devices for claimed integration. Measure audio/power rather than infer
benefits from a codec setting or an animation capture.

Track all fifteen items as designed / implemented / software verified / live integrated /
physically accepted with individual evidence and unresolved dependencies. Update
docs/progress.md, docs/upstream.md and SESSION_STATUS.md as the implementation advances.
The goal stays active until every requested deliverable meets its contract; no narrowing
to the easiest client-only subset, and no publication claim from a debug sideload.
