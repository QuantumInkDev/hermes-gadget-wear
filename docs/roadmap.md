# Quantum Relay feature roadmap

Updated 2026-10-18. The owner requested implementation of original feature backlog
items 1–5 and all ten new ideas. All fifteen remain in scope until their individual
acceptance contracts are met. The one-port/multiplexed Gadget gateway is excluded.

Design: [fifteen-feature expansion](superpowers/specs/2026-10-18-relay-expansion-design.md).
The owner approved the complete eleven-question batch, with one explicit amendment:
build **Akuma only** as the personal pet proof of concept and a reusable pipeline for
future custom pets. Staged live rollout/delegation, bounded provider testing and preserving
device tests are authorized; wireless access to both devices is now confirmed.

Later artwork scope amendment: finish Akuma first, then create full Kitana, SF6 C. Viper
and Cthulhu pets from scratch, including standard and watch companion states. Kitana has
a regal walk and fan-cooling waiting loop; Cthulhu matches the existing profile portrait.
Personal references and output remain private.

## Feature ledger

| ID | Feature | Starting point | Current state |
| --- | --- | --- | --- |
| O1 | Live per-profile Hermes pets | Isolated SDK delivery patch, watch renderer/cache and synthetic native coverage | Reviewed Portrait/Pet selector installed; configured portrait/default/footer physically observed; real pet delivery and power acceptance open |
| O2 | Akuma expressions/lip sync and reusable custom-pet pipeline | Reviewed companion tooling, fallback/state mapping and synthetic sheets | Fixed-body renderer/fix passed scoped review; export/reapply pending; Akuma art still rejected; Kitana/Viper/Cthulhu queued after accepted pipeline |
| O3 | End-to-end optional Opus | Reviewed phone audio integration, SDK/watch codecs and stock PCM fallback | Synthetic production codecs pass on both physical devices; live negotiated playback, speech comparison and power measurements open |
| O4 | Optional phone ElevenLabs BYOK | Reviewed phone audio path and encrypted shared/per-profile vaults | Two bounded provider format probes pass; profile-aware settings/fix reviewed and integrated; minimum-runtime UI investigation pending; live phone/watch playback and usage acceptance open |
| O5 | Reconnect outbox | Reviewed durable storage/grants and actual-core hook v2; fresh public-patch reapplication passes | Windows fix2 source reviewed; both hosts pass9/10 native methods, restricted codec/probe correction pending; native drain/ACK, task events and live/physical acceptance open |
| N1 | Durable history and phone/watch conversation continuation | In-memory per-profile drafts/replies; independent device conversations | New session/history integration and all acceptance open |
| N2 | Cross-box task inbox and notifications | Current turn/tool status only | New authoritative task integration and all acceptance open |
| N3 | Share text, links and supported attachments to an agent | Native picture picker exists for profile art, not conversation sharing | New implementation and all acceptance open |
| N4 | Editable prompt actions and Android shortcuts | Exact-profile widgets/tiles already launch conversations | Implemented/reviewed/installed; physical entry/unbound guard/editor observed; all 13 API29 methods pass; profile-bound physical preview/shortcuts/live remain open |
| N5 | Walking mode | Existing portrait/inline hold-to-talk, waveform and audio playback | New mode/routing/concise-speech implementation and all acceptance open |
| N6 | Structured reply cards and actions | Reviewed bounded parser integrated; 137 protocol tests including Python fixtures pass | Native cards/actions in isolated Task2 implementation; physical acceptance remains open |
| N7 | Previewed agent handoff | Independent profile routing, no cross-agent context operation | New implementation and all acceptance open |
| N8 | Connection/request-stage diagnostics and redacted reports | Existing connection/status and error UI | Typed protocol snapshots/export reviewed; native presentation/export and physical acceptance open |
| N9 | Authenticated remote wake | No remote wake integration established | New implementation; sender and hardware capabilities need verification |
| N10 | QR onboarding and safe configuration migration | Current manual review/import and encrypted setup/art stores | New reviewed QR/migration implementation and all acceptance open |

## Planned workstream order

1. Audit actual Hermes SDK/core/plugin interfaces and collect the decision batch.
2. Finalize the umbrella design and focused implementation plans with acceptance evidence.
3. Build authenticated optional server contracts, durable events/outbox and session/task
   adapters. Keep stock peers usable with features off.
4. Integrate real pets, registered expressions, negotiated Opus and opt-in client speech.
5. Build history/continuity/task UI plus sharing, prompt actions, walking mode, rich cards
   and explicit agent handoff against the real scoped contracts.
6. Add request-stage diagnostics, supported remote wake and reviewed setup/migration.
7. Complete independent review, staged authorized live rollout, native/device tests,
   owner-observed speech/earbuds/art checks and measured audio/battery acceptance.

Independent work may overlap after interfaces and file ownership are defined and the
owner's decision permits delegation. The order does not remove any feature from scope.

## Completion rules

- Record designed, implemented, software verified, live integrated and physically accepted
  separately, with source/build identifiers and the relevant receipt links.
- Passing synthetic tests does not establish real sessions, task progress, provider voice,
  pet lip sync, wake hardware or battery life.
- Preserve independent phone/watch identities, signatures, custom art/GIF assignments,
  themes and existing working profile behavior. Personal receipts stay in ignored .local/.
- A hardware prerequisite or missing external API is an explicit dependency requiring a
  supported implementation path; it does not turn an unfinished feature into a completed one.
- The owner's report of several stable days covers the existing app only. It does not
  accept these new features or silently close earlier targeted release gates.
- Keep publication, upload-key changes, merging and the excluded gateway v2 separate from
  the active feature goal. The release checklist remains a different checklist.
