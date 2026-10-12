<!-- Current continuation: 2026-10-12 safe dog-walk checkpoint. Native Tasks 1–5 reviewed; Task 6 implemented at 5aa0d3b but review requires a concurrent local/phone pin-edit fix. Resume original Task 6 implementer for fix round 1, then scoped re-review before Task 7. All 18 owner GIF imports and stable watch assignments verified; selected-watch motion confirmed. Both timeouts restored; no physical operation or child task running. New native builds await final review/delivery. Recovery ledger: .superpowers/sdd/2026-10-12-native-client-design/progress.md. -->
# Session status

Native redesign approved, 2026-10-12: Portrait Studio, original Voice Q,
Light/Dark/Electronic Moonlight, swipe-first watch picker without Previous/Next,
and one/four-profile tiles. Tasks 1–5 are implemented with clean scoped reviews,
including theme persistence, native phone directory/conversations, real capture/playback
waveforms, watch picker, durable static tiles and guarded profile launches.

Task 6 phone tile composer/synchronization is committed at 5aa0d3b. Twenty-two affected
JVM tests, five native cases across all three themes and strict affected checks pass.
Review found an Important lost-update path: an already-open watch pin editor can restore
stale untouched fields after a phone edit. Resume /root/native_phone_tile_sync for fix
round 1 using task-6-review.md, then run a fresh scoped re-review. Task 6 is not complete.
Actual associated Data Layer delivery is also unverified: the owned emulator returned
Wearable.API API_UNAVAILABLE. Task 7 final whole-branch checks/review, native artifacts
and preserving physical delivery remain pending.

Safe dog-walk checkpoint: no physical operation or child task is running. All eighteen
normal owner GIF assignments match every source frame and the correct watch profile
keys; selected-watch motion is verified. Phone timeout is restored to five minutes and
watch timeout to its original fifteen seconds. Personal receipts are ignored under
.local/native-design/. Physical devices retain b724231; the new native redesign and
tiles are not installed. Walk observations apply to that existing build. The approved
[design](docs/superpowers/specs/2026-10-12-native-client-design.md),
[plan](docs/superpowers/plans/2026-10-12-native-client-design.md) and native receipts
are the continuation map. SDD recovery ledger: .superpowers/sdd/2026-10-12-native-client-design/progress.md.

Authorized phone Talk/Chat setup now has 18 independently paired profiles: twelve M5 Max,
four GPU 5090 and two Win 11 ARM. Fresh typed replies passed on all three default
profiles; the other fifteen were enrolled without reply probes. After waking the
GPU, its normal phone enrollment/reply passed. The maintainer sees phone transcript,
tool calls and replies but expects the configured ElevenLabs voice. A read-only
probe found speaker media volume 0/15. The maintainer now confirms voice works after
raising volume; no TTS/provider change is needed. Real capture/playback waveforms and
a muted-media hint were requested and added to Task 3. This confirms the previous
installed phone build, not future native speech acceptance or an all-profile matrix. App-file backups verify all preexisting identities, pictures
and preferences unchanged; only refreshed catalog pages changed and new independent
phone identity/known files were added. Five-minute phone timeout is restored. Private
receipts stay in .local/native-design/phone-live/. No watch key was reused.

Owner phone UI follow-up: three groups/portraits/Talk/Chat are visible. Clearer full-width box
headers and connected panels, explicit dark foreground (native contrast 1.15→14.05:1), and
the requested Quantum Relay for Hermes title are implemented. Six scoped presentation/order
checks and strict full build/all APKs pass. Refinement b724231 is installed on both devices:
phone APK/certificate and all 42 protected app files/preferences (36 PNGs) are verified;
watch APK/certificate and all 19 identity ciphertexts/18 portraits are verified. The original
watch timeout is restored. The maintainer reopened the physical phone app and confirms the
box headers are clearer to expand/collapse and the top text is readable. Private receipts are
in .local/client-usability/phone-accordion/ and watch-title/. For large watch APK backup,
compressed adb pull succeeded after exec-out streaming timed out; use remote sha256sum
for post-install hash verification. The failed backup made no installation change.

