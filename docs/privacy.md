# Privacy policy draft

Hermes Gadget for Wear OS is a client for a Hermes server that you choose. The project
operates no conversation backend, accounts, advertising or analytics service. This draft
matches the current app; the publisher must add its identity, contact and effective date
and host the final policy at a public URL before Play submission.

Microphone access starts only after permission and an explicit recording gesture. Audio
streams to your configured server while recording; releasing completes that input. Cancel
stops capture and discards unsent audio, but cannot retract bytes already received by the server.
Typed messages, battery percentage, pairing identity and supported device-action results
also go to that server. The server may transcribe, process, retain or forward content under
its own configuration and providers. The app cannot delete server history or control those
providers. Heart rate, steps, location, contacts and advertising IDs are not requested.

Direct TLS and phone relay use certificate/hostname validation. The phone relay forwards
watch-owned TLS bytes to your server and can observe the target, timing and volume. Private
cleartext direct connections require endpoint-specific consent and expose pairing keys and
conversation content to that local network. They cannot be represented as universally
encrypted transport. Tailscale is a separately installed service subject to its own terms.

Optional phone voice sends final reply text, a chosen voice and your phone-held ElevenLabs
API key to ElevenLabs over HTTPS. Provider usage is billed to your key and follows the
provider's policies. This option is disclosed and selected per endpoint; Server is the
default. Keys are not sent to Hermes. Read-only key tests access subscription/quota data.
No paid synthesis request is automatically retried after failure.

Android Keystore protects watch identity/settings and phone setup/voice keys at rest.
Phone-assisted setup intentionally shares endpoint names, URLs and optional access tokens
with the same-package paired watch through Google Play Services Data Layer. Device identity
and ElevenLabs keys are excluded from setup sync. Google Play Services may route Data Layer
traffic via Bluetooth, Wi-Fi or its cloud transport. App backup is disabled, and phone setup
screens block screenshots. No project-operated telemetry receives this data.

The watch caches bounded pet assets and an encrypted short last-reply excerpt for its Tile.
A selected watch face receives generic connection/pet state through the complication, never
reply text or keys. Removing a watch profile clears its pet cache/settings but keeps its
identity for re-adding; Reset removes the whole watch vault and pet cache. Phone Delete controls erase the
selected voice/setup record. Uninstall removes app-private storage and vault access. Sync
removal does not automatically delete setup on the other device; remove it there separately.
Server enrollment/history and provider records must be removed through their own controls.

The publisher's public support channel should be supplied here before release. Do not post
keys, actual endpoint details, personal screenshots or recordings in public bug reports.
