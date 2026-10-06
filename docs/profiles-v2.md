# Profiles: v1 and gateway v2 proposal

## Shipping v1 setup

Each profile uses its stock Gadget adapter URL/port, profile home, store and voice settings.
The watch holds at most eight encrypted endpoint records with separate random device keys.
Switching stops capture/playback/client speech and the prior transport; prompts, reply and
draft are cleared and the new profile authenticates independently. Its pet cache is scoped
by URL/hash. Shared or per-profile phone voice remains tied to that exact canonical URL.
Removing watch setup retains that URL's identity until explicit Reset, avoiding an
unannounced key rotation when re-adding. Revoke enrollment on the host separately.

On the watch, swipe horizontally from a conversation without an approval/recording to open
Profiles, or use its button. Scroll/rotary through names, select a profile, or Add profile.
Settings edits the active profile. Changing a URL adds a distinct profile; remove the old
record explicitly. Unsaved drafts are discarded on switching, and no input is replayed.

The phone's Hermes profiles page saves encrypted URLs, names, access tokens and transport
policy. Save and send publishes setup to the same-package/same-signature paired watch.
Open Import phone setup on the watch and review one item before Save & connect. Cleartext
consent is always renewed on the watch. Sync does not remotely switch or delete watch
profiles. Phone deletion removes its setup/voice key but leaves watch/server enrollment.
Data Layer setup is intentionally visible to the paired phone/watch; device identity and
ElevenLabs keys never enter setup records. Actual paired-device sync is still a hardware gate.

## Gateway v2 — proposal only

**DECISION:** implementation needs maintainer approval and upstream maintainer feedback.
No outreach, gateway implementation, or real multiplexed verification has occurred.
Independent stock endpoints do not establish multiplexed hosting.

The pinned adapter is bound to one profile home, plugin lifecycle and DeviceStore, with an
independent configured port. A root-home plugin cannot list and route all profile agents
just by adding JSON messages. A host-wide gateway needs an explicit profile registry,
load/start/stop ownership, restricted per-device grants and profile-scoped delivery context.
It must avoid leaking a profile name, pet, voice, history or action to another grant.

Proposed optional envelopes, only after paired authentication:

- `profiles.list {request_id}` → `profiles.result {request_id, profiles:[{id,label}]}` for
  authorized profiles only; bounded list, opaque stable IDs, no filesystem paths/credentials.
- `profile.select {request_id, profile_id}` → `profile.selected {request_id, profile_id,
  selection_epoch}` or a bounded generic error. Explicit consent and authorization before
  selection; unsupported peers stay on their default profile.
- Every routed turn, prompt, media/asset stream and action must carry the selection epoch.
  Switching cancels prior capture/playback and must settle/withdraw prompts before swapping.
  No old-epoch input, queued action or final reply can land in the new profile.

Open upstream questions: root versus profile enrollment, device revocation/grant UI,
identity migration from separate v1 stores, compatible default routing for old gadgets,
profile lifecycle/crash isolation, replay/ordering and reconnect selection, voice/pet refresh,
headless discovery without starting all agents, and whether this belongs in Hermes core or
the SDK plugin. Avoid identity reuse across independent hosts. Unknown optional messages
must remain ignorable and extension-off behavior must retain stock protocol v1.

Acceptance after approval: run a real gateway with multiple profile homes, verify isolated
pairing/grants, concurrent users, pet/voice/history, switching during recording/approval,
restart/revocation, malformed/unauthorized IDs and stale media/actions. Unit mocks and
multiple independent stock devservers cover only v1.