Current follow-up, 2026-10-12: approved Quantum Relay for Hermes client usability and bounded GIF
software is implemented and verified. Watch scrolling retains per-page position and uses
accessible compact arrows. Phone Profiles/Watch/Settings, grouped/searchable/custom-ordered
box directory, inline pictures, original icons and independently paired foreground Talk/Chat
are implemented. Phone requires reviewed explicit Connect and TLS; no watch identities are used.

GIF overrides retain the v1 PNG roster plus an optional hash/length/fallback-bound animation
manifest. Limits: 256×256, 256 KiB, 120 frames, ten seconds and 2,097,152 frame-canvas pixels.
Selected available foreground pages animate, including unapproved cards. Previews, reduced
motion, power saver and ambient use static fallback; validated pets keep priority. One durable
pending GIF survives publication failure/restart beside the settled 32-file/8-MiB cache.
Retired portrait requests cannot re-enter the cache after a roster change/reset.

Strict full checks/lints/format and all debug/instrumentation APKs passed with warnings as
errors. JVM: 105 tests, zero failures/errors/skips (90 protocol, 13 mobile, two Wear).
Owned-device acceptance: 19 phone storage/directory tests, three stock TLS/vault/playback
checks, 14 Wear animation/ambient/cache/capture/codec/surface tests, plus the complete
three-profile carousel and vertical touch/return/refresh/aborted-drag scenarios. The final
stationary scroll gesture stops before release so ordinary fling motion is not called a reset.
GIF pixel sampling uses original checkerboard pixel interiors, avoiding blended boundaries.

Fresh Astra review found six Important defects and one ambient mismatch; all seven were
corrected and the source regrade is clean. See docs/client-usability-review.md. Playback
completion uses atomic guarded updates without the session monitor; Stop voice remains
available during playback. Recording feedback joins recorder release with accepted protocol
outcomes, including short capture, automatic limit, rejected sends and stale tokens.
Private verification receipts are in .local/client-usability/.

The final watch build cea12b5 is installed after a matching-certificate check and app-file/APK
backup. Its installed APK hash matches the tested artifact; all 19 identity ciphertexts and
18 portrait hashes match before/after. Original screen timeout is restored. The initial ADB
status probe incorrectly expected tab separators; splitting whitespace found the isolated
watch connection correctly. Private receipts are under .local/client-usability/watch-final/.
The final phone build is now installed after comparing its existing signing certificate and
backing up its APK, app files and preferences. The installed APK hash matches the tested build.
All 39 protected files, including 36 portrait PNGs, and both preference files are unchanged.
Receipts and the active private USB connection are under .local/client-usability/phone-final/;
the watch’s refreshed connection is recorded in its existing private session config. The phone
was attached to an existing isolated ADB server, not the resetting shared default server.
No app data was cleared, identity seeded or signature bypassed. The phone activity was not
launched remotely; the maintainer's phone UI observation now passes. Phone client pairing remains explicit;
no bulk live enrollment or real-device microphone test was initiated. All three GitHub CI jobs
for code cea12b5 and docs de620f4 passed before this delivery note.

Work resumed. Next: approved native implementation/reviews, remaining GPU phone pairing/speech
checks, GIF battery/rendering and office trial. Owner manages Mac awake state; no power helper is installed. Existing live
18-profile cellular relay and three-profile watch speech evidence below remains historical;
it does not establish the new phone client or GIF power gates. Keep PR #1 draft; no merge,
release signing, Play upload or optional live server extension is authorized.

Updated: 2026-10-12. The authorized live profile/portrait setup and typed cellular relay
checks are complete. All three sources are on the phone; all 18 supplied round picture
overrides were chosen through the native picker and verified in both actual device caches.
The watch has 19 pages: 18 discovered TLS profiles and its retained original LAN page.
All 17 new endpoints completed normal watch review and their correct stock CLI approval.
All 19 identity ciphertexts are unchanged through the final app updates, including both
original identities. No app data was cleared and no identity or portrait cache was seeded.

