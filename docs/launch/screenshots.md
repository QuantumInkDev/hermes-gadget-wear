# Screenshot and media plan

## Rules for every capture

- Only synthetic profiles and art: the demo fixtures in `docs/demo/carousel/fixtures` and
  original procedural pets. No personal pets, no commercial game characters, no real
  profile names.
- No real addresses. Use a demo server on `wss://demo.example.ts.net:8765/gadget`
  (`tools/phone-client-demo.py`, `tools/carousel-demo.py`). Check for tailnet names, device
  ids and pairing codes before anything is posted.
- Status bar: clean demo mode (`adb shell settings put global sysui_demo_allowed 1`, then the
  demo-mode broadcasts), with full battery and no notifications.
- Capture each shot in Dark and in Electronic Moonlight. Use Moonlight for the hero shots.

## Reuse (already captured, synthetic)

| Need | Existing file |
| --- | --- |
| Watch carousel, portraits | `docs/demo/carousel/01-portrait-first.png` … `03` |
| Watch pets | `docs/demo/carousel/07-pet-first.png` … `09` |
| Watch listening | `docs/demo/carousel/10-listening.png` |
| Watch ambient | `docs/demo/carousel/05-ambient.png`, `docs/demo/m8/ambient.png` |
| Watch approval, reply, pairing | `docs/demo/m2/approval.png`, `reply.png`, `pairing.png` |
| Watch tile | `docs/demo/m8/tile.png` |
| Themes | `docs/demo/native-clients/appearance/*` |
| Phone directory, conversation, keyboard | `docs/demo/native-clients/phone/*` |
| Home widgets | `docs/demo/home-widgets/*` |
| Icon and feature graphic (drafts) | `docs/store-assets/icon-512.png`, `feature-1024x500.png` |

Recapture the phone directory: the Arrange button replaced the old reorder menu.

## New captures needed

### Phone (Play needs 2 to 8; 9:16, at least 1080×1920)

1. Profiles page with two boxes and the **Arrange** button.
2. Arrange sheet mid-drag (a row lifted).
3. Talk/Chat with a streamed reply and a structured card (link or checklist).
4. Inbox with Working, Needs input and Finished items.
5. Conversation list with a bookmark and the search field.
6. Walking mode controls (Replay, Stop, Tell me more).
7. Handoff review sheet.
8. Home screen with two widgets.

### Watch (Play needs at least one; square 1:1, at least 384×384, no frame)

1. Profile carousel with a pet (hero).
2. Holding to talk, listening ring.
3. Reply streaming.
4. Approval prompt.
5. Conversations & inbox list.
6. Walking mode.

### Setup guide (install.md)

1. Tailscale admin console → DNS → HTTPS Certificates enabled (blur the tailnet name).
2. Terminal: `tailscale serve status` showing one `https:8765` route (synthetic host name).
3. Phone: Tailscale connected.
4. Phone: Settings → Boxes and discovery form, filled in with placeholders.
5. Phone: Watch tab, relay on, after **Allow background relay**.
6. Watch: pairing code screen, plus the matching `hermes pairing approve` terminal line.
7. Watch: Artwork → Portrait / Pet.

Save these under `docs/demo/beta/` and link them from `docs/install.md` next to each step.

## Video (30 to 60 seconds, for X, LinkedIn and the portfolio)

| Time | Shot |
| --- | --- |
| 0–3 s | Close-up of the watch. A finger holds the face and the pet's ears perk up (listening) |
| 3–10 s | Spoken question, the reply streaming in, the pet talking |
| 10–16 s | An approval appears and is approved with a tap |
| 16–22 s | Swipe to two other profiles and their pets |
| 22–32 s | The phone: same conversation, "Continue exact conversation", then the Inbox |
| 32–40 s | Walking with earbuds, a short spoken answer, "Tell me more" |
| 40–45 s | End card: "Quantum Relay for Hermes · open beta · <link>" |

Record against the demo server, or against a real box with synthetic profiles and pets only.
Burn in captions; most feeds autoplay muted.
