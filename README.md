# Hermes Gadget for Wear OS

A Wear OS watch app and Android phone companion for talking to your own Hermes agent, approving prompts, and seeing your profile's pet on your wrist.

**Status:** repository bootstrap. Application code and the M0–M8 milestones are not implemented yet.

The initial target is the Samsung Galaxy Watch Ultra, with support planned for Wear OS 4+ devices. The app will connect to user-configured Hermes servers, using a phone relay by default or a direct WebSocket connection when configured. There is no hosted backend or analytics service planned.

## Project brief

The [project brief](docs/hermes-gadget-wear-prompt.md) defines the requirements, upstream sources, acceptance criteria, and milestones. [Progress](docs/progress.md) records what has actually been delivered.

Before implementation, verify the Hermes Gadget SDK at `v0.2.0` and the referenced Hermes pet code, then write `docs/PRD.md`, `docs/architecture.md`, and `docs/pets.md`. Decisions marked **DECISION** in the brief require maintainer input.

## Planned modules

| Directory | Purpose |
| --- | --- |
| `protocol/` | Pure Kotlin/JVM protocol, framing, authentication, and negotiation |
| `wear/` | Wear OS UI, audio, actions, pets, Tile, and complication |
| `mobile/` | Android onboarding, server management, relay, and optional client TTS |
| `server/` | Upstream-bound Hermes Gadget SDK patch series |
| `tools/` | Pet tooling, development server helpers, and CI utilities |

These directories will be introduced during implementation.

## Public repository hygiene

Keep personal server addresses, tailnet details, credentials, signing keys, and personal pet art out of commits and screenshots. Store maintainer setup notes in the ignored `.local/` directory. Never commit Android `local.properties`, device keys, or BYOK secrets.

## License

[MIT](LICENSE).
