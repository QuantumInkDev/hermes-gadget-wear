# SOUL, voice casting and voice design

Read the selected profile's real SOUL.md and relevant TTS configuration; keep the originals private. Draft a coherent role/personality/communication voice, retaining existing tool/security/operational instructions. Produce SOUL.proposed.md and a unified diff. Never give a profile capabilities because its character bio mentions them. A new SOUL can cover role, temperament, verbal style, values, boundaries, uncertainty and examples without overwriting global/user rules.

Derive a **casting brief** from the approved personality: language/accent, pitch, resonance, texture, pace, emotional range, humor/formality and avoidances. Ask about missing material preferences. A fictional character's art isn't a request to impersonate an actor. Use original voice design unless a specific authorized voice was supplied.

## Existing voice

List account voices with `voices.py search --query ...`; optionally `--library` for shared voices. Inspect availability, category, labels, preview and account/plan compatibility; never choose by name alone. Recommend a small contrasting shortlist with why each fits the SOUL. Audition existing `preview_url` audio without passing an API key to its CDN. If using a shared voice, add it to the account only after approval (`add-library --execute --approval ...`). Record the returned usable voice_id, not a display name or preview ID. No automatic synthesis merely to browse.

## Custom voice

Write an original detailed voice_description and a natural in-character sample script100–1000characters. Preview the prompt/script and proposed budget first. `voices.py design` plans by default; with explicit paid approval it calls `POST /v1/text-to-voice/design` once and saves returned audio and generated_voice_ids. Show ALL returned candidate auditions; stop for selection/feedback. After approval of one sample, `save` calls `POST /v1/text-to-voice` with that exact generated_voice_id, voice_name and voice_description. Preview IDs are not installed voice IDs. Do not save every candidate or delete voices to make room without permission.

Use existing private credentials through the environment/secret store (`ELEVENLABS_API_KEY` or `ELEVENLABS_API`) without printing them. Per-run calls/characters and uncertain outcomes go in an append-only spend ledger **before** each paid send. No automatic retry after timeout/error, no paid fallback, no subscription/key purchase. A voice account mutation and each changed prompt/sample require their own covered approval. If the account/API is unavailable, leave voice pending; never fabricate a voice ID or claim an audition happened.

Configure the exact selected Hermes profile's existing `tts.provider` and `tts.elevenlabs.voice_id` schema after reading its current loader. Preserve provider/model/settings unless the approved plan changes them. Server voice remains Relay's default. Phone BYOK overrides are a separate opt-in; don't overwrite a global key or unrelated profile's voice. Verify a short approved synthesis and actual desktop/phone/watch playback only within the run's remaining budget, then test Stop/profile-switch cancellation. Do not infer successful playback from a saved audio file.

Official API references checked2026-10-23; recheck before future live use:
- [Account voice search](https://elevenlabs.io/docs/api-reference/voices/search)
- [Voice Library search](https://elevenlabs.io/docs/api-reference/voices/voice-library/get-shared)
- [Voice Design previews](https://elevenlabs.io/docs/api-reference/text-to-voice/design)
- [Save selected preview](https://elevenlabs.io/docs/api-reference/text-to-voice/create)
- [Add a shared voice](https://elevenlabs.io/docs/api-reference/voices/voice-library/share)

## Helper request shapes

Use `voices.py --help`; all non-search operations require `--request <json>` and a NEW `--out <dir>`. Without `--execute` they save a request plan and make no API call. Execute into a fresh sibling output directory; preserve the approved plan and verify the request payload still matches it.
- Design JSON: `voice_description`, `text` (explicit100–1000characters), optionally a currently supported `model_id`. Paid send additionally requires `--execute --approval <actual user approval> --spend-ledger <run/voice/spend.jsonl> --max-calls <approved total> --max-characters <approved total>`.
- Save JSON: `voice_name`, `voice_description`, `generated_voice_id`; execute only with `--preview-manifest <design-output/result.json>` and the selected sample's explicit approval.
- Shared voice JSON: `public_user_id`, `voice_id`, `new_name`; execute only after the user approves the account addition.
- TTS audition JSON: `voice_id`, `text`, optional current supported model/voice settings; same paid approval/budget ledger requirements.
- Write `voice/selection.json` with the persistent `voice_id`, mode (`existing`, `designed` or explicitly `preserve`), settings and `preview_file` relative to the run. Approve the sample file too; only deliberate preservation can omit a new audition.
