# Build: Hermes Gadget for Wear OS (watch + phone companion)

You are starting a fresh, public, MIT-licensed repository. Build a Wear OS app (with a phone
companion) that turns a smartwatch into a **Hermes Gadget**: hold to talk to your own Hermes
agent, read and hear the answer on your wrist, approve prompts, and see your profile's pet.
Primary test device: **Samsung Galaxy Watch Ultra** (Wear OS 5+, 480×480 round AMOLED, mic,
speaker, LTE, Quick Button). The end state is a Play Store listing that anyone can install and
point at **their own** Hermes, typically reached over **their own Tailscale tailnet** via their
phone.

Plan before you code. Write `docs/PRD.md` and `docs/architecture.md` first, then work in the
milestones below. Each milestone ends with passing tests and a short demo note in
`docs/progress.md`. Ask me before any decision marked **DECISION**.

---

## 1. Ground truth — read these before designing anything

The Hermes side already exists. Do not reinvent it; speak it.

- **Gadget SDK** (MIT): `github.com/Adolanium/hermes-gadget-sdk`, pinned to tag `v0.2.0`.
  Read in full: `docs/protocol.md` (the wire contract), `docs/hermes-integration.md`,
  `docs/architecture.md`, `docs/porting.md` (last section: "Devices that cannot run the core can
  speak the protocol directly" — that is us), `docs/faces.md`, `docs/tailscale-funnel.md`.
- **Reference clients to port from:** `python/hermes_gadget/linux/client.py` (pure-protocol
  client, ~14 KB — the closest thing to what we are writing) and the simulator in
  `python/hermes_gadget/sim/`.
- **Test server without Hermes:** `python/hermes_gadget/devserver.py` runs the real plugin hub
  with a scripted brain (echo, mic→speaker loopback, pairing, pushed cards/prompts/images/actions).
  Use it for CI and day-to-day development.
- **Protocol test vectors:** `tests/test_protocol.py` and the firmware core tests. Our Kotlin
  protocol module must pass the same vectors (device-id derivation, HMAC auth, OTA MAC, binary
  header encoding).
- **Hermes pets** (in `NousResearch/hermes-agent`): `agent/pet/constants.py`,
  `agent/pet/store.py`, `agent/pet/generate/{prompts,atlas,orchestrate}.py`.

Facts already established (verify, don't assume):

- Protocol v1: one WebSocket per device, subprotocol `hermes-gadget.v1`, JSON text frames with
  `type`, binary frames with a 4-byte header `[channel u8][stream u8][seq u16 LE]` (channels:
  `0x01` audio PCM16 mono, `0x02` image RGB565, `0x03` firmware). Handshake
  `hello → challenge → auth (TOFU key, then HMAC-SHA256) → welcome → pairing/paired`.
  `device_id = "hg-" + hex(sha256(key))[0:16]`. Heartbeat ping/pong, `3 × heartbeat_s` = dead.
- **New optional message types and optional fields do not bump the protocol version**; receivers
  ignore unknowns. Every extension we add must follow that rule, so stock gadgets and stock
  servers keep working.
- The plugin accepts any mic sample rate but only `format: "pcm16"` today. `charset` other than
  `"ascii"` keeps UTF-8.
- Pets are **profile-scoped**: `<HERMES_HOME>/pets/<slug>/` with `pet.json` + a spritesheet.
  Current Codex atlas: **8 cols × 9 rows, 192×208 px cells (1536×1872)**, rows top→bottom
  `idle, running-right, running-left, waving, jumping, failed, waiting, running, review`;
  6 frames stepped per state, 1100 ms loop. Legacy sheets are 9×8. The renderer **infers the
  row taxonomy from the sheet's row count** (`state_rows_for_grid`).
- The pet generator normalises **one shared scale across all rows of a hatch**
  (`compose_atlas(normalize_cells(...))`), so a row generated later and spliced in makes the pet
  visibly change size between states.
- Each Hermes **profile** has its own TTS settings (`tts.elevenlabs.voice_id` in its
  `config.yaml`, `ELEVENLABS_API_KEY` in its `.env`) and its own pets. The gadget adapter binds
  its own port per profile (`platforms.gadget.extra.port`) and stores device keys per profile
  home; upstream has only unit-tested multi-profile hosting.

## 2. Non-negotiables

- **Public repo hygiene:** no hostnames, IPs, tokens, API keys, tailnet names, or personal pet
  art in the repo, its history, its tests, or its screenshots. Secret scanning in CI.
- **User owns everything:** the app connects only to servers the user configures. No backend of
  ours, no analytics by default, no telemetry without opt-in.
- **Backward compatible both ways:** our app must work against a stock `v0.2.0` plugin with
  every extension off, and a stock gadget must work against our server-side extensions.
- **Server-side changes are upstreamable:** implement them as a clean branch/PR series against
  `hermes-gadget-sdk` (plugin + `docs/protocol.md` + tests), not as a private fork hidden in this
  repo. Track their status in `docs/upstream.md`. Hermes-agent changes (pets) likewise go in
  `docs/upstream.md` as proposed PRs.
- **Secrets on device:** device key and any BYOK keys encrypted with an Android Keystore key;
  never logged, never sent to the Hermes server unless the user explicitly chooses it.
- Tests for everything; zero-warning builds (lint, detekt/ktlint, Gradle warnings).

## 3. Architecture

```
watch app  ──(A) direct WebSocket──────────────────────────────►  Hermes gadget endpoint
    │                                                             (LAN / tailnet / wss public)
    └──(B) Wear Data Layer ChannelClient ─► phone app ─ WebSocket ─┘
                    (Bluetooth, Wi-Fi or cloud — the OS chooses)   (phone is on the tailnet via
                                                                    the user's Tailscale app)
```

Modules (Gradle, Kotlin, version catalogs):

- `protocol/` — pure Kotlin/JVM: message models, binary framing, handshake state machine, HMAC,
  device identity, codec negotiation. No Android imports. Pinned to the upstream vectors.
- `wear/` — Compose for Wear OS (+ Horologist where it helps). The gadget itself: UI, mic,
  playback, pet renderer, Tile, complication, ambient mode, haptics.
- `mobile/` — phone companion: onboarding, server/profile management, relay (transport B),
  BYOK key vault, optional client-side TTS, pairing helper (show the approve command / QR).
- `server/` — the upstream-bound changes to the gadget plugin (Python), with tests, kept as a
  patch series against the pinned SDK tag.
- `tools/` — pet extension tooling (see §6), devserver scripts, CI helpers.

### Transports

- **(A) Direct:** the watch opens the WebSocket itself — home Wi-Fi/LAN, or a public `wss://`
  endpoint (e.g. Tailscale Funnel) if the user chose to publish one. LTE watches without the
  phone can only use a public `wss://` endpoint.
- **(B) Phone relay (default):** the watch streams protocol frames over Data Layer
  `ChannelClient`; the phone forwards bytes to the server. **The phone is a dumb pipe:** the
  device key and HMAC stay on the watch, so a compromised phone app cannot impersonate the
  gadget. The phone reaches the tailnet because the Tailscale Android app is a system VPN; we do
  not embed Tailscale in v1. Note `tsnet`-in-app (userspace tailnet via gomobile) as a later
  option in `docs/architecture.md`, with its auth and licensing implications.
- Transport is chosen automatically (relay if the phone is reachable, else direct if configured)
  with a manual override. Reconnect with backoff; surface which path is live in the UI.
- Cleartext: `ws://` is acceptable on LAN and inside a tailnet (WireGuard encrypts it); any
  public endpoint must be `wss://`. Implement this as an explicit per-server setting with a
  warning, and get Android's network security config right for it. **DECISION** if a clean
  config isn't possible.

### Compression (implement)

PCM16 at 16 kHz is ~256 kbps each way — too much for a Bluetooth-proxied link. Add Opus:

- Negotiate in `hello`: `caps.mic.formats: ["opus","pcm16"]`, `caps.speaker.formats: [...]`.
  Server answers with the chosen format; absent support means PCM16 (stock behaviour).
- Uplink: `audio.start {format:"opus", rate, frame_ms}`; binary audio frames carry one Opus packet
  each. Encode with Android `MediaCodec` (Opus encoder, API 29+); measure on the Ultra.
- Server decode without new native Python deps if possible: the Hermes package manager already
  ships an `ffmpeg` tool on every install — prefer piping through it; otherwise justify a
  dependency in the PR.
- Downlink: server encodes TTS to Opus for devices that accept it (check whether the TTS
  provider can emit Opus directly before transcoding). Device decodes with `MediaCodec`, ~1 s
  jitter buffer as the protocol expects.
- Acceptance: a measured bitrate table (PCM vs Opus, both directions) and STT accuracy on the
  same utterances must not regress noticeably.

## 4. Watch UX (Galaxy Watch Ultra first, any Wear OS 4+ watch)

- Round 480×480 layout; respect the inscribed square for text. Large type, short replies,
  scroll with rotary input where the hardware has it, swipe otherwise.
- **Hold the screen to talk** (matches the AMOLED reference boards); swipe down = cancel;
  hold cancel 2 s = `session.new` (with the server's confirm prompt). Document assigning the
  Ultra's Quick Button to launch the app. Haptic ticks for listen-start, listen-end, reply,
  prompt.
- Screens: pairing (code + the `hermes gadget pair` hint), ready, listening (live level meter),
  thinking (`status` text), streaming reply (`reply.delta`), cards (`display`), images,
  yes/no `prompt` (ignore presses in the first 0.6 s, as the firmware does), notices, errors.
- **Tile** ("hold to ask" + last reply) and a **complication** (connection/pet state).
- **Ambient mode:** low-power pet silhouette, no burn-in.
- **Battery:** connect on demand (app open, Tile, Quick Button), keep a foreground service
  (`microphone` / `connectedDevice` types) only during a conversation, disconnect after an idle
  timeout. Document honestly that server messages sent while disconnected are lost today;
  propose an upstream "pending outbox on reconnect" extension (prompts already re-send).
- Device actions advertised in `hello` (each with a model-facing description and JSON-Schema
  params): `watch.vibrate`, `notification.show`, `timer.start`, `screen.brightness`.
  Sensors (`battery_pct` by default; heart rate / steps only behind an explicit opt-in, because
  they enter the agent's context).

## 5. ElevenLabs BYOK

Two modes; the user picks per server/profile:

1. **Server TTS (default):** the Hermes profile synthesises with its own key and voice, exactly as
   today. The app holds no ElevenLabs key.
2. **Client TTS (BYOK):** the device advertises `caps.tts: "client"`; the server (extension) sends
   reply text only, plus an optional `voice` hint from the profile config. The **phone** calls
   ElevenLabs streaming TTS with the user's key (Opus output if available), and relays audio to
   the watch. Keys: one **shared** key, or **per-profile** key + voice ID overrides. Keys live
   only in the phone's Keystore-backed vault. In direct mode (no phone), fall back to server TTS.

Never embed a key in the build. A "test key" button validates against the ElevenLabs API and
shows remaining quota if the API exposes it.

## 6. Pets — including the frame-gap analysis (deliverable)

Render the profile's active pet on the watch, driven by gadget state.

**Delivery (extension):** `pet.request` → server replies `pet.manifest` (slug, display name,
cell size, rows present, loop ms, sheet sha256) and streams the PNG sheet(s) on a **new binary
channel `0x04` (asset)**. The existing image channel is RGB565 with no alpha, so it cannot carry
sprites. Cache by sha256 on the watch; nearest-neighbour scaling for pixel art.

**State mapping with today's 9 rows:**

| Gadget state | Row |
|---|---|
| ready / idle | `idle` |
| connected, paired, new session | `waving` |
| `turn.start`, `status` (thinking, tools) | `running` (in-place work) |
| transcript shown | `review` |
| yes/no `prompt` (approval) | `waiting` (exact semantic match) |
| `turn.end` success | `jumping` (once, then idle) |
| `turn.end` failure, auth/connection error | `failed` |
| (unused on a watch) | `running-left`, `running-right` |

**Gaps — rows the gadget needs that pets do not have:**

1. **`listening`** — mic open while the user speaks. Nothing reads as "attentive, ear toward
   you". Fallback: `review`.
2. **`talking`** — the reply being spoken. No row has a mouth. The gadget's own built-in faces
   carry a dedicated talk frame for exactly this. Generate **3 mouth shapes (closed/half/open)**
   and pick by playback amplitude, so it lip-syncs instead of looping. Fallback: `idle` with a
   procedural amplitude-driven bob.
3. **`sleeping`** (recommended) — disconnected/offline. Ambient mode is derived procedurally
   (outline of an idle frame), not generated.

**Constraints for adding them:**

- Do **not** add rows to the existing 9-row sheet: Hermes infers the taxonomy from the row count,
  so an 11- or 12-row sheet breaks desktop, terminal and petdex consumers. Use a separate
  companion sheet (e.g. `gadget-sheet.png`, same 192×208 cells) referenced from `pet.json`.
- Because a hatch normalises one shared scale, extra rows generated later will not match.
  Either (a) generate them in the same hatch (Hermes-side change to the generator: optional extra
  rows, same normalisation pass), or (b) generate them later and rescale to the existing
  sheet's measured character bounding box from the `idle` row. Prototype (b) in `tools/` so
  existing pets can be extended without a full re-hatch; propose (a) upstream.
- Write `STATE_ACTIONS`-style prompt text for the three new rows. `talking` must keep the body
  still except the mouth/head, or it reads as a different animation.
- The app must work with pets lacking the companion sheet (fallback mapping above).

Deliver `docs/pets.md` with this analysis confirmed or corrected against the real code and real
pets, before building the renderer.

## 7. Extra credit — every Hermes profile

Goal: one watch talks to any of the user's Hermes profiles, each with its own pet and voice.

- **v1 (works with the stock plugin):** the app manages several **endpoints**, one per profile
  (each profile's gadget adapter on its own port), each with its own pairing and **its own
  device key** (do not reuse a key across profiles). Switch by swipe/rotary on the watch; each
  endpoint carries its profile name, pet and TTS mode (shared or per-profile BYOK key).
- **v2 (extension, upstream proposal):** one gadget port per Hermes host that lists profiles
  (`profiles.list`) and routes a device with `profile.select`, so users open one port instead of
  N. Spell out the plugin changes this needs for a multiplexed gateway (today only the root home
  loads the plugin) and get an upstream maintainer's view before building it. **DECISION.**
- Verify multi-profile behaviour against a real multiplexed gateway, not just unit tests; upstream
  has not.

## 8. Play Store readiness

- Package names, signing, `targetSdk` current, Wear OS app-quality guidelines, round-screen
  screenshots (synthetic data only), `com.google.android.wearable.standalone` set to match the
  transports actually supported.
- Privacy policy and Data Safety: microphone audio goes only to the user's own server; BYOK keys
  stay on the device; no collection by us. Foreground-service type declarations justified.
- Original sample pet art only; no Hermes mascot or personal pets in the store listing.
- A self-host guide: install the plugin, pair, reach it over Tailscale (phone relay) or Funnel
  (direct), and the security trade-off of each.

## 9. Milestones

- **M0** Repo skeleton, CI (build, unit tests, lint, secret scan), PRD + architecture docs,
  `docs/pets.md` analysis.
- **M1** `protocol/` passing upstream vectors; JVM integration test against `devserver.py`.
- **M2** Watch direct mode on LAN: pair, typed text round trip, hold-to-talk PCM16, playback,
  prompts, cards, images, actions. Demo against a real Hermes.
- **M3** Phone relay over the Data Layer; Tailscale via the phone; automatic transport selection.
- **M4** Opus both ways (server patch series + app), with the measurement table.
- **M5** Pets: delivery extension, renderer, state mapping, fallbacks; companion-sheet tooling.
- **M6** BYOK client TTS, shared and per-profile keys.
- **M7** Multi-endpoint profiles (v1); write up v2 for upstream.
- **M8** Tile, complication, ambient, battery pass on the Galaxy Watch Ultra; Play Store
  internal testing track.
