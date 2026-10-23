# Native canonical-voice codec and pure state (V4)

Android-free, in `protocol.relay.voice` plus the aggregate snapshot in `protocol.relay.state`. Private source stage:
nothing here sends, records, plays, persists or activates anything. The Relay wire version stays 1; the AGGREGATE
SNAPSHOT version is a separate number (now 2).

## What is SOURCE and what is POLICY
`VoiceLimits` names its origin for every number. SOURCE: `relay_voice_wire.py`, `relay_voice_stream.py`,
`relay_voice_capture.py`, `opus.py` (rates, frame and byte caps, 15,000 AUDIO frames, 9,600,000 PCM bytes, 450-code-point
chunks, response bounds 8,192 / 32,768). POLICY (local, tunable by review, always a typed refusal, never a truncation):
16 capture records per enrollment and 64 in total (design §7.4), a 120 s intent window (design §7.4), 24 h terminal
retention (equals the server's own), and the memory-only coverage bounds (32 messages, 64 played intervals, 8 attempts,
1,024 boundaries per message; a 262,144-byte source walks in a few hundred ranges).

## Decisions where the source or the design left a choice
* A frame's AUDIO body is bounded at the larger payload (1,280); PCM and Opus rules narrow it in the stream machines.
* Downlink Opus packets must be single 20 ms packets (the uplink validator's rule), stricter than the producer's
  length-only `Clip`; the real encoder's captured packets pass. Cost: a non-20 ms packet closes the stream.
* Opus header then END(0) is a violation (the producer sends no header for a silent clip); ABORT must carry the next
  sequence; frames after END/ABORT or a violation, and frames of other streams, are ignored.
* `CaptureResult` / `SpeechResult` enforce the producer's cross-field rules (reason only on failed/aborted/failed/
  unavailable; transcript only when transcribed; `in_flight` only while transcribing/discard-pending or preparing/
  stopped; pre-seal answers name no row). `total_cp` is the processed prefix (ruling R3); the coverage ledger ends at
  `speakable_end_cp` and never reads `total_cp` as an unread tail.
* The ledger does not own correlation ids or transport. It builds typed calls, applies decoded results by identity, and
  emits persistence obligations (`PersistBeforeStart`, `PersistBeforeFinish`, `PersistAggregate`, `MayDiscardServerCopy`).
* Scope decisions (`STOPPED`, `NEEDS_REVIEW`) stay the Task2 ledger's; voice only reads them and freezes. A voice error
  cannot mark a scope stopped; it returns the Relay action and withdraws the transient intents.
* A restore never rewrites a saved state: `RECORDING` stays `RECORDING` (no audio, no intent) until `reconcileRecordings`;
  `FINISHING` may be answered by a receipt read; an `ADMITTING` capture without its turn is reported
  (`CAPTURE_LINK_WITHOUT_TURN`), never repaired or prepared again.

## Atomic prepare + link
`VoiceAdmission.admit` returns the next `TurnLedger`, the next `VoiceCaptureLedger`, the outcome and the obligations in
ONE value. Any refusal returns both halves as the very input instances. A linked capture never prepares again (repeat,
settlement or restore). A transcript whose intent is gone, whose binding changed or that arrives after the window is a
draft (`DRAFT_OFFERED`, memory-only text) and never submits.

## Aggregate snapshot version 2
`RelayStateSnapshot` v2 adds `captures` (section version 1). A version 1 snapshot (no section) restores with an empty
capture ledger and every turn unchanged. `SnapshotBuild.TooMany` is checked by one shared definition before any capture
transition hands out a persist-before-send obligation.
