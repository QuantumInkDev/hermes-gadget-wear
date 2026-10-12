# Physical-device results

## Round portraits and live profile setup — 2026-10-12

This continuation follows the maintainer's approved live setup and supplied round images.
Personal images, endpoint details, registry records, screenshots and receipts stay ignored.

| Check | Observation |
| --- | --- |
| Source entry | Three actual HTTPS sources were entered on the physical phone; the watch received a 19-page roster containing 18 discovered profiles and the retained LAN page. |
| Round image selection | All 18 supplied 256 by 256 RGBA images were chosen through the native photo picker. Actual normalized phone PNGs retain the circular alpha shape and match the staged art; all 18 hashes are present in the real watch cache. |
| Pairing | All 17 new endpoints completed normal on-watch review and exact profile-specific CLI approval. Each reached Ready on Phone relay with watch TLS. The existing root endpoint also reached Ready; no original identity was rotated. |
| Larger portraits | The requested change doubles the image from 80 to 160 dp. Bounds on the 480-pixel, 340-dpi Ultra are 340 by 340 pixels. The follow-up top-padding change from 40 to 28 dp raises it 25 pixels to bounds [70,69,410,409], with center y=239. Visual inspection shows the complete circle and clock; names and controls remain below in the scrollable page. |
| Preserving installation | Watch/phone certificates match. The watch's installed updated APK hash matches the local build, all 19 identity ciphertexts match before/after, and both original ciphertexts match their initial baseline. A complete prior APK and encrypted vault backup are private. |
| Phone cellular reachability | A selected read-only diagnostic using existing phone configuration passed authenticated default-trust HTTPS for all three catalogs on validated cellular/Tailscale, Wi-Fi off and USB debugging only. It did not pair, reset or send a conversation. The normal test APK was restored afterward. |
| Provider recovery | A previously rate-limited profile returned a fresh exact marker on the same cellular TLS path after the pause. The prior rate-limit response was a server-model failure, not successful conversation acceptance. |
| Cold background startup | With the phone keyguard showing, Wi-Fi off, validated cellular/VPN and no active phone relay foreground service, watch app startup opened a fresh relay and returned its exact marker through watch-owned TLS. |
| Per-profile requests | All 18 profiles returned fresh markers through Phone relay with watch TLS on validated cellular/VPN, Wi-Fi off and USB debugging only. The phone keyguard remained showing for each: 12, four and two per source. No automatic input replay was used. |
| Catalog outage/return | Stopping only the owned Mac catalog job produced the cached-offline badge while retaining selected page 1/19 and its portrait. After HTTP readiness and refresh, the badge disappeared. The phone stayed locked on cellular; the exact job/routes and portrait index were restored. This did not power off the box or interrupt its Gadget gateway. |
| Restoration | Phone Wi-Fi is enabled; phone and watch timeouts are restored to 300000 and 15000 ms. Normal watch settings verify the root TLS profile is Automatic. All 19 identity ciphertexts and 18 picture hashes remain intact after the final update. |

Earlier automation missed replies arriving beneath the portrait; the helper now sweeps the
body and transport footer until the fresh response is observed. One earlier cold connection
timeout remains recorded and was not reproduced by the successful resumed cold test. Debug
builds can record exception class, errno, elapsed time and foreground/network-blocked flags;
URLs, headers, keys, device IDs, exception messages and conversation text are excluded.

Whole-box power transitions, fresh speech across all three boxes, live optional pet/Opus
extensions, radio-path/throughput, battery and Play acceptance remain open. The maintainer
will manage the Mac's awake state; the proposed AC-only power helper was not installed.


## Earlier baseline — 2026-10-06–07

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

## Carousel physical pass — 2026-10-11

The maintainer authorized testing the carousel on the existing paired watch and temporarily
extending its screen timeout. The original timeout was 15 seconds; a 10-minute value was
written and verified. Power/window queries confirmed the watch remained awake and unlocked.
Both existing encrypted identity records were backed up before any update attempt. The
installed APK's v2 signing block was read in small ranges; its certificate matches the local
debug builds. Initial full rollback transfers and a preserve-data install stalled. Partial
files are not usable rollback packages. The installed hash was rechecked after reconnection
and still identified the older build, before a new preserve-data install was attempted.

Earlier routing checks found different IPv4 subnets; the maintainer clarified that both
devices used the same Wi-Fi name. After a refreshed address on the Mac's local subnet was
supplied, ping/TCP probes succeeded but the existing ADB server still reported an unreachable
host. A fresh isolated watch-only server established a working connection without restarting
phone USB debugging. The cause of the earlier stalls is not established.

The review-fix APK installed successfully with app data preserved. Its on-watch SHA-256
matches the local artifact. The phone already has its matching-signature review-fix build.

| Check | Observation |
| --- | --- |
| Enrollment | Both existing watch identity ciphertext checksums match before and after the complete pass. No new URL or pairing was introduced. |
| Manual phone card | The watch displays the phone's saved TLS card alongside its existing watch-only LAN card. This proves manual metadata delivery, not three-box discovery or portrait transfer. |
| Horizontal navigation | Actual left/right input switches between the two approved profile pages. The unavailable LAN page remains browsable. |
| Sleep/wake | A power query confirms sleep, then wake restores the selected page. Ambient artwork quality and power use are not measured. |
| Draft retention | An initially empty TLS editor receives one owned synthetic draft. After switching to the other profile and back, the exact unsent draft is restored. |
| TLS conversation | One explicit Send produces the exact fresh test reply through forced Phone relay with watch-owned TLS. The companion reports an active relay; server-profile voice remains selected. The initial reply scan missed it below the viewport; a subsequent scroll confirmed the exact reply without sending again. |
| Phone permission | The relay was enabled but API 37 local-network permission was denied. The normal Disable/Enable flow requested the Android permission; granting it restored private-endpoint relay access. |
| Restoration | Automatic mode is verified by reopening settings and uses the Phone TLS path. The original 15-second screen timeout is restored, the submitted draft is empty and phone USB debugging remains authorized. |

Framework touch-scroll automation did not reliably advance the vertical list; mouse scroll
input was used to reach controls. Manual vertical/rotary usability remains unaccepted.
The old LAN address refuses connections while the TLS front end passes default-trust and
WebSocket-upgrade checks; no live listener or Serve/ACL policy was changed.

Live three-box catalog refresh, portrait/pet delivery, locked-phone catalog/background
behavior, physical speech quality, radio throughput, battery and Play acceptance remain
open. Private addresses, backup ciphertexts, screenshots and helper scripts remain ignored.
