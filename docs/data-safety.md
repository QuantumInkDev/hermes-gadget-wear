# Play Data Safety draft

This is a publisher review worksheet, not a submitted Play declaration. The app has no
project backend or analytics. Off-device transmission to a user-selected server or provider
still needs assessment under Play's definitions; do not automatically mark “no collection.”
The declaration must cover both form factors, optional voice and bundled SDK behavior.

| Data / path | Current behavior | Review before submission |
| --- | --- | --- |
| Voice/audio | Optional watch mic → configured Hermes server for STT/agent processing | Audio files; app functionality; optional; server/provider retention varies |
| Messages/reply text | Typed input → Hermes; opted-in final reply → phone/ElevenLabs | Other user-generated content; optional user action; no project copy; provider retention varies |
| Random device identity | Watch enrollment key/derived device ID → configured server | Device or other IDs for authentication; app functionality; never hardware/advertising ID |
| Battery/action state | Battery percentage and supported action results → configured server | Review applicable data category; app functionality; no health/location sensors |
| Voice key/quota | Phone key → fixed ElevenLabs HTTPS origin; read-only subscription check | Authentication/provider data; explicit opt-in; encrypted vault; no Hermes upload |
| Setup | Encrypted local settings; URL/name/optional token → paired watch Data Layer | User-selected setup; SDK routing/disclosure; no identity or ElevenLabs keys in sync |
| Pet/reply cache | App-private pet assets and encrypted 120-character reply excerpt | Local storage; bounded; not uploaded by project |
| Complication | Generic Ready/Listen/Working/Talking/Offline state → selected watch face | No conversation, endpoint, label or credential content supplied |
| Diagnostics | No automatic analytics/crash-reporting collection | Do not enable telemetry without new consent and declaration review |

Encryption in transit cannot be answered universally Yes while optional direct `ws` remains
supported. Keystore encryption at rest is separate. Never assume ephemeral processing or
a sharing exemption for third-party/user-server retention. The developer must review the
actual server/provider/Google Play Services behavior and final policy. Account creation
with the project is absent; local delete/reset and separate server/provider deletion are
explained in the [privacy draft](privacy.md).

Primary guidance checked 2026-10-06: [Play Data Safety](https://support.google.com/googleplay/android-developer/answer/10787469)
and [User Data](https://support.google.com/googleplay/android-developer/answer/10144311).
