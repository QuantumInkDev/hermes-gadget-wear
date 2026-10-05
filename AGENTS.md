# Hermes Gadget for Wear OS

This is a fresh Kotlin Wear OS and Android companion project. The authoritative requirements are in `docs/hermes-gadget-wear-prompt.md`; read them and `docs/progress.md` before starting implementation.

- Plan before coding. Read the upstream sources specified in the brief and write `docs/PRD.md`, `docs/architecture.md`, and `docs/pets.md` before implementing the relevant features.
- Follow M0–M8 in order and record passing checks and a short demo note in `docs/progress.md` at each milestone. Do not mark untested work complete.
- Ask the maintainer before decisions explicitly marked **DECISION**.
- Keep the protocol module free of Android imports and preserve compatibility with the stock Hermes Gadget SDK `v0.2.0`.
- Keep server extensions upstreamable and track proposed changes in `docs/upstream.md`.
- Never commit personal endpoints, tailnet details, credentials, signing keys, or personal pet art. Maintainer setup belongs in the ignored `.local/` directory.
- Do not infer a local Hermes endpoint. Ask for the server URL and pairing approval when M2 reaches hardware testing.
- Keep `SESSION_STATUS.md` current so another session can continue from verified progress.
