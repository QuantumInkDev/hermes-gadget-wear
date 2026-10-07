# Physical-device acceptance runbook

Use this after the software/emulator checks in [progress](progress.md). A completed step
needs an observed result on the named devices; a configured setting or emulator result
does not close the physical gate. Keep endpoints, device serials, screenshots, credentials
and measurement records in ignored `.local/`. Publish only sanitized results.

The first [physical results](physical-results.md) record direct/TLS relay, background
startup, cellular/VPN relay and the corrected Ultra decoder checks.

## Update without losing enrollment

1. Obtain the watch's current **connection** IP:port from the main Wireless debugging screen.
   Its pairing-dialog port/code is a different connection. Use the already approved device;
   confirm model/form factor before operating it. Connect the phone with USB debugging.
2. Inspect the installed watch package and compare its signing certificate with the local
   APK. Verify the watch/phone APK certificates match each other. CI debug APKs use a separate
   ephemeral signing identity; they cannot replace a locally enrolled installation safely.
3. Install compatible local APKs as updates. Preserve identities, setup, pets and phone keys.
   Do not uninstall, clear app data, reset the vault or forget server enrollment to bypass
   a signing/auth failure. Such failures need investigation before proceeding.
4. Open the watch's existing LAN profile and verify a fresh typed/voice conversation still
   works. Record app versions, observed transport, transcript, reply and server voice. A
   visible tool/status message is not proof that a watch notification/timer action executed.

## TLS phone relay and setup sync

1. Keep the phone and Hermes host on the intended tailnet. Enable the phone companion relay,
   grant its requested permissions, and complete the watch association if needed. Confirm
   the phone's Tailscale VPN is active; host-side TLS success alone does not prove phone access.
2. Save the actual TLS Gadget URL as a separate phone profile with Server voice and **Phone**
   transport. Send setup, then import/review it on the watch. A changed URL has its own
   watch identity and enrollment; it must not reuse the saved LAN endpoint's device key.
3. Approve that new identity on the intended Hermes host. Confirm the watch shows the phone
   path and a real round trip succeeds. Forced Phone mode prevents direct LAN success from
   being mistaken for relay success. The phone forwards opaque watch-owned TLS bytes.
4. With the phone on USB for control, test cellular access by turning its Wi-Fi off while
   keeping its VPN and watch link available. Record the network/path, then restore the
   original setting. Do not disable the watch's only ADB link without a recovery path.
5. Background and lock the phone; start another explicit watch turn. Then stop/interrupt
   the relay. Check bounded failure, preserved reply/draft, cancellation and a fresh explicit
   reconnect. Restoring connectivity must not resend old input or silently downgrade TLS.

Bluetooth-specific throughput still needs evidence of the actual Data Layer path; the OS
can route through Bluetooth, Wi-Fi or cloud. A Phone label proves the app's relay choice,
not the underlying radio. Record each tested condition separately.

## Opus, pets and phone BYOK

These require the compatible optional server extensions. Review the
[upstream patch series](../server/README.md) before any live installation. Prefer an isolated
test server while retaining the original enrolled profile. A stock server remains PCM/server
TTS and may have no pet; that is a compatibility result, not extension acceptance.

- Run identical spoken phrases over PCM and negotiated Opus. Record payload bytes, capture
  duration, transcript, release-to-text/reply/audio latency, interruptions and perceived
  clarity. Payload bitrate is `bytes × 8 / seconds / 1000`; record framing/network overhead
  separately. Synthetic tone counts do not establish speech/STT nonregression.
- Verify the selected profile's pet on the Ultra at normal/larger text sizes. Check listening,
  thinking, approval, reply, playback, offline and ambient states, with and without companion
  rows. Record actual lip sync and clipping; never publish personal pet art.
- Phone BYOK is a separate opt-in test from server ElevenLabs voice. Enter the owner-selected
  key through the protected phone UI, review billing disclosure, use the read-only quota
  check, then select client speech for the exact TLS profile. Verify one explicit synthesis,
  cancellation and profile isolation; provider failure should retain the readable reply.
  Existing server voice does not prove phone BYOK. Never copy a server key automatically.

## Lifecycle, surfaces and battery

For instrumentation on an enrolled personal watch, select only reviewed classes. The
initial allowlist was `OpusCodecTest`, `ProviderOpusTest`, `RelayTlsTest`,
`IdentityVaultTest` and `WatchSurfaceTest`; vault tests use unique test directories and
aliases. Do not run the complete suite or reuse emulator scripts that clear app data.
Snapshot identity ciphertext hashes privately before and after testing.

- Check mic/notification permission denial and recovery, approval guard/expiry, switching
  during capture/playback and disconnect while editing. Preserve enrollment throughout.
- Add the Tile and a supported short-text complication on the physical watch. Verify tap
  opens the conversation, then capture waits for a new user action. Verify ambient entry,
  capture cancellation, static outline, minute clock and return to interaction.
- Inspect the watch service during an active turn and after actual audio drain. Ready idle
  should release foreground lifetime; inactivity should disconnect under the documented policy.
- Measure idle and conversation battery over timed, unplugged windows with fixed brightness,
  always-on setting, network, audio volume and workload. Record start/end charge, elapsed time,
  temperature and charging state. Restore settings afterward. Charging or short emulator runs
  cannot establish Ultra battery life; repeat comparable windows before drawing conclusions.

## Identify the auth layer before changing credentials

Development-agent/T3 login, phone VPN login, Gadget access token/device HMAC, Hermes model
provider auth, remote-tool OAuth and speech-provider auth are independent. Record where the
failure appears, its time and a sanitized error code. A valid TLS/WebSocket upgrade establishes
reachability and server identity, not watch enrollment or model/provider access. A remote-tool
401 does not establish a Gadget pairing failure. Preserve watch keys while resolving the
affected layer; retry a fresh explicit turn after recovery.

Continue to the publisher/signing/internal-track steps in [release preparation](release.md)
only after their prerequisites are ready. Gateway v2 remains a proposal requiring its
separate decision and upstream feedback.
