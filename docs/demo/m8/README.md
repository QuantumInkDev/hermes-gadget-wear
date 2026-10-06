# M8 synthetic watch surfaces

Actual 480×480 round API 37 owned Wear emulator, using the unmodified SDK development
server on synthetic localhost. No personal endpoint, credentials, pet or recording appears.
The robot is original procedural art. These captures are app behavior, not design mockups.

| Surface | Capture | What passed |
| --- | --- | --- |
| Ready conversation | [Normal font](conversation.png) | Speaking and typing controls visible together; Tile tap opened this screen without starting capture |
| Larger text | [Font scale 1.3](conversation-large-font.png) | Labels and controls remain readable with wrapping; original emulator setting restored |
| Tile | [Offline cached reply](tile.png) | Rendered after app force-stop, restored encrypted excerpt, no conversation service started until tap |
| Ambient | [Static outline](ambient.png) | Actual system `KEYCODE_SLEEP` transition, ambient semantics and sparse outline; `KEYCODE_WAKEUP` returned to Ready |

Foreground lifecycle instrumentation used an actual stock SDK round trip: service active
while the turn ran, then stopped after reply/audio drain while the visible app remained
paired. Starting AudioRecord and entering ambient canceled capture, released the service,
and retained reply and unsent draft. No host microphone/speaker or physical audio quality
is inferred. The stock offline-draft editor/reconnect regression passed again after layout
changes. Actual service profile switching during recording also passed.

Six Keystore/catalog/surface tests and two surface/outline tests passed, including ciphertext,
profile isolation, bounded excerpt, removal/reset, Tile launch target and generic complication
data/tap action. The complication was validated as provider data; watch-face selection and
physical Ultra rendering remain open. Neither emulator screenshots nor lifecycle tests prove
Ultra battery life, Data Layer reliability, off-network relay or Play acceptance.

See [progress](../../progress.md), [release checklist](../../release.md), and
[original store artwork](../../store-assets/README.md).
