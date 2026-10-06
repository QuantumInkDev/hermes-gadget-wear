# Pending outbox proposal

Proposal only, not an implemented or negotiated v1 extension. The stock server resends
pending approval prompts after reconnect, but general pushed messages while disconnected
are lost. Clients preserve the last reply, do not queue microphone input, and require a
fresh user action to send a draft. Do not promise delivery before upstream accepts a design.

A proposed optional `caps.outbox` / acknowledged welcome capability would let the server
retain only authenticated device-addressed text/card notifications. Exclude microphone
input, audio/assets, actions, approval answers and automatic speech; replaying those could
repeat work or create unsafe stale interactions. Existing prompt expiry remains authoritative.

Candidate defaults for review: at most 32 entries / 64 KiB per device, 5-minute TTL, bounded
aggregate memory and an explicit oldest-entry eviction notice. Retain event IDs and original
server timestamp/expiry. Sequence within an endpoint/device enrollment generation; a profile
switch, removal or re-enrollment must not receive another device's retained content. Client
acks mean accepted into the bounded display state, not spoken or acted on. Persist only a
bounded deduplication cursor if required; never regenerate or replay outgoing messages.

Reconnect must first restore identity/session and reissue current prompts, then deliver
unexpired entries in sequence with their original IDs. A dropped acknowledgement may cause
redelivery; the client must deduplicate before changing UI. Missing capability/ack retains
stock behavior. Feature-off tests, bounded retention/eviction, reconnect duplicates, expiry,
permission/prompt ordering, wrong-device access and v1 compatibility would be required.

Storage durability, acknowledgement wire shape, retention consent, adapter hooks and quotas
need upstream maintainer feedback before a patch. No upstream outreach or runtime change
was made during this project work. See [upstream work](upstream.md).