All 18 profiles returned fresh markers with the phone keyguard showing, Wi-Fi disabled,
validated cellular/Tailscale and USB debugging only: 12, four and two profiles per box.
A fresh cold startup also passed with no prior phone relay foreground service. Every
observed conversation used Phone relay with watch TLS. The earlier provider rate-limit
response was followed by successful fresh replies; an earlier connection timeout was not
reproduced in the resumed cold check. These typed checks do not prove audible speech quality
or an isolated Bluetooth radio path. The legacy LAN endpoint is outside the 18 TLS passes.

A controlled outage of only the owned Mac catalog job retained the selected page, 19-page
order and portrait; refreshing after its return removed the offline badge. The phone stayed
locked on cellular, portrait references stayed identical, and the exact owned job and Serve
configuration were restored. The first immediate startup probe was early; the repeated
check waited for HTTP readiness and passed. This is catalog outage/recovery, not a whole-box
power-cycle test. The maintainer will manage the Mac's awake state; no AC helper was installed.

Portraits are now twice their original display diameter: 160 dp, measuring 340 by 340 pixels
on this Ultra. Portrait pages use 28 dp top padding instead of 40 dp. The final measured
bounds are [70,69,410,409], 25 pixels higher, with the portrait center at y=239 on a 480-pixel
screen. The full circle and clock were visually checked. Pet canvas sizing remains as before.
The final installed watch APK matches the local build and retains the matching phone signature.

Phone Wi-Fi is restored to enabled, phone screen timeout to 300000 ms and watch timeout to
15000 ms. The existing root TLS page is selected; its saved Automatic preference was verified
through normal settings. All 18 actual portrait hashes and 19 identity ciphertexts were
rechecked after the final installation. No test worker remains active.

Strict full checks, both debug/instrumentation builds and unsigned release bundles passed
with warnings as errors; JVM reports contain 71 tests and zero failures/errors/skips.
Debug relay failure diagnostics include only exception class, errno, elapsed time and
foreground/network-blocked flags; no private messages, keys, URLs or identifiers are logged.
Private receipts, APK/vault backups and screenshots are in
`.local/profile-carousel/round-portraits/` and `physical-watch/`; diagnostics are in
`.local/network-probe/`. Use `cellular-turns.json`, `catalog-outage-result.json`,
`centered-portrait-install.json` and `final-restoration.json` for exact continuation evidence.

The maintainer now reports that all three requested hold-to-talk checks passed: visible
transcript, answer and audible profile voice on one profile from each box. This closes the
fresh functional speech demo by maintainer report, recorded privately in
`speech-report-2026-10-12.json`; the speech network/lock conditions were not separately
reported. CI for code commit `628de74` passed Android, extensions and secrets.

Next: the office trial, whole-box power transitions, measured speech/codec quality, radio
interruption/throughput, unplugged battery, live optional pet/Opus/client-voice extensions
and signing/privacy/Play acceptance. Keep these measurements separate from the functional
voice report. Do not install optional SDK extensions on live hosts without their review.
Use [the setup guide](docs/profile-setup.md), [physical report](docs/physical-results.md) and
[release gates](docs/release.md). Earlier dated snapshots below are historical.

## Current continuation — portraits and live setup, 2026-10-11

The maintainer requested all original portraits in one private folder, a precise round-image
regeneration prompt, imported box pictures with explicit watch overrides, and setup of the
missing profile endpoints with publication instructions. This request authorizes the concrete
live rollout previously prepared for review; earlier no-deployment notes below are historical.

All three owner-context catalogs are now live over private HTTPS: 12, four and two profiles.
All 18 portraits passed authenticated HTTPS hash/length checks, with 401 without credentials.
All 18 stock SDK v0.2.0 listeners passed loopback WebSocket checks and default-trust WSS
upgrades from their own boxes. Catalog mappings now contain only verified explicit URLs.
The 17 new conversation ports and three catalog routes preserve existing Serve routes;
owner-only policy additions passed validation and ETag-guarded publication. Existing default
conversation service/enrollment remains intact. Physical phone reachability is not inferred
from these host-side checks. Windows jobs run at owner login and continue while locked.

