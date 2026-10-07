# Physical-device results

Recorded 2026-10-06–07 on a Samsung Galaxy Watch Ultra and Galaxy S25 Ultra, both
Android 16 / API 36. Device addresses, names, keys and raw captures remain in ignored
maintainer storage. These results supplement the [acceptance runbook](device-testing.md).

## Verified

| Check | Observed result |
| --- | --- |
| Compatible app update | Local watch/phone signing certificates matched. Watch updated from the earlier build to version code 10001; phone installed at 10000. The original watch identity ciphertext was unchanged. |
| Existing direct profile | A fresh typed request returned the requested marker from real Hermes after the update. |
| Phone setup sync | Phone saved a separate TLS profile with forced Phone transport. The watch received it through Data Layer, reviewed its URL/transport and saved it. |
| Independent TLS enrollment | Only the pairing code shown by this watch was approved on the intended host. Both original LAN and new TLS identity ciphertexts remained intact. |
| Real TLS phone relay | A fresh typed marker returned through the forced Phone profile. The watch displayed Phone relay · watch TLS and server-profile voice. |
| Cellular/VPN relay | With phone Wi-Fi off, cellular and VPN transports present, and no USB tether function, another explicit watch request returned its marker through forced Phone TLS. |
| Background startup | After closing the previous connection, the phone had no relay foreground service and its keyguard was showing. A fresh watch request started the background relay and returned another marker. |
| Watch foreground lifetime | Foreground service was observed during the relay turn and released after the reply. |
| Companion chooser | The original unrestricted single-device request offered an unrelated device. The corrected request shows a device list. Final system state retained one association for the bonded watch; extra own-app associations were removed and other apps' associations were unchanged. |
| Automatic transport restored | Phone and watch TLS profiles are saved as Automatic. Reopening watch server settings verified the saved preference. Both identity ciphertexts were unchanged, and the original watch screen timeout was verified restored. |

The returned text sometimes included a server-generated prelude. The relay check confirms
the fresh marker in the incoming reply rather than requiring the entire reply to equal it.
The maintainer's earlier hold-to-talk/ElevenLabs report remains the speech demonstration;
the automated typed checks do not establish microphone quality or audible voice quality.

## Hardware instrumentation and decoder correction

An explicit five-class allowlist initially ran 12 tests on the Ultra. Ten passed: six isolated
Keystore/catalog/surface-vault checks, two outline/Tile/complication payload checks,
native provider Ogg Opus decoding, and loopback TLS certificate validation. The test
vaults use unique directories and aliases. Both personal identity ciphertexts were
unchanged afterward. No app-data or personal-key deletion was performed.

Two three-second Opus burst tests failed when `dequeueInputBuffer` returned no available
buffer after a single 10 ms wait. The decoder now drains pending output while acquiring
an input buffer within a deadline, including the end-of-stream input. Packet order,
timestamps and stock PCM fallback are preserved. Strict checks and four owned API 37
emulator codec/TLS regressions pass with the change.

After installing the matching-certificate decoder update, **all 12 Ultra tests passed**,
including both formerly failing burst cases. Both personal identity ciphertexts remained
unchanged. The three-second physical tone produced 8,905 Opus payload bytes in 151 packets
from 96,000 PCM payload bytes, or 23.747 kbit/s before framing. Native decoding produced
289,714 bytes at 48 kHz; combined encode/decode elapsed time was 1,671 ms. These are
synthetic codec measurements, not speech quality, network throughput or conversation
latency. See [audio measurements](audio-measurements.md). The phone chooser correction
is also installed on the real phone.

Wireless ADB interrupted installation of the instrumentation APK and one subsequent
test invocation. The already installed test APK matched the current build by SHA-256;
after reconnection, the explicit 12-test run completed successfully. Interrupted runs
were not counted as passing tests.

## Interruption observations

The phone's Disable and Enable controls were exercised. The synthetic unsent draft was
observed during the interruption and again after relay re-enable. The recovery driver
preserved it instead of submitting it when the input screen changed. This verifies the
observed draft retention, but does not close the bounded failure/reconnect gate: the
terminal connection control was not exposed within the driver's observation window,
and no explicit post-interruption reply was completed. Do not treat that UI assertion
as evidence of a server authentication or transport failure.

## Open checks and next steps

A controlled cellular retry completed with a fresh request and reply under the conditions
listed above. Earlier attempts stopped during watch UI navigation before sending input;
they were not transport failures. Bluetooth-specific throughput and interruption remain
open; the Phone label identifies the app path, not the underlying Data Layer radio.

Both screen timeouts and the phone Wi-Fi setting are restored. The relay is enabled,
and both phone/watch TLS profiles are saved with Automatic transport. The complete bounded
Disconnect/reconnect check remains open. After the idle turn, the watch app was restarted to
clear the transient test screen and unsent synthetic draft. Both identity ciphertexts
were unchanged after restart; no app data was cleared.

Physical Tile/complication placement, button mapping, comparable PCM-versus-Opus speech,
real-profile pets, phone BYOK/provider billing, timed unplugged battery measurements,
reviewed optional server extensions, release signing and Play installation remain open.
No optional patch was installed into live Hermes.
