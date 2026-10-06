# Release preparation

Status: software preparation, not a Play release. Hardware acceptance and Play prerequisites
remain open. Package ID on both form factors is `dev.quantumink.hermesgadget`. Wear version
code 10001 and phone 10000 deliberately differ; future uploads must increase the applicable
codes. Version name is 0.1.0. Target API is 37, minimum Wear API 33 and phone API 29. The
watch declares standalone=true because direct user-configured connections work without a
phone; private tailnet relay and optional phone voice still require the companion.

## Artifacts and signing

Run the strict checks from the README, then:

```bash
./gradlew :wear:bundleRelease :mobile:bundleRelease --warning-mode=fail
```

Without private signing configuration the bundles are unsigned review
artifacts, not Play uploads. Build outputs remain ignored.

For a signed release, the owner supplies `.local/signing.properties` with `storeFile` (a
root-relative keystore inside `.local/`), `storePassword`, `keyAlias` and `keyPassword`.
Protect the file/keystore with owner-only permissions; do not paste keys in chat or public
issues. Both form factors must use matching signatures for Data Layer. No signing key is
generated or rotated by this project. Use the existing owner upload key and Play App Signing
configuration if the application already exists. If not, the owner must choose and retain
that identity. Upload/signing access was not supplied during this implementation.

## Internal-track checklist

- Finish real phone/watch Data Layer, association/background/VPN, off-network and interruption
  tests, plus profile import and provider voice/billing output verification.
- On the Ultra, compare the same spoken phrases over PCM and Opus, record bitrate, latency,
  STT accuracy, speech clarity, lip sync, idle and conversation battery drain.
- Verify round text at default/larger fonts, approval guard/expiry, switch during media,
  microphone/notification/network denial, ambient return, Tile tap and complication selection.
- Supply publisher identity/contact/effective date, host the [privacy policy](privacy.md),
  review the [Data Safety worksheet](data-safety.md) and provide Play's required disclosures.
- Create/select the Play application and Wear form factor; enroll the correct signing keys,
  build signed bundles and validate them; use the owner's internal-test group and credentials.
- Complete foreground-service declarations: watch microphone for explicit capture and media
  playback for an active spoken conversation; phone connectedDevice for active watch channels.
  Stop controls are available and services do not auto-start at boot. Record demonstration
  videos showing user initiation and termination. Review the final waiting-turn behavior.
- Add original icon/art and synthetic app-only round screenshots. Supply a feature graphic,
  publisher support details, content rating, access/testing instructions and country choices.
- Upload to internal testing, install from Play on both devices, confirm matching signatures,
  enroll against an isolated test server and run the release matrix. Record actual Play links
  and results; an unsigned bundle/debug sideload is not internal-track acceptance.

## Draft listing

Name: Hermes Gadget
Short description: Talk to your own Hermes agent from your Wear OS watch.

Hold the watch screen to speak, read a streamed reply, hear your profile's voice and answer
approval prompts. Connect to the Hermes Gadget server you configure, directly or through
an Android phone on your own Tailscale tailnet. Save independently paired profiles and see
their optional pets. A Tile and complication open the conversation, with a quiet ambient
outline between interactions. Optional ElevenLabs phone voice uses your own key and billing.

Requires a compatible self-hosted Hermes Gadget server. No project account, hosted agent,
analytics or advertising. New pets, Opus and client voice require compatible optional server
extensions. Messages pushed while disconnected are lost with today's stock server, except
pending prompts that are re-sent. This app is an independent MIT project; do not imply
endorsement by upstream, Samsung, Google, Tailscale or ElevenLabs.

## Primary references

[Wear distribution](https://developer.android.com/training/wearables/packaging),
[Wear quality](https://developer.android.com/docs/quality-guidelines/wear-app-quality),
[Play Data Safety](https://support.google.com/googleplay/android-developer/answer/10787469).

## Local unsigned-bundle verification — 2026-10-06

Official bundletool 1.18.3 validated the following local build outputs. Manifest inspection
confirmed package/SDK/version codes/standalone metadata and no debug flag. ZIP inspection
found no signing records. These hashes identify the recorded local artifacts; CI rebuilds
are separate artifacts and can have different hashes. APKs/AABs stay ignored.

| Module | Version code | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| wear | 10001 | 11,047,874 | `13efe7ac3f6ff72e3238c21db7cca9368fd9a1e4505cc77f1272d884db27316d` |
| mobile | 10000 | 9,104,355 | `fad98dfee3a1d383298136c8998fb22ba036e98ce2937a82b97abc19965eff3e` |

[Actual synthetic watch captures](demo/m8/README.md) and [original store-art drafts](store-assets/README.md) are available for review. Physical battery/quality and Play gates remain open.
