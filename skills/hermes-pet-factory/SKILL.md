---
name: hermes-pet-factory
description: Create or update complete pixel-art pets for Hermes profiles and Quantum Relay, including portraits, desktop and watch animations, SOUL changes, ElevenLabs voice selection or design, previews, and approved installation. Use for a new profile character or a pet refresh, not ChatGPT Work Pets.
metadata:
  hermes:
    tags: [pets, pixel-art, imagegen, elevenlabs, soul, wear-os, quantum-relay]
    category: creative
---

# Pet Factory

Turn a character/profile brief into an approved, installed Hermes + Quantum Relay pet pack. Support new pets and precise updates. Keep the existing pet usable throughout. Default to serial image jobs; never launch a swarm of agents or silently change providers.

## Start: one complete intake

Read [intake.md](references/intake.md). Discover supplied information and relevant existing profile/art/SOUL/config read-only. Ask **all remaining material questions together before generation**: identity/style, exact targets, state choreography/signature, SOUL scope, voice, image backend, budget and installation scope. Don't re-ask answered questions. Accept images already attached or local reference paths. Never ask for keys in chat.

Create a private run outside source-control with `scripts/factory.py init --run <new-directory> --name <name> --slug <slug>`. Complete `brief.json`, write the proposed SOUL separately, and preserve originals and their hashes. `factory.py` needs Pillow; use this skill's `.venv/bin/python` if present, otherwise an environment with `requirements.txt`. No live profile or device edits during preparation.

## Preview and approval checkpoints

At each checkpoint show the actual artifacts, then request **Approve all shown items**, or feedback naming the item/state/frame. A blanket approval applies to the displayed packet and exact revision, not future artifacts. Empty replies, timeouts and silence are not approval. Record the user's actual message and artifact hashes with `factory.py approve`; changed bytes invalidate that approval.

1. **Brief, SOUL and voice direction:** show the character/state plan, proposed SOUL diff, voice description/shortlist rationale and estimated image/voice usage. Obtain the missing generation/budget approvals.
2. **Look:** generate and show the canonical full-body anchor, square card and circular card on light/dark backgrounds, including actual watch size. Approve the look before animation jobs.
3. **Motion:** generate every base and companion state from the approved anchor. Show a contact sheet **and moving previews of every state**, normal/half speed, frame stepping, light/dark, desktop and round-watch views. Show signature separately. Ask for batch approval or state/frame feedback. Inspect the full loop, especially last→first.
4. **Voice and SOUL:** audition the actual shortlisted previews or generated voice samples alongside the final SOUL diff. Approve the selected sample/voice and SOUL independently. Saving a newly designed voice or adding a library voice is a separate approved account mutation.
5. **Install:** show the final assets/hashes, exact box/profile mappings, file/config diffs, watch display-mode choice, backups, reloads and rollback. Obtain explicit install approval for those exact targets before applying.

Do not demand repeated approvals for the same unchanged item. Work that doesn't depend on a pending approval can continue, but never pass the dependent gate. Stop after presenting each packet and resume from the saved run when the user answers.

## Art and animation

Read [visual-contract.md](references/visual-contract.md) before generating. Use OpenAI image generation for new or revised artwork. Prefer the environment's native `image_gen`/imagegen tool; load local reference images before editing. In Hermes, inspect the actual image provider: an unconfigured generic image tool may use FAL. Prefer its installed `openai-codex` image backend with existing authorized Codex/ChatGPT OAuth; this needs no OpenAI API key. Otherwise include the choice of **approved OpenAI Images API access or an explicit T3 art handoff** in the initial intake. An API route needs an API key; do not confuse it with subscription/OAuth access. Any provider-setting change must be covered up front and limited to the selected profile/run, preserving the prior setting. Do not silently fall back to another vendor or a paid API route. Re-check the selected tool/model's current constraints.

Create the anchor first; attach it and identity references to every later job. Generate grounded state-specific poses, not an entire atlas in one image call. Keep raw outputs/prompts and approved revisions. Use extraction/registration appropriate to the actual generated layout; verify pose count and complete silhouettes before fitting. Use one shared character scale/registration, preserving jump height. `factory.py assemble` consumes **already registered 192×208 RGBA cells**; it does not repair bad source art or independently stretch poses.

`factory.py jobs --run <run>` writes the required image job prompts. `assemble` packs cells and portraits into a new bundle; `validate` checks the exact bundle; `preview` renders self-contained HTML and per-state GIFs. Use actual Hermes/Relay validators and the installed `tools/pet-companion.py` stager as well when available, per [deployment.md](references/deployment.md). Technical validation is not aesthetic approval or device acceptance.

Revise the smallest rejected row/region. Preserve approved rows and original pixels on update. After two failures of the same root cause, change the art/extraction strategy and explain it; don't burn an unlimited regeneration loop. No code-drawn substitute character, whole-body warp, drifting markings, fake mouth pasted onto nonhuman anatomy, or label/X/debug mark in the art.

## SOUL and ElevenLabs

Read [soul-and-voice.md](references/soul-and-voice.md). SOUL defines character/personality, not new privileges. Preserve existing profile policies, skills/tool access, memory and unrelated configuration. Show a diff; never replace an existing SOUL with a generic character bio.

Start with the account's existing voices and, if requested, the Voice Library. Match delivery to the approved SOUL and audition a short consistent script. For a custom voice use ElevenLabs Voice Design previews, let the user choose, then save only that approved preview. Use `scripts/voices.py` for bounded, no-retry API operations; it defaults to a request plan for mutations. Existing preview downloads need no synthesis. Paid design/TTS uses a per-run approved character/call cap and an append-only spend ledger. Never renew an old test allowance or automatically retry an uncertain paid call. No cloning/impersonation is implied by a fictional character reference.

## Install and verify

Read [deployment.md](references/deployment.md). Default is a staged bundle and a dry-run plan. Only apply after exact art/motion/SOUL/voice/installation approvals. Install into the selected profile's real Hermes home, preserving regular desktop atlas and separate watch companion/Gadget base. Set that profile's `display.pet.slug` and approved ElevenLabs voice configuration; verify with the actual profile resolver. Do not set a global voice/pet for every profile or copy device keys.

**Which card goes where:** the **square** card is the profile picture everywhere except the watch face: the profile's `assets/avatar.png`, the desktop Bots tab card and the phone apps. The **round** card is only for the watch (the Relay catalog portrait and watch-face art). Never write the round card to `assets/avatar.png`, and keep the replaced file as a dated backup. The desktop Bots tab keeps its own copy of each picture in local storage, so after changing an avatar, update that card too (see deployment.md).

Install/import the square/circular card and pack into Quantum Relay's exact endpoint/profile mapping using supported catalog/picker/pet delivery, and deliberately select Portrait/Pet as approved. Report desktop, catalog, phone and watch outcomes separately. A local bundle or ADB success is not delivery proof. If a box/device is offline, leave a resumable target marked pending with its backup/install plan; do not claim success.

## Completion

Deliver: raw prompts/references; square and round masters/exports; registered frames; standard atlas + watch companion (+ optional Gadget base); `pet.json`; all-state animated previews; validation; approved SOUL/diff; voice ID/settings/auditions; exact target receipts and rollback. Verify profile switching resolves each intended pet and voice and watch frames match the approved bytes. Preserve remaining physical/voice/battery checks as pending. The final result must distinguish generated, approved, staged, installed and observed.