The root managed Gadget port pin on macOS was scoped to its default profile to prevent
secondary port collisions. On Windows, Gadget was added to the managed plugin allow-list
without changing other entries; normal gateway restarts drained cleanly. Private per-profile
configuration backups, verification, access-policy evidence and rollback notes belong under
`.local/profile-carousel/rollout-review/`; no personal endpoints, art or credentials are tracked.

The phone now stores explicit watch pictures independently of the latest imported box image,
keyed by box/profile. Picture options supports choosing an override and restoring the box
picture, including before connection setup. Strict checks and debug APK builds pass with
warnings as errors; 71 JVM tests and 12 owned API 37 phone vault/portrait/repository tests
pass. Native phone UI discovery and picture controls were exercised against the live local
catalog. Original portrait pack and exact 1024-master/256-runtime prompt are in ignored storage.

The phone is authorized and all three sources, 18 portraits and 17 new per-profile approvals
are now installed as recorded above. Earlier pending source-entry and authorization notes
are superseded. Preserve both original watch identities and local signatures. [Publication setup](docs/profile-setup.md)
documents box/profile prerequisites and diagnostics. Real speech, locked-phone catalog,
source loss/return, battery and signing/Play gates remain open; keep using [release](docs/release.md).


Updated: 2026-10-11

New maintainer scope: swipeable watch profiles from three independently available boxes,
with per-profile pictures transitioning to pets and additional animations. The approved
[design](docs/superpowers/specs/2026-10-11-multi-box-profile-carousel-design.md) covers
automatic phone catalog aggregation, independent TLS endpoints, stable offline pages and
ambient-safe selection/setup review. The maintainer approved app and isolated catalog development; live
source/endpoint publication and any new pairing remain separate review steps. Personal
source names and connection details stay ignored. Latest existing-code/docs CI `be3a2ef`
[passed all three jobs](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37576578446).

Latest continuation: both personal devices were verified as Samsung Ultra models on
Android 16/API 36 and received local matching-signature apps. Original watch enrollment
was preserved. Real direct and forced Phone TLS replies passed, along with reviewed phone
setup import, independent TLS enrollment and fresh locked-phone background startup. The
phone companion chooser correction is installed; one matching watch association remains.
See docs/physical-results.md. Both screen timeouts and phone Wi-Fi were restored.

The Ultra baseline passed 10 of 12 allowed instrumentation tests; two Opus burst tests
failed on unavailable decoder input buffers. The bounded output-draining correction is
now installed and all 12 tests pass on the Ultra, with both personal identity ciphertexts
unchanged. Strict checks, 56 JVM tests, four owned API 37 emulator codec/TLS regressions,
and rebuilt/validated unsigned bundles pass. The physical three-second tone produced
8,905 Opus payload bytes in 151 packets; speech-quality and battery acceptance remain open.
A fresh controlled cellular/VPN Phone TLS request also passed with phone Wi-Fi off and
no USB tether function. Phone Disable/Enable preserved an unsent synthetic draft, but bounded failure/reconnect
was not fully observed. Both temporary screen timeouts and phone Wi-Fi were restored;
relay is enabled and both phone/watch TLS profiles are saved as Automatic. The watch
setting was verified by reopening server settings; both identity ciphertexts were unchanged
and its original screen timeout was verified restored. An earlier idle app restart cleared
the transient test draft/screen without changing those identities. Preserve both
LAN/TLS endpoint identities; CI debug certificates differ from the local devices.

The decoder/chooser fixes and sanitized results were pushed as `e9bfbce`; all three
[CI jobs](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37573623286) passed.

M0/M1 complete. The core M2 physical Ultra demo passed: automated real-Hermes typed round trip, followed by maintainer-reported weather/STT/reply/ElevenLabs playback. The maintainer found the flow clunky and authorized continuing M3–M8 while away/off the home network. Remaining Ultra action/media, permission/lifecycle, audio-quality and battery checks stay open.

