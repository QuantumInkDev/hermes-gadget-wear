# Product requirements

Status: native client implementation and software acceptance, 2026-10-12. Hardware and release gates remain open. The [original brief](hermes-gadget-wear-prompt.md) remains the scope; verified corrections are recorded in [architecture](architecture.md) and [pets](pets.md).

## Native client behavior — 2026-10-12

The approved [native design](superpowers/specs/2026-10-12-native-client-design.md) is implemented:
Quantum Relay for Hermes uses original Voice Q artwork and independently persisted Light,
Dark and Electronic Moonlight appearances. Existing installations default to Dark; ambient
stays black and static. The phone provides Profiles, Watch and Settings, grouped searchable
portraits, accessible profile details, foreground Talk/Chat, live capture/playback meters,
a muted-volume hint and revision-safe watch tile editing.

The watch retains stable swipe pages, per-page drafts/scroll, selection locks and rotary
focus, with a direct profile picker. Hold the portrait to record, release to send or drag
down to cancel; short taps and interrupted gestures do not send. Actual capture drives the
external rings and very light start/accepted-finish haptics. A foreground active turn may
keep the display on for at most two minutes; completion, background and ambient release it.
Historical missing replies during an away trial remain an unconfirmed transport diagnosis.

Focus and Pinned profiles tiles render static local pictures for one and four stable keys.
They launch the intended page without starting recording. Unknown/removed keys stay unavailable;
locked selection is deferred visibly. The watch owns a durable revision, and stale phone edits
require review instead of overwriting newer watch choices. Synthetic native acceptance and its
limits are recorded in the [demo](demo/native-clients/README.md). Physical tile addition,
associated Data Layer phone edit acknowledgement, speech, away replies, haptic strength and
battery remain gates. Physical phone snapshot readback matches the watch's existing Focus
revision, empty grid and all 19 profile keys; both devices received preserving native updates.

## Outcome and users

A user installs a Wear OS app and Android companion, configures their own Hermes Gadget endpoint, pairs on their own host, and holds the watch screen to speak. The watch shows the transcript, working status, cumulative reply, spoken answer, and approval prompts. Each configured profile has a separate identity, pet, and voice configuration. There is no project-operated backend, account requirement, analytics, or default telemetry.

Primary hardware acceptance is on the Galaxy Watch Ultra. Emulator, JVM, and scripted-server tests establish correctness; they cannot establish microphone quality, battery life, Bluetooth throughput, or a successful real Hermes conversation.

## Requirements and acceptance

| ID | Requirement | Acceptance gate |
| --- | --- | --- |
| R01 | Stock protocol v1 compatibility | SDK `v0.2.0` identity, authentication, OTA MAC, and framing vectors; real devserver enrollment and reconnect |
| R02 | Authorized conversations | Watch generates one random key per endpoint, encrypts it with Android Keystore, and follows server pairing state; no input before paired |
| R03 | Direct transport | User enters an endpoint; no discovery of personal addresses. Text, PCM capture/playback, prompts, cards, images, and actions tested against the devserver, then real Hermes |
| R04 | Phone relay | Data Layer ChannelClient, matching app IDs/signatures, bounded queues, reconnect, cancellation, and visible transport selection; security decision below resolved before M3 |
| R05 | Audio | Hold to record; release sends; cancel discards; live level meter; playback abort on barge-in; no retained recordings by default |
| R06 | Approvals | Prompt blocks unrelated controls; first 600 ms ignored; exactly one answer; expiry/withdrawal prevents a late answer |
| R07 | Opus | Optional negotiated formats; absent support means PCM16; measured uplink/downlink bitrate and same-utterance STT comparison on the Ultra |
| R08 | Pets | Authenticated asset delivery with bounded size and hash validation; legacy/current taxonomy, occupied-frame counts, absent/partial sheet fallbacks; companion sheet preserves base atlas |
| R09 | BYOK | Server TTS default; phone-only encrypted shared or endpoint-specific keys; keys never forwarded to Hermes; direct mode uses server TTS |
| R10 | Profiles | One endpoint, pairing, and key per profile; no key reuse. Multiplexed profile routing is a proposal requiring maintainer input |
| R11 | Watch surfaces | Round safe area, readable text, scroll/rotary where available, haptics, Tile, complication, ambient silhouette, and idle disconnect |
| R12 | Device actions | Advertise only implemented actions; validate JSON arguments; exactly one result; bounded duration; battery only by default; health sensors opt-in |
| R13 | Public hygiene | CI secret scan and push protection; no personal addresses, credentials, personal art, recordings, or identifying screenshots |
| R14 | Release | Current target API policy, signed internal-track packages, hardware demo, privacy/Data Safety review, synthetic screenshots, and self-host instructions |

