# Hermes plugin catalog submission

How to get the Quantum Relay server side into the
[Hermes plugin catalog](https://github.com/NousResearch/hermes-agent/tree/main/plugin-catalog).
The catalog rules quoted here are from the local Hermes v0.22.5 docs
(`website/docs/developer-guide/plugins/catalog-submission.md`). Re-read the live
`plugin-catalog/README.md` before opening any pull request.

## Where we stand

| Fact | Consequence |
| --- | --- |
| The server side is the Gadget plugin plus 166 commits (relay, pets, client speech, outbox, history, inbox, wake, attachments) on Adolanium's v0.2.0 | It is a fork of a plugin that is already listed (`gadget`, community tier, pinned at 0.3.0; upstream is at 0.4.1) |
| Relay history, inbox, outbox, handoff and wake need `relay-core-seams.patch`, about 15,000 lines in 50 Hermes core files | Rule 9 refuses any plugin that needs core patched or rebinds core at runtime. The `no core override` check fails it automatically |
| Rule 5: only the owner or a major contributor can submit an entry | We can't submit `gadget` changes ourselves. We can submit our own repository |
| Rule 16: a fork is listed only if it is materially different, or the original's author declined or hasn't answered a pull request in 30 days | A rebranded fork is declined. Upstream first |
| Upstream accepts outside contributions | Their 0.4.0 credits several contributors, including a native-admission hook (`validate_native_profile_route`) added to Hermes for the plugin. There is a working path |

**Result:** there is nothing to submit to the catalog today. There is a clear path in three
tracks that can run in parallel.

## Track A: turn the core seams into Hermes hooks

The catalog guide says: "If the hook you need does not exist, open an issue describing it: we
would rather add the seam than list a patch." Do that, then split the patch into small
reviewable pull requests, one seam each. The plugin then uses only public hooks.

The seams the patch adds today, from the files it touches:

1. **Adapter final-delivery context.** The adapter learns which turn and session a final
   reply belongs to (`gateway/platforms/base.py`, `run_turn*`). Needed for the outbox and
   history identity. Smallest and least controversial, so it goes first.
2. **Trusted native admission.** A native client admits a turn into a specific session it
   owns (`session_native_admission.py`, `run_native_admission.py`). Overlaps with the
   upstream `validate_native_profile_route` hook; build on that rather than duplicating it.
3. **Bounded history reads and scoped search.** Paginated, profile-scoped reads of a
   session's messages, and search limited to sessions the device is granted
   (`session_read_projection.py`, `session_scoped_search.py`, `hermes_state*`).
4. **Native session mutations.** Rename and delete of sessions the device owns
   (`session_native_mutations.py`, retirement).
5. **Prompt deadlines and native controls.** Exact cancel/stop of an identified run, and
   approval and clarify prompts with an expiry the client can show
   (`session_native_controls.py`, `tools/approval*.py`, `clarify_gateway.py`).
6. **Task projection and delegation receipts.** Run lifecycle states for the inbox (working,
   needs input, finished, failed) and own-request receipts (`session_task_projection.py`,
   `session_delegation.py`, `delivery_ledger.py`).

### Draft issue for NousResearch/hermes-agent

> **Title:** Plugin hooks for native clients: delivery context, scoped history, session
> controls
>
> I maintain Quantum Relay, an Android phone and Wear OS client that talks to Hermes through
> the Gadget platform plugin. Each paired device gets durable conversations it can continue
> on another device, a task inbox, a reconnect outbox for replies sent while it was offline,
> and explicit cancel/stop. Today this needs a local core patch, which the catalog rightly
> refuses (rule 9). I'd like to replace that patch with public hooks and send them as small
> pull requests, one per seam:
>
> 1. Final-delivery context on platform adapters (which session and run a final message
>    belongs to).
> 2. Native session admission. This builds on `validate_native_profile_route`.
> 3. Bounded, profile-scoped history reads and search over sessions a device is granted.
> 4. Rename and delete for sessions the device owns.
> 5. Run cancel/stop by id, plus approval and clarify deadlines exposed to the adapter.
> 6. Run lifecycle events for a task inbox.
>
> Each hook is inert unless a plugin registers for it. The working implementation and tests
> (about 30 gateway test files) exist against v0.22.5. I can start with (1), which is about
> one file of change. Would you take these, and do you have a preferred shape for history
> access from plugins?

Posting it is an outward action. Review it and post it yourself, or ask me to.

## Track B: upstream the plugin features to Adolanium

Rebase our features onto upstream `main` (0.4.1+) and offer them as separate pull requests.
Order them by how self-contained they are:

| Feature | Upstream state | Pull request plan |
| --- | --- | --- |
| Opus audio | Upstream 0.3.0 added Opus speaker audio | Check our negotiation against theirs. Adopt theirs where they overlap; send only the uplink and missing pieces |
| Pets and expressions | Upstream 0.4.0 added profile faces (`caps.faces`) | Propose pets as an extension of faces: animated states over the same channel. Discuss first in an issue |
| Client speech (phone voice) | Not upstream | Self-contained opt-in feature (`enable_client_tts`). Good first pull request |
| Reconnect outbox | Not upstream | Depends on Track A seam 1 |
| History, inbox, handoff, attachments | Not upstream | Depend on Track A. Large; discuss the design in an issue first |
| Remote wake | Not upstream | Self-contained, but host-specific. Could live in a separate plugin instead |
| Windows storage privacy verifier | Not upstream | Self-contained. Offer it |

Open a single issue first that introduces Quantum Relay, links the repo, and asks whether
they want these features in `gadget` or prefer a companion plugin. Their answer decides
Track C.

## Track C: our own catalog entry

Either of these makes a separate listing acceptable:

- **A companion plugin** (`quantum-relay`), registered alongside `gadget`, that adds only
  the relay methods (history, inbox, outbox, wake) through public Hermes hooks. This is
  materially different, so rule 16 doesn't apply. It needs Track A, and either a supported
  way to extend another plugin's platform or its own small listener.
- **A fork listing**, if Adolanium declines or doesn't answer for 30 days. The disclosure
  must then say "Derived from gadget".

In both cases the repository must be public, must not update itself (rule 3), must read
secrets through `agent.secret_scope.get_secret` (rule 11), and must pass
`hermes plugins validate <dir> --install-deps` locally before the pull request. Ship no
binaries (rule 17); we load libopus from the host, so that's fine. Make every risky
behaviour visible in the disclosure (rule 13).

### Draft entry: `plugin-catalog/quantum-relay.yaml`

```yaml
name: quantum-relay
repo: https://github.com/QuantumInkDev/quantum-relay-plugin   # to be created
sha: <40-hex commit, after validate passes>
title: Quantum Relay
description: "Native Android phone and Wear OS clients for your own Hermes: hold to talk on
  your watch, continue a conversation on your phone, see a task inbox across profiles, and get
  replies that arrived while you were offline. Adds durable per-device conversations,
  task lifecycle notifications, a bounded reconnect outbox, profile pets and optional phone
  voice to the Gadget platform. Disclosure — listens on the Gadget WebSocket port it is
  configured for; devices pair through Hermes pairing codes and receive only conversations
  and profiles they are granted per profile (relay-grants.json). Stores a bounded outbox
  (24 hours) and temporary attachments (7 days) under plugin-data/. Optional remote wake
  sends Wake-on-LAN packets or runs an owner-configured start command for allow-listed hosts
  only. Optional phone voice sends reply text to the paired phone, which calls ElevenLabs
  with the user's own key; the key never reaches Hermes. Uses Hermes's STT, TTS and the
  host's ffmpeg/libopus. No telemetry. Independent community project, not affiliated with
  Nous Research."
maintainer: QuantumInkDev
tier: community
category: platform
requires_hermes: ">=0.23.0"   # whichever release first ships the Track A hooks
docs_url: https://github.com/QuantumInkDev/hermes-gadget-wear/blob/main/docs/install.md
version: "0.2.0"
image: https://raw.githubusercontent.com/QuantumInkDev/quantum-relay-plugin/<sha>/docs/banner.png
screenshots: []   # up to 6, synthetic only, pinned to <sha>
capabilities:
  provides_tools: []
  provides_hooks: []        # fill in from what the plugin actually registers
  provides_middleware: []
  requires_env: []
```

### Pull request description template

> Adds `quantum-relay`, native phone and watch clients for Hermes through the Gadget platform.
>
> **What it does:** (two sentences from the description).
> **Hermes surfaces used:** (list the hooks and `register_*` calls exactly).
> **Disclosures (rule 13):** network listener and port; files written (plugin-data only);
> optional Wake-on-LAN and start commands; optional reply text to the phone for client voice;
> ffmpeg and libopus from the host; no telemetry; no self-update.
> **Validation:** `hermes plugins validate` output at the pinned sha, with every warning
> explained.
> **Screenshots:** phone and watch, synthetic profiles only.

## Order of work

1. Post the Track A issue and the Track B introductory issue. This step is yours.
2. Send Track A seam 1 as a pull request. Meanwhile, rebase onto upstream gadget `main` and
   run the Core-tier compatibility test from the [launch checklist](README.md).
3. Send Track B client speech as a pull request.
4. Decide on Track C from the replies. Submit the catalog entry only after the hooks it needs
   are in a released Hermes version.

Until then, beta testers install the server side from a git URL
(`hermes plugins install <repo-url>`). Hermes shows an unreviewed-code warning; the install
guide explains it.