M3 software is implemented: watch-owned TLS through an opaque ChannelClient phone relay and loopback CONNECT bridge; Automatic/Phone/Direct override; same-endpoint network fallback without input replay; last-reply preservation; opt-in companion, stop controls, connected-device foreground service, Android companion association. Relay cannot use ws and certificate/authentication errors do not trigger downgrade. The watch connects on app open and idles out as before. Actual Data Layer import, forced TLS relay, locked-phone background startup and cellular/VPN conversation now pass; Bluetooth-specific throughput and interruption still need their named observations. See [progress](docs/progress.md), [architecture](docs/architecture.md), and [self-host setup](docs/self-host.md).

Verified: strict full Android/JVM build, lint, format, warnings-as-errors, both debug APKs and watch instrumentation APK; 38 JVM tests, zero failures/errors/skips, including actual stock SDK direct and relay tests. API 37 Android loopback TLS test rejects untrusted certificates and accepts test-injected trust. Existing M2 Keystore/setup/draft emulator results are recorded in progress; no personal-watch reset tests were run. M3 commit `210bbcc` was pushed; [CI](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37465546979) passed.

Maintainer TLS infrastructure is configured privately: tailnet-only Serve HTTPS proxy on the existing allowed Gadget port, Funnel disabled, backend unchanged. Certificate/hostname validation and the real WebSocket upgrade passed using default trust without sending enrollment/conversation. Read-only live ACL verification confirmed the existing own-device grant and no Gadget port in box-to-box access; no policy edit made. Actual phone access through the TLS relay is now verified in the physical report. Private files are under `.local/tailscale/`; never print or track them or the `.hermes/.env` credential. The personal watch retains its original LAN identity and its independently enrolled TLS identity.

M4 optional Opus is implemented with per-direction selection, stock PCM fallback, platform probe, MediaCodec capture/playback, unified CSD extraction and raw 20 ms packets. The isolated SDK patch has real ffmpeg pipes, bounded Ogg wrapping, paced output and protocol docs. [Measurements](docs/audio-measurements.md) include synthetic emulator and physical Ultra payload counts, not speech or network throughput. Strict app checks/42 JVM tests, three Android codec/TLS tests, the stock draft regression, and 21 Python patch/compatibility/live-WebSocket tests passed. The live Hermes environment remains unchanged. The opt-in service-level Opus emulator demo also passed: actual AudioRecord capture, negotiated Opus upload, SDK loopback response/playback path, encoder flush on release and recording cancellation. Host microphone/speaker were disabled; this does not measure physical quality. The patch series was exported from `9c400f9` on local SDK branch `wear/optional-extensions`. No upstream PR/review is claimed.

M4 commit `82a206b` was pushed; [CI](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37468005261) passed all three jobs. M5 pet delivery, cache/renderer/state fallbacks, original placeholder and registration tooling are implemented. Strict app checks and 46 JVM tests passed with zero failures/errors/skips. The exported SDK patches (Opus `9c400f9`, pets `2bcdfc7`) applied to a fresh pinned checkout; 29 Python SDK/tool tests passed. Two Android pet delivery/cache/corruption checks and the stock draft regression passed on the owned emulator. [Pet docs](docs/pets.md) and [synthetic capture](docs/demo/m5/README.md) record limits. Real-profile pets, Ultra legibility/lip sync and ambient power remain open.

M5 staged-export gitleaks, personal-value and local-document-link checks passed.

M6 phone BYOK shared/per-endpoint vault, quota checks, native Opus/PCM streaming, cancellation and server negotiation are implemented. Strict full checks passed: 53 JVM tests, zero failures/errors/skips, 31 Python tests from a fresh three-patch export, actual owned-phone Keystore and owned-watch native Opus decode instrumentation. Stock-readable replies and optional client speech are checked against actual SDK processes. No paid provider call, personal key or live gateway extension was used. Actual phone BYOK provider output/billing/voice quality and its speech Data Layer path remain open.

M6 `ac329d1` CI passed all jobs. M7 v1 encrypted endpoint catalog, independent keys, migration/removal, phone setup/import review and media teardown are implemented. Strict checks passed with 56 JVM tests, zero failures/errors/skips. Actual two-stock-server tests, five owned-watch vault tests, one owned-phone vault test and actual watch-service switching during recording/reply clearing/reconnect/removal passed. Actual phone Data Layer import now passes; swipe/rotary and physical profile media remain open. See docs/profiles-v2.md; v2 remains proposal-only.