## Interaction and lifecycle

Screens are setup, connecting, pairing, ready, listening, thinking, reply, card, image, approval, notice, offline, and error. State overlays must not discard the current reply or silently resolve an approval. Only the server's current turn updates that turn's reply. `reply.delta` replaces the preview because it is cumulative.

Connection is on demand while an active conversation needs it. A microphone foreground service starts only following a user gesture and permission grant. Tile/complication surfaces launch the conversation; they do not bypass microphone permission or background-start restrictions. The final service types and idle timeout require hardware validation. Messages pushed while disconnected are lost on the stock SDK except pending questions that the adapter re-sends.

A two-second cancel hold requests a new session. The stock adapter may auto-confirm its own deliberate `session.new`; the app must not promise a second server question. Other server prompts retain the approval guard.

## Boundaries and unresolved choices

- Relay confidentiality: the maintainer requires watch-owned end-to-end TLS through a byte-forwarding phone relay (2026-10-06), with direct fallback under its separately approved endpoint rules. First enrollment includes the device key, so the phone must not terminate the server WebSocket/TLS. Certificate/authentication failures never silently downgrade security.
- Dynamic cleartext: the maintainer approved explicit LAN/tailnet `ws` on 2026-10-05, with a per-endpoint warning. The watch platform policy allows cleartext; the direct connector requires declaration and acceptance, rejects public destinations, and disables redirects/proxies. Phone relay requires watch-owned TLS; accepted cleartext is direct-only.
- Multiplexed gateway v2 stays a documented proposal until user approval and upstream maintainer feedback.
- No firmware OTA installation on Android: test the shared MAC vector, but omit `caps.ota`; distribution uses Android installation/update mechanisms.
- Do not claim that every Hermes installation has working ffmpeg or that every Ultra has a usable Opus encoder before checking them at M4.

## Approved multi-box profile carousel — 2026-10-11

The maintainer requests swipeable watch profiles from three independently available Hermes
boxes, initially showing each profile's picture and later its pet with additional animations.
The [concrete design](superpowers/specs/2026-10-11-multi-box-profile-carousel-design.md)
specifies an automatic phone catalog, independent TLS Gadget endpoints, stable offline pages,
portrait/pet fallbacks and safe selection. The maintainer approved app and isolated catalog implementation;
real-box acceptance is not claimed. The multiplexed Gadget v2 gate remains separate.

The maintainer subsequently requests live setup and explicit watch-specific portrait choice.
Imported box pictures are the default. A profile can choose a phone-selected watch picture
that takes precedence over future box portrait refreshes; returning to the box picture uses
its latest cached image. This choice is per source/profile, works before conversation setup,
and never changes a box's original artwork or endpoint/pairing. Validated pets retain priority.
The [implementation plan](superpowers/plans/2026-10-11-watch-portraits-and-profile-setup.md)
also covers the generic publication setup instructions and private live rollout evidence.

## Milestone gates

The maintainer approved the [client-usability follow-up](superpowers/specs/2026-10-12-client-usability-design.md)
on 2026-10-12: Quantum Relay for Hermes branding with an original icon, usable watch scrolling and
recording cues, phone profiles grouped/sorted by box with inline effective pictures, and
independently paired foreground phone text/voice conversations. Phone chat requires TLS;
it does not borrow watch keys or change the opaque relay. Animated watch-picture overrides
are an approved final stage with [explicit limits and PNG fallbacks](superpowers/specs/2026-10-12-animated-picture-design.md).
These are requirements; implementation and physical acceptance are recorded separately.

Follow M0–M8 from the brief. M0 delivers these design documents, runnable starter apps, a pure JVM module, reproducible wrapper/catalog, build/test/lint/format CI, and secret scanning. M1 adds wire models, framing, identity, handshake/heartbeat behavior, upstream vectors, and a JVM test using the actual SDK development server. M2 requires the maintainer's endpoint and approval plus a real hardware demo. M3–M8 retain the original measurements and release gates; unit tests alone never close them.

Every milestone records exact checks and a short demo in [progress](progress.md). Claims of success must distinguish local tests, CI, emulator, real Hermes, and physical watch evidence.
