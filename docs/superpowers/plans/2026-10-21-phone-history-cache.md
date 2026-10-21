# N1 phone history cache — isolated source candidate

Base: c91f012910db5a5b5ba103a1a6c06dea43282c44. The approved expansion requires a
configurable ninety-day encrypted phone text cache. ConversationVault remains an
enrollment vault. This task supplies storage, not a new transport or an activated
history screen. The server/native consumer contract and its independent reviews
remain prerequisites for wiring it into PhoneConversation.

Ruling: execute this independent new feature serially while the delegated provider
is quota-limited. Do not fix existing review findings in the controller, launch
replacement agents, or substitute self-review for independent acceptance. Cost if
wrong: rework this private candidate before integration. No device or live actions.

## Storage contract

- Separate private directory and Android Keystore AES-256-GCM alias. No enrollment,
  provider keys, PCM, tool/system/reasoning messages, attachment bytes or transport
  bindings in its explicit text-only schema. No automatic network or speech.
- Exact identity is stable catalog key, canonical TLS endpoint, opaque server UUID,
  profile UUID and enrollment public-key fingerprint. Conversation/message IDs are
  opaque canonical identities, never display names. Identity changes cannot read the
  previous scope. Callers must verify these identities before access.
- Cache only complete validated user/assistant display messages. Segment assembly,
  binding/generation authorization, existing-Hermes opt-in and canonical deletions
  belong to the later consumer. Do not manufacture identities for stock peers.
- Preserve stable message IDs: equal retries do not duplicate or renew retention;
  conflicting content fails without replacing the prior record. Metadata has an
  explicit server/public revision; stale/conflicting metadata is refused.
- A global local revision fences stale updates, including writes after device-only
  purge. Retention is configurable 0–365 days, default 90; zero clears/disables the
  cache. Purge never calls a server. Expiry uses local receipt time, not server time.
  Reads prune expired content atomically. Wall-clock jumps can expire local cache
  early or delay expiry; purged bytes never reappear from this cache. This is not
  canonical retention or a claim of tamper-resistant elapsed time.
- Bound plaintext to 8 MiB, 256 conversations, 4,096 messages total, 1,024 per
  conversation, 256 KiB UTF-8 per message; refuse over-capacity writes without
  eviction/truncation. Titles ≤120 scalars/512 bytes. Search is exact-scope bounded
  literal text, at most 50 results. Lists are defensively copied; diagnostics redact.
- Authenticate the entire fixed-field/versioned binary document. Reject trailing
  fields, invalid UTF-8/counts/lengths, corrupt/truncated/cross-key ciphertext,
  missing initialized record/key and unsupported versions. Never silently seed over
  damaged storage. Synced staging + atomic replacement; orphan staging is preserved
  on failed initialization. Tests use synthetic keys and actual temporary files.

## Execution

1. Write storage behavior tests first, retain a runnable missing-feature RED with a
   minimal API scaffold; then implement merge, scope/revision, retention, bounds,
   real AES-GCM persistence, corruption and atomic failure behavior.
2. Add the Android Keystore/off-main binding and owned synthetic instrumentation
   tests as source. Compile them offline; do not launch AVDs/devices. Android Keystore,
   API29/current OS and crash/power-loss acceptance remain unexecuted until allowed.
3. Run relevant JVM tests, the repository offline check and native compilation;
   preserve all errors/skips/warnings. Commit only this private candidate, produce
   the full-range patch/report and request fresh independent review when available.
   Root receives status/evidence only. Full N1, export/UI/transport/continuation and
   all fifteen-feature/recovery gates remain open.