M7 `009b174` CI passed all jobs. M8 watch Tile, generic complication, sparse ambient outline,
compact conversation controls, service-owned drafts and foreground release after turn/audio
are implemented. Strict checks, both debug/instrumentation APKs and both unsigned release
bundles passed with 56 JVM tests and zero failures/errors/skips. Eleven owned-watch device
tests passed (six vault, two surfaces, lifecycle, draft, profile); final compact-layout draft
regression passed again. Actual Tile render/tap/process-death excerpt, system ambient/wake and
normal/1.3 font captures are in docs/demo/m8/. Official bundletool 1.18.3 validated both unsigned
bundles; package/SDK/standalone/version codes/signing absence were inspected. CI publishes
review bundles and debug apps. Original store art and privacy/Data Safety/listing/signing
checklists are prepared. No signing credentials or Play upload access was supplied.

CONTINUE HERE: real-device/publisher gates in docs/release.md. M8 is software verified, not
battery or Play accepted. Continue relay interruption/radio measurements, actual provider voice/billing, physical
Ultra audio/STT/legibility/pet/ambient power, Tile/complication
and button mapping; then supply signing/publisher/privacy/Play setup and perform internal-track
installation. Do not deploy the optional server patches into live Hermes without their review.

The maintainer authorizes advancement with open hardware gates. Do not mark Ultra throughput/STT/quality/battery or the untested client-speech Data Layer path complete. Multiplexed gateway v2 remains proposal-only pending explicit DECISION and upstream-maintainer feedback; no maintainer messages have been authorized.

Physical pairing approval was already fulfilled. Private ADB/settings/captures remain ignored; verify restoration of the temporary device settings at the end of a hardware pass. Use only the owned Hermes test AVD for local checks and exact serials. Existing unrelated AVDs remain untouched. The public demo uses synthetic data and original procedural artwork. No PR existed at the start of carousel implementation.

## Current requested-review and physical-watch continuation

The maintainer-requested PR #1 review of `235ba3c` identified four additional Important
findings, all addressed with failing-then-passing regressions. Strict full checks/builds
pass with 71 JVM tests (zero failures/errors/skips), seven publisher tests and ten owned-phone
instrumentation tests. Owned-watch combined-capacity recovery, rejection before enrollment
and the original three-profile swipe/draft/recording/approval checks pass. See
[review resolutions](docs/carousel-review.md) and the
[follow-up plan](docs/superpowers/plans/2026-10-11-carousel-review-followup.md).
Review fixes were pushed as `556c365`; both CI runs passed Android, extensions and secrets
jobs. PR #1 remains a draft and is linked to this thread.

A physical watch pass for existing approved endpoints is complete. Both personal devices
have the matching-signature review-fix build. After the earlier stalled transfers, a refreshed
local debugging address and a fresh isolated watch-only ADB server restored connectivity.
The installed hash was checked before retrying: it still identified the old build. Preserve-data
installation then succeeded, and the new on-watch hash matches the local artifact. Both
existing identity ciphertexts match the original checksums after the complete pass. Private
backup/signature/artifact records and helpers are in `.local/profile-carousel/physical-watch/`.

The watch now displays the phone's manual TLS card and its watch-only LAN card. Actual
horizontal input switches between the two pages; sleep/wake preserves selection. One owned
TLS draft survives a profile round trip, and one explicit Send returns the exact real-server
reply through forced Phone relay with watch-owned TLS. The submitted draft is verified empty.
The phone's API 37 local-network permission had been denied despite the enabled relay; the
companion Disable/Enable flow requested it and it is now granted. Automatic transport is
verified by reopening watch settings and uses the Phone TLS path. The old LAN address refuses
connections; the TLS front end passes trust/upgrade checks. No server binding or policy changed.

