# Relay v1 captured wire vectors

Synthetic, Relay-only frames recorded from the **accepted** Hermes Gadget SDK (`docs/relay-native-clients.md`
section 1-4) while its own tests ran on the real registered Gadget adapter, `SessionAuthority` and SQLite. Nothing in
these files is hand-written: a passive recorder wraps `GadgetAdapter.on_relay_request` and
`DeviceSession.send_json`, and stores each request with the exact `relay.response` that left the socket
(`wire_bytes` is the byte length of that frame; the Kotlin tests re-derive it).

* `relay-*.json` - one file per advertised method, one verbatim case per distinct outcome and body shape
  (`ok`, or `error:<code>[+snapshot_required]`). The smallest frame of each class is kept; for paged methods the
  largest frame under the bound is kept as well.
* `hub-level.json` - transport refusals produced by `DeviceHub._on_text` (oversize and depth-9 frames answered with an
  id-less `invalid_request`; the 65,536-byte and depth-8 boundary frames accepted). Each request is stored as a
  rebuild recipe plus its SHA-256 and byte length; the tests rebuild the exact bytes.
* `malformed-envelope.json`, `relay-bogus.json` - id-less / unknown-method refusals from the real adapter.
* `manifest.json` - SDK and core commit/tree pins, the digests of the recorder, runner and gap test, the accepted
  test files whose runs were recorded, and a SHA-256 of every vector file.
* `capture-harness/*.txt` - the recorder, the runner and the capture-only gap test, verbatim (as `.txt`).

Every case carries `source.test` (a test id in the accepted SDK, or in the gap test) and `source.run`.

## Provenance and limits

* SDK `286e13482f12a71b1eb81e66c43470df09da2f05`, core `continuity-core` `7c17e6d72a3b9ec2974b3566f02dab1ed4b94eae`
  (the second, `continuity-core-clean`, was not used).
* Captured with the accepted wrapper, one worker, no retries, warnings as errors, in isolated clones with a synthetic
  home, no network and no provider. Command shape:
  `scripts/run_tests.sh -j 1 --file-retries 0 -W error -q -rA -p native_relay_recorder <test files>`.
* O5 final-text frames keep `conversation_id` / `event_id` / `message_id` as exact opaque text: the accepted `Outbox.put` stores any
  non-empty UTF-8 string (<= 128 / 256 / 36 bytes) and a compat test asserts the real adapter delivers `stock-chat` / `stock-final`.
  Those recorded frames are ordinary successes and round-trip exactly. Task-change copies keep canonical UUIDs.
* No real capture exists for `profile_changed`; the Kotlin tests classify it from the SDK source and label it constructed.
* This proves the codec/DTO layer against recorded server behaviour only. No device, emulator, live host, provider or
  native acceptance is implied.