The temporary 10-minute watch timeout is now restored and verified at its original 15 seconds.
The active physical connection is recorded in private `current-connection.json`; its isolated
ADB server is separate from the still-authorized default phone USB server. The two owned test
emulators remain available. Do not reconnect to historical ports or reset enrollment.
Framework vertical touch-scroll automation did not reliably advance the list; mouse input
reached controls. Manual vertical/rotary usability, live three-box catalog/portrait delivery,
locked-phone catalog behavior and battery/provider/Play gates remain open. Live publisher
deployment and any new URL pairing still require their separate reviews. The cause of the
earlier network/ADB stalls is unproven; a shared Wi-Fi name alone did not establish reachability.

## Carousel implementation checkpoint — 2026-10-11

Approved app/isolated catalog work is implemented on `feature/multi-box-profile-carousel`.
[Draft application PR #1](https://github.com/QuantumInkDev/hermes-gadget-wear/pull/1) is open
and linked to the implementation thread. Local strict gates pass; CI is reported by the PR.
The [five-task plan](docs/superpowers/plans/2026-10-11-multi-box-profile-carousel.md) covers
bounded publisher/contract, phone sources/discovery/photo/cache, watch enrollment-safe roster,
endpoint drafts, stable pager, ambient-safe review and selected-only portrait/pet transitions.
Strict checks and all APKs pass: 69 JVM tests, zero failures/errors/skips; six publisher tests.
Owned API 37 phone vault/photo and watch cache/catalog checks passed. New swipe/Next/rotary,
recording/approval protection, three separate identities/drafts/artworks, actual sleep/wake
review and moving/frozen-renderer checks passed. Original draft/profile/lifecycle, companion
and base-only pet regressions passed. [Sanitized demo](docs/demo/carousel/README.md).

No live box, ACL/Serve policy, personal artwork/credential or personal device enrollment
was changed. Read-only inventories produced private proposed mappings (three sources) and
installation/rollback notes in `.local/profile-carousel/rollout-review/`. Unverified Gadget
URLs remain null and portraits await owner review; no address was inferred. The new catalog
Data/Channel Layer path and physical three-box relay remain acceptance gates. Existing SDK
extension exports remain isolated. T3 device-panel boot failed twice; owned AVDs were operated
through isolated ADB only. Preserve existing personal LAN/TLS keys and app signatures.

Fresh whole-branch review is complete. All eight Important findings were fixed in one
TDD pass with a green full suite; there were no Critical or deferred Minor findings.
[Review resolutions](docs/carousel-review.md) records the tests and retained acceptance gates.
Startup restores the saved card before connecting, stationary metadata reconciles artwork,
conflicting sources remain isolated, phone-selected fallbacks survive refresh/restart,
portrait ingestion and replicated retention are bounded, and owner-confirmed withdrawal
retirement frees catalog capacity without removing endpoint setup or pairing keys.

## Concrete catalog rollout review — 2026-10-11

Three private packages now include explicit stable-ID/portrait mappings, hash-checked offline
Pillow wheels, dedicated credential generation, owner-context job definitions and guarded
installation/HTTPS publication/removal controllers. The scoped catalog-access proposal passed
the provider validation endpoint without a policy write. Existing source profile/config/art
hashes and Serve configuration are unchanged; no live source catalog was deployed.
Private portrait exports meet the watch limits and the review gallery is in this thread.

Five private package tests and seven publisher tests pass with warnings as errors. An actual
isolated macOS launchd catalog started, served authenticated portraits and was removed with
its port released and state preserved. Windows definitions parsed on both actual hosts with
their required cmdlets available; Windows native job execution and HTTPS publication remain
untested. Public app/publisher code was unchanged during this preparation.

CONTINUE HERE: obtain the approved design's live publication/artwork review for the concrete
packages in `.local/profile-carousel/rollout-review/packages/` and private scoped policy
proposal/controller. Recheck hashes, source availability, port and current Serve/policy ETag
before deployment; controllers refuse stale review state. The private package README gives
installation, start, authenticated checks, publication and ownership-checked rollback steps.
Then configure the reviewed sources through the phone UI and verify real watch portrait
delivery, stable source loss/return and locked-phone refresh. Setup-needed cards remain
non-conversational until their independent Gadget endpoints and new pairing are approved.
Existing battery/speech/provider/Play gates in docs/release.md remain open.
