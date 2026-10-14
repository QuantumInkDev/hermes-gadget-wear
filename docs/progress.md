# Progress

## Phone messaging and Courier — 2026-10-14

The approved refinement is implemented: Quantum Relay for Hermes Agents leads smaller
section headings; the inline mic uses hold/release/physical-left cancellation and a bounded
real-level waveform/timer. Accessible Start/Finish/Cancel remains available. Both picture
entry points use one native sheet with a separate Cancel footer. Original Courier A has
adaptive color and OS-themed layers; the three existing app themes are retained.

Warning-strict full checks, formatting/lint and all four APK builds pass: **133 JVM tests
across 38 XML reports**, zero failures/errors/skips. Final native acceptance passes **42
executions /30 distinct methods**: 24 combined composer/picture/gallery/conversation/directory
methods, four methods in each of three font/window scenarios and six independent stock TLS
audio/vault methods. Windows cover 320×640 dp/font1.8, 360×720/font1.3 and 640×320/font1.3,
with all three themes and keyboard states. Original emulator font/window settings are restored.

One fresh whole-feature review found three Important issues, no Critical/Minor findings.
The single corrective pass covers delayed mic startup after release, failed accessible/
permission starts and physical-left cancellation in RTL. Failing-then-passing regressions
and the green suite are recorded in the [review](phone-messaging-review.md). Large-text
hierarchy and short-window keyboard overlap were also reproduced and corrected. Fixture
withdrawal timing, stale accessibility trees, held injected pointers and idle/IME timing
are documented separately; failed receipts are retained and not counted as passes.

The [native gallery](demo/phone-messaging/README.md) contains **39 original synthetic PNGs**
with source/APK/test-APK hashes. Recording layout levels are synthetic; actual capture,
playback, cancellation, permission completion and independent identities pass against two
isolated stock TLS Gadget servers. Demo: hold the inline mic, release to send, slide left
to discard, or type and tap Send. Open Watch picture for the separate full-width Cancel footer.

Phone build `d26e790` is installed through the existing paired wireless ADB transport.
Current APK/data backups, matching certificate and installed APK SHA-256 are verified:
`80aef071c8011dccc04875bfd9c0cac7d22938688b1cb2698a333dce96c4f5ba`.
All **102 baseline files, 18 identities and 54 artwork files** are unchanged. The original
five-minute timeout and seven registered widget providers remain. No app UI was launched
or captured; no reset, enrollment or new pairing occurred. The watch remains at `f35bf59`.

Physical speech/gesture comfort, away replies, widget/tile placement, quantitative power
and existing signing/privacy/Play gates remain separate owner/release checks. Keep PR #1
draft. Private receipts, failed runs and the closed execution ledger stay ignored under
`.local/phone-messaging/`.

## Clear watch portraits and centered footer — 2026-10-13

The owner's correction removes the floating profile menu and microphone badge from the
watch artwork. Jump to profile is the final card control, with its label centered across the
full button. Horizontal swipes, accessible profile selection, stable scroll/drafts, recording
locks, external rings and original portrait geometry remain. A supported device-tuned tick
replaces the attenuated recording cue; the owner confirms both start and finish are noticeable.

Warning-strict checks pass 116 JVM tests / 33 XML reports and all four APK builds. Eleven
distinct affected native methods pass, including configured three-server horizontal and
vertical carousel regressions. Initial fixture failures and missing-server assumption skips
were resolved; skipped cases are not counted as passes. Full theme/font/density geometry
covers 18 scenarios, followed by 18 centered-footer cases. The native gallery refreshes
91 watch/recording exports; unchanged captures retain their earlier provenance.

Preserving physical watch updates verify matching signer/installed APK hash and unchanged
78 baseline files, 19 identities and 29 artwork files. The final `f35bf59` build shows the
clear first view and footer jump control. Original timeout, appearance and tile pins remain;
the owned bridge is stopped. Demo note: hold the unobscured portrait, release after the two
ticks, swipe profiles or scroll to the centered Jump to profile control. Fresh away replies,
speech, microphone contamination, extended haptic comfort and quantitative power remain gates.

## Phone home widgets — 2026-10-13

The approved seven rows × columns presets are implemented with final review corrections through `5a9a459`: 1×1, 2×2,
2×4, 1×4, 4×4, 1×5 and 2×5. Separate instances have stable-key bot choices/order,
static effective portraits, OS appearance by default and explicit Light/Dark/Moonlight.
Exact-profile taps open foreground Talk/Chat without connecting, recording or pairing.
Busy/approval deferral, stale/withdrawn recovery and independent drafts are guarded.

Strict checks pass 125 JVM tests / 35 XML reports with zero failures/errors/skips, warning-strict
lint/format and all four APKs. Thirty-two affected widget/navigation/presentation native methods,
one blocked-watch publication check and five actual stock TLS audio/vault methods pass.
The host geometry matrix covers 112 font/direction/orientation/size cases. Adaptive size
variants share the sixteen-picture bitmap budget after parcel/service delivery. Editor
recreation, stale CAS edits and configuration races have failing-then-passing regressions.
Initial missing fixture environment assumptions were corrected; skipped runs are not passes.

The [native widget gallery](demo/home-widgets/README.md) contains 29 source/APK-bound original
synthetic PNGs. Demo: add a preset from the launcher, choose bots and order, select appearance,
save and tap a portrait for that exact Talk/Chat profile. Reconfigure in Settings → Home
screen widgets. A five-column launcher grid is required for the two five-column presets.

The final whole-feature review found three Important items; one corrective pass addresses
the restore-chain collision and native busy/theme coverage. No Critical/Minor items were found.
The [review record](phone-home-widgets-review.md) retains all decisions and test limits.
On 2026-10-14, a preserving update installed the matching-signer widget APK on the phone
using its existing paired wireless ADB transport. The backed-up APK and app data, installed
APK hash and unchanged 101 baseline files are verified: 18 identities, 54 artwork files and
five-minute timeout preserved. All seven providers are registered. No app UI was launched;
One UI placement, custom ordering/taps, wallpaper/night changes and reboot remain open. API 29–30 runtime is
unavailable. The wallpaper capability probe could not preserve the existing emulator image,
so no wallpaper change was attempted; two real palette changes are not claimed. Existing
watch physical build remains unchanged; the phone update preserves its identity/artwork/timeout state.

## Native client software acceptance — 2026-10-12

The approved native clients are implemented: Portrait studio/Voice Q, three themes,
box/profile organization, independent phone Talk/Chat/waveforms, swipe-first watch picker,
portrait recording/rings/light haptics, bounded foreground reply visibility and one/four tiles.
Tasks 1–6 and 8 and Task 7 software acceptance have clean scoped reviews. Whole-branch review
found four Important issues and one Minor at `ccb2953`; one fix wave `c248b03` resolves all five,
and the single scoped re-review finds no new Critical/Important breakage. The
[review record](native-client-review.md) explains corrections and source-bound validation.

Final strict checks: 116 JVM tests / 33 XML reports, zero failures/errors/skips, all four
APK builds and warnings as failures. Final native receipts: 34 passing executions / 32
methods, no failure/skip in those receipts; 18 density/theme/font scenarios. The
[native gallery](demo/native-clients/README.md) contains 173 original synthetic captures,
with 72 clock-affected exports refreshed and unchanged images retaining reviewed provenance.

Physical watch `c248b03` delivery verifies matching certificate/APK and unchanged 19 identities
plus 75 protected baseline files at installation. Normal UI selected Moonlight and configured
Focus for the current profile, revision 1; grid favorites remain unspecified. Original
15-second timeout is retained and the 340×340-pixel portrait position is unchanged. Physical
phone also received `c248b03` on 2026-10-13 after current APK/data backups and matching certificate/hash
checks: all 99 baseline files unchanged at installation, including 18 independent identities
and 18 GIFs. Normal UI selected Moonlight, opened directory/Settings/Talk/Chat and returned to
Profiles; all identities and 54 artwork files remain unchanged afterward. Five-minute timeout
is retained. The associated watch returned its existing Focus revision 1, empty four-slot grid
and 19 profile keys. Snapshot readback is verified; edit acknowledgement is not. Owned debugging
bridges and the exact temporary phone grant were removed. Prior owner GIF assignments remain
verified, without reimport or publication. No live phone turn or microphone capture was tested.

Associated phone edit acknowledgement/system tile placement/every target, final-build speech,
haptic strength/mic contamination, away reply/sleep receipt and quantitative Light/GIF/audio/
battery tests remain open. OS power saver is a capability skip; reduced motion passes.
Historical server-generated replies do not prove watch receipt. Keep the PR draft.

Demo note: compare the native three-theme gallery, keyboard/large text, watch portrait/
picker/reply and static Focus/grid; hold the physical watch portrait for the next owner speech
trial. Personal endpoints/art and device preservation receipts remain ignored.

## Client usability follow-up — 2026-10-12

The owner confirmed all three phone box groups, pictures and Talk/Chat are present, then requested
clearer collapse/grouping, top-text contrast and the title **Quantum Relay for Hermes**.
Box headers now have a full-row action, machine icon, count, catalog status and chevron;
expanded profile cards sit within their box panel. Reorder arrows are mode-specific, and
search explains why matching sections remain open. Stable keys and saved order are retained.

The contrast regression reproduced a 1.15:1 inherited black foreground on the custom dark
Scaffold background. The shared phone shell explicitly provides onSurface, measuring 14.05:1.
Six owned-phone presentation/order tests pass: contrast, independent accessible expansion and
four existing directory-order/picture regressions. Strict full checks and both app/instrumentation
builds pass with warnings as errors; JVM reports still total 105 with zero failures/errors/skips.
Refinement `b724231` is installed on both actual devices after certificate/hash checks and
backups. Phone protected files/preferences (42, including 36 PNGs) and all watch identity/
portrait hashes (19/18) are unchanged. Original watch timeout is restored. The maintainer
reopened the physical phone app and confirms the box headers are clearer to expand/collapse
and the top text is readable. Phone speech/cues and GIF power observations remain open.


The maintainer approved the [phone/watch follow-up design](superpowers/specs/2026-10-12-client-usability-design.md),
including QuantumInk Relay branding, recording cues, grouped/custom-ordered phone profiles,
independent foreground phone conversations and bounded GIF overrides. The approved software
is implemented. Strict checks, lint/format and all debug/instrumentation APK builds pass with
warnings as errors; JVM reports contain 105 tests and zero failures/errors/skips.

The phone groups/searches profiles by box, persists custom box/profile order, displays effective
pictures inline and provides separate Profiles/Watch/Settings navigation with an original icon.
Phone Talk/Chat creates independent TLS identities only after explicit reviewed Connect.
Three owned-phone stock TLS/vault tests pass: fresh text/PCM responses, reconnect, separate
endpoints/drafts, speaking/Stop state and controlled playback completion overlapping exit.
Leaving the conversation stops its socket/audio while preserving relay settings. Nineteen
phone directory/repository/storage tests pass, including full GIF capacity and retained vaults.

Bounded GIF overrides import through the native picker, normalize a first-frame PNG and
replicate optional hash/length/fallback-bound metadata/assets alongside the unchanged v1 roster.
One pending import survives failed publication/restart without evicting the published GIF.
Fourteen owned-Wear tests pass: actual frame changes on selected/unapproved cards,
static/reduced-motion/pet behavior, ambient monochrome fallback, retirement/admission races,
recording boundaries, codecs and existing surfaces. Exact-frame checks sample source-pixel
interiors rather than the enlarged checkerboard's blended boundary.

Fresh Astra whole-branch review found six Important issues and one ambient contract mismatch.
All seven were corrected and regraded clean; [review resolutions](client-usability-review.md)
record failing-then-passing cases and the reviewer’s read-only limits. Recording cues now join
recorder shutdown with the accepted protocol outcome, covering short release, automatic limit
completion and rejected sends. Actual phone speech, cue audibility/contamination and animated
watch battery cost remain physical observation gates. The final watch build is installed with
matching certificate and APK hash, all 19 identity ciphertexts and 18 portrait hashes unchanged,
and its original timeout restored. The final phone app is also installed with matching
certificate and verified APK hash. All 39 protected app files, including 36 portrait PNGs,
and both preferences remain unchanged. Owner phone UI observation passes; speech remains pending;
no live phone pairing or physical microphone test was initiated. GitHub Android, extensions
and secrets checks passed for code `cea12b5` and delivery docs `de620f4`.

The reported watch scroll reset was reproduced: the pager's progress flag replaced its
active vertical list with a static preview during nested scrolling. The settled conversation
now stays mounted, with activity-owned list state per stable profile and explicit Wear focus
groups. Compact 48 dp chevrons flank the page count instead of truncated word buttons.
Prompt lists use independent state. Strict checks and debug/instrumentation builds pass;
both owned-emulator scenarios pass: touch scrolling/control interaction, cancelled drag,
profile return and metadata refresh; and three-profile draft isolation, recording/approval
locks, rotary input, arrows and ambient/setup recovery. Tests wait for visible UI changes
after reducer events and rotary input rather than relying on fixed render delays. The Wear
pager-wrapper experiment was removed after a post-approval gesture regression; the existing
foundation pager uses the documented custom Wear focus hierarchy. Physical update follows.

## Larger watch portraits and live device setup — 2026-10-12

The maintainer reports that the fresh hold-to-talk check passed on one profile from each
of the three boxes: visible transcript, answer and the profile's spoken voice. This is a
functional speech pass by maintainer report. Network/lock conditions, quantitative latency,
STT/codec quality, lip sync and battery were not separately measured. CI for code commit
`628de74` passed all Android, extensions and secret checks.

The maintainer requested circular portraits twice as large. Portraits now render at 160 dp
instead of 80 dp, within a 160 by 168 dp fixed artwork frame. The Ultra accessibility bounds
measure 340 by 340 pixels and the actual screenshot shows the whole round portrait. The
follow-up placement request reduces portrait-page top padding from 40 to 28 dp: measured
bounds move upward 25 pixels, centering the image at y=239 on the 480-pixel display. Names
and controls remain below it in the scrollable page; pet rendering retains its existing canvas.
Strict full checks and both debug builds pass with warnings as errors: 71 JVM tests, no
failures/errors/skips. The matching-signature watch update preserves all 19 identity
ciphertexts; its installed APK hash matches the local build. Personal art/screenshots are ignored.

All three sources were entered through the physical phone UI. All 18 supplied round
256 by 256 PNGs were chosen through the native picker, then verified against the actual
normalized phone cache and watch content hashes. All 17 new endpoints were reviewed on the
watch, approved through their correct profile-specific stock CLI, and observed Ready on
Phone relay with watch TLS. The existing root endpoint also reached Ready; the original two
identity ciphertexts remain unchanged. No identity or portrait cache was seeded.

A read-only phone diagnostic passed authenticated default-trust HTTPS to all three catalogs
on validated cellular/Tailscale with Wi-Fi off and USB debugging only. A profile that returned
a provider rate-limit error before the pause now returned its fresh exact marker. A second
fresh reply passed with the phone keyguard showing and no relay foreground service before
watch app startup. All 18 profiles returned fresh markers with the phone keyguard showing on the same cellular
TLS path: 12, four and two per source. A controlled outage of the owned Mac catalog job
retained the selected page, 19-page order and portrait; refresh after return restored source
availability. The exact job/routes and picture references were preserved. This exercises
catalog failure rather than whole-box power loss. Phone Wi-Fi and both original screen
timeouts are restored; root Automatic is verified. See [physical results](physical-results.md).
Do not infer physical speech, radio throughput, battery,
live optional pets/Opus, signing or Play acceptance from typed replies.


## Phone text contrast correction — 2026-10-11

The maintainer reported black text over the dark companion background. The shared activity
now uses a Material Surface with an explicit light content color, so profile, discovery and
speech instructions inherit readable text. Strict checks and the phone APK build passed;
the JVM reports contain 71 tests with zero failures/errors/skips. The matching-signature
update preserved existing app storage, and the maintainer confirmed the text is readable
on the physical phone. Portrait selection and live watch setup continue below.

## Portrait overrides and authorized live setup — 2026-10-11

The maintainer requested a private export of all portraits and an exact regeneration prompt,
box-imported pictures with a per-profile watch override, setup of the missing conversation
profiles, and publication instructions. The ignored pack contains 18 byte-identical originals,
a hash manifest and ZIP; the prompt specifies 1024×1024 RGBA masters and exact 256×256
RGBA watch PNGs, transparent round framing, safe face/headgear bounds, no text and a 256 KiB limit.

The phone's encrypted source/profile overrides retain the latest imported box portrait beneath
the chosen watch image. Native Picture options offers the system picker and Use box picture.
Tests cover refresh/offline/restart, overrides before URL setup, duplicated profile IDs across
boxes, withdrawal cleanup, returning to the latest box image and unchanged endpoint setup.
Strict full checks/debug APK builds pass with warnings as errors; 71 JVM tests and all 12
owned API 37 phone storage/repository/portrait instrumentation tests pass. The live local
catalog and picture-choice dialog were also exercised through native phone UI.

The requested setup authorizes the previously prepared live rollout. Three independent HTTPS
catalogs now serve 12, four and two profiles and all 18 authenticated portraits; unauthenticated
reads return 401 and unknown assets 404. Every stock SDK v0.2.0 profile now has its own listener
and verified explicit WSS mapping. All 18 loopback upgrades and default-trust WSS upgrades from
the respective boxes pass; this is transport evidence, not enrollment or physical speech.
The root macOS managed Gadget pin was scoped to the default profile, and the Windows managed
plugin allow-lists now include Gadget while preserving other entries. Normal Windows gateway
restarts drained cleanly. Existing default service/route and approved device records remain.

Catalog and conversation policy additions are restricted to the owner's exact box addresses
and ports, validated and published with the current ETag. Existing TLS routes remain and
Funnel stays off. Windows catalog state/assets have explicit owner SID permissions; jobs
start at login and continue while locked. All personal values, art, logs and rollback material
remain ignored. [Setup guide](profile-setup.md) explains what users need once per box and
once per profile, managed settings, TLS/access, catalogs, pictures and independent pairing.

Demo note: source entry, all 18 round overrides and real watch delivery now pass, as recorded
in the 2026-10-12 continuation above. All 17 new endpoints completed normal watch review and
profile-specific approval. Fresh locked-phone cellular conversation startup also passed.
Controlled catalog outage/return now passes. Whole-box power transitions, physical
speech/pets, battery and publication gates remain open.

## Approved multi-box carousel — 2026-10-11

The maintainer requests horizontal swipe pages for profiles across three intermittently
available boxes, showing profile pictures first and pets/additional animations as they
become available. The [design proposal](superpowers/specs/2026-10-11-multi-box-profile-carousel-design.md)
was approved alongside PRD/architecture/pet updates. Discovery uses
read-only profile/avatar metadata while conversation pairing remains independent per
Gadget URL. App and isolated publisher implementation is now software verified on
`feature/multi-box-profile-carousel`; live deployment and real-box acceptance remain open.

The phone stores three HTTPS sources and catalog-only credentials encrypted, independently
merges bounded responses, retains offline/withdrawn cards and owner ordering, and syncs
normalized content-addressed portraits. Source edits invalidate stale requests. Manual
phone setup and photo selection remain available. The watch keeps approved setup and keys
separate from discovered metadata, displays stable swipe pages, preserves endpoint drafts,
and requires review for new URLs. Selected foreground pets crossfade into a fixed artwork
area; cached neighbors/offline pages stay static. Activity-owned review/page memory survives
ambient composition replacement. Setup capacity is 32 without changing existing key records.

Validation: strict full checks/builds and both debug/instrumentation APKs passed with warnings
as errors; 69 JVM tests (57 protocol, ten phone, two watch), zero failures/errors/skips.
Six isolated publisher Python tests passed. Three real TLS HTTP fixtures verify credential
scope, default-trust refusal, independent auth/offline states and stable withdrawal/return.
Three stock SDK processes verify independent pairing/reconnect. Owned API 37 instrumentation
passed phone vault/photo checks, watch cache/label enrollment preservation, actual swipe,
accessible Next, vertical/rotary scrolling, recording/approval switch protection, separate
drafts/identities, actual ambient/wake review restoration, fixed artwork placement and
selected-only movement. Original draft/profile/lifecycle and companion/base-only pet
regressions passed. The publisher suite is included in CI. The fresh branch review identified eight important
orchestration/retention issues; each now has a regression observed failing then passing.
[Review resolutions](carousel-review.md) records startup ordering, stationary artwork,
source conflict isolation/partial publication, TLS handshake deadlines, replicated portrait
cleanup, ingestion quotas, owner fallback persistence and confirmed withdrawn-card retirement.
The related manual-ID changed-URL downgrade is also covered. Final phone storage/repository
instrumentation passes 8/8; startup, stationary portrait, changed URL, companion carousel and
original profile switching each pass on the owned watch emulator. Both local APK signatures
verify against the existing local debug certificate.

Demo note: [three-source original artwork and actual emulator captures](demo/carousel/README.md).
The new catalog Data/Channel Layer path has not yet been exercised between real paired
phone/watch devices; the carousel test seeds an isolated watch cache. Private read-only
inventories and proposed per-box mappings/installation/rollback notes are prepared under
ignored maintainer storage. Only the previously approved conversation URL is mapped; other
URLs and owner artwork remain review steps. No live service, personal artwork, credentials,
ACL/Serve policy or existing enrollment was changed. Physical three-box TLS relay, source
loss/recovery, background delivery, artwork/lip sync and battery remain acceptance gates.

## Requested carousel PR review follow-up — 2026-10-11

The maintainer-requested review of draft PR #1 found four additional Important defects.
Each was reproduced then fixed: oversized restored roster and combined manual-setup
capacity; independent catalog admission/execution alongside relay and speech; an absolute
TLS/HTTP publisher deadline; and owner-confirmed fallback-photo retirement with preserved
saved/offline references. [Resolutions](carousel-review.md) and the
[follow-up plan](superpowers/plans/2026-10-11-carousel-review-followup.md) record the scope.
Strict full checks/builds pass with warnings as errors: 71 JVM tests, zero failures/errors/
skips; seven publisher tests; ten owned-phone storage/repository instrumentation tests.
Owned-watch overflow recovery, manual setup at capacity and the original three-page
swipe/draft/recording/approval regression pass independently against synthetic SDK servers.

The authorized physical pass now installed the matching-signature review-fix build on both
personal devices. After the maintainer supplied a refreshed local debugging address, an
isolated ADB server connected without interrupting phone USB debugging. The installed watch
APK hash matches the local artifact; both existing identity ciphertexts remain unchanged.
The watch received the phone's manual TLS profile card, swiped between its two approved
profiles, retained selection through sleep/wake and restored an unsent TLS draft after a
profile round trip. One explicit typed request received its exact real-server reply through
Phone relay with watch-owned TLS. The phone's denied API 37 local-network permission was
restored through the companion enable flow. Automatic transport was verified by reopening
settings; the original 15-second watch timeout is restored and the submitted test draft is
cleared. See the [physical report](physical-results.md). Live three-box catalog/portrait
delivery, manual vertical/rotary usability and the remaining release matrix stay open.
No new endpoint pairing or live catalog deployment was performed.

## Live catalog rollout preparation — 2026-10-11

The [rollout preparation plan](superpowers/plans/2026-10-11-catalog-rollout-review.md)
now has three concrete private source packages: explicit stable-ID mappings, references to
existing portraits, hash-checked offline Pillow wheels, a dedicated credential generated
only at installation, owner-context job definitions and guarded publication/removal controls.
The proposed catalog-only tailnet grant passes the provider's validation endpoint; it has
not been applied. Current listener/Serve observations are recorded privately, and publication
refuses drift instead of replacing existing routes. New conversation URLs remain unmapped.

Validation: five private package tests pass with warnings as errors, including actual
isolated macOS installation, authenticated catalog/portrait reads, launchd startup and removal,
input preservation, and refusal of tampered packages, existing directories, occupied ports or
changed publication configuration. The isolated job/LaunchAgent was removed and its port
released. Both proposed Windows definitions parse on their respective hosts with all required
task cmdlets available; actual Windows job startup remains untested. Seven publisher tests
pass. All private portrait exports meet dimension/length/hash/metadata limits. Source
profile/config/portrait hashes and Serve configuration are unchanged after preparation.

Demo note: the private portrait review is rendered in the implementation thread and stored
with the ignored rollout packages. It is an export preview, not a real-watch artwork result.
No live catalog, policy, Gadget configuration, enrollment or optional SDK patch was changed.
Next is the approved design's concrete live publication/artwork review, followed by real
phone/watch catalog delivery and source loss/return. Setup-needed profiles still require
independently reviewed Gadget endpoints and pairing; speech/battery/Play gates remain open.

## Repository bootstrap — 2026-10-05

Prepared a public MIT repository with a README, the project brief, project-specific agent instructions, and development/secret ignores. The brief's maintainer-only section is stored locally under the ignored `.local/` directory.

Demo note at bootstrap: documentation only. Runnable starter artifacts were added in M0 below.

Validation at bootstrap: staged whitespace checks passed, local documentation links resolve, credential/address pattern checks passed, and Git excludes the maintainer notes, local configuration, signing keys, and build outputs.

## M0 foundation — 2026-10-05

Read and pinned the Gadget SDK protocol/integration/porting/architecture sources and relevant implementation/vector tests before coding. Verified the pet generator, atlas, constants, store, and renderer against a pinned Hermes snapshot and a read-only local geometry survey. Wrote the PRD, architecture, pet corrections, upstream proposal index, and self-host TLS guide.

Added `protocol`, `wear`, and `mobile` Gradle modules, a checksummed Gradle 9.4.1 wrapper, version catalog, matching application IDs, and Compose starter screens. Cleartext is denied and all backup domains are excluded. CI builds both apps, runs checks with warnings treated as errors, scans secrets, and uploads debug APKs.

Demo note: generated `wear-debug.apk` (28,640,892 bytes) and `mobile-debug.apk` (29,559,076 bytes), containing the setup/intro screens. These artifacts have not been launched on an emulator or physical device, and conversation controls are not implemented yet.

Local validation: `./gradlew ktlintFormat check :wear:assembleDebug :mobile:assembleDebug --warning-mode=fail` passed with the devserver environment variable set. No Kotlin, Gradle, or Android lint warnings remained. Both Android host unit-test tasks have no source tests at this starter stage. APK production does not establish round-screen readability or device behavior.

Public-tree validation: gitleaks 8.30.1 found no leaks in the staged export; private-address/tailnet checks and local documentation-link checks passed. Maintainer notes, SDK location, and both APK outputs remain ignored. Git attributes normalize source line endings while preserving Windows wrapper checkout compatibility.

[GitHub CI run](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37379033226) passed the Android build/check job and secret-scan job for commit `9e9b696`, with both debug APKs uploaded. M0 is complete; emulator and physical-device evidence remain later gates.

## M1 stock protocol — 2026-10-05

Implemented defensive device identities, SHA-256 IDs, enrollment Base64, authentication/OTA HMAC, little-endian binary framing, bounded JSON envelopes, PCM negotiation, ordered handshake/pairing state, handshake timeout, heartbeat echo, and liveness. Tests cover upstream vectors, malformed and out-of-order input, unknown messages/channels, size/nesting limits, key redaction, and pairing revocation.

Demo note: a JVM client starts the actual unmodified SDK devserver with isolated ephemeral state, enrolls and approves a synthetic identity, receives `You said: hello café`, uploads 0.4 seconds of synthetic 16 kHz PCM and verifies byte-identical paced playback, then reconnects with HMAC and receives `You said: reconnected`. Server output, runtime addresses, and identity material are discarded from reports.

Local validation: 13 protocol tests plus one live SDK integration test passed, zero failures/errors/skips. `.local/venv/bin/python -m pytest .local/upstream/hermes-gadget-sdk/tests/test_protocol.py -q` passed all four upstream Python tests. SDK commit: `323e3303ab68981f810fc3208119cd8a22e64af0`.

The integration test requires `HERMES_GADGET_PYTHON`; without it, that test is explicitly skipped. Its enabled state is a Gradle test input: changing from an absent variable to a configured devserver was verified to rerun the test and pass with zero skips. CI installs the pinned server and sets the variable. No full Hermes gateway, physical microphone/playback, or phone Data Layer evidence is claimed by M1.

M1's protocol and live devserver checks also passed in the CI run linked above. M1 is complete; M2 is the first real-Hermes and watch-hardware conversation gate.

## M2 direct mode — 2026-10-05 — software verified, hardware gate open

The maintainer approved explicit LAN/tailnet cleartext with a per-endpoint warning. Implemented direct OkHttp WebSockets with normal TLS validation, private-address checks on every cleartext DNS resolution, no redirects/proxies/downgrade, generation-guarded callbacks, bounded work/audio queues, jittered reconnect, and no input replay. TLS, token, enrollment, and protocol errors require explicit correction. Ordinary inactivity disconnects after 90 seconds; a stalled turn/prompt has a 120-second inactivity cap.

Added a pure JVM conversation reducer for cumulative replies, turn matching, guarded exactly-once prompt answers, cards and RGB565 transfers, 16 kHz PCM upload/playback ordering, capture cancellation, and bounded action execution. Recording tokens guard both PCM data and failure callbacks, so an old backend cannot cancel a newer hold. The watch now has encrypted atomic endpoint/key storage under Android Keystore, memory-only media, bound-service lifecycle, runtime permissions, typed input, hold/release/swipe gestures, a live meter, two-second Cancel hold, and platform actions. Fixed an activity-stop path that could discard unsaved drafts by retaining the binding until activity destruction; the emulator regression covers background/return. Typed entry starts a bounded conversation service before opening the full-screen keyboard. Standalone metadata is now true following the direct emulator demo.

Local build gate: `./gradlew ktlintFormat check :wear:assembleDebug :wear:assembleDebugAndroidTest :mobile:assembleDebug --warning-mode=fail --max-workers=4` passed with the pinned devserver environment configured. All 29 JVM tests passed with zero failures/errors/skips: 13 original protocol tests, 10 conversation/action/media tests, four endpoint-policy tests, and two actual SDK integration tests. The new live test uses the stock hub's native TLS hook with its unmodified echo delegate, rejects an untrusted certificate, and validates pairing, typed replies, exact PCM loopback, prompt answer, card, image, server receipt of an action result, cancellation, HMAC reconnect, idle closure, and a new user connection after idle. Production trust settings are unchanged.

Three Android Keystore instrumentation tests and one opt-in setup UI instrumentation test passed on a 480×480 round Wear OS 7 / API 37 ARM64 emulator. CI compiles the instrumentation APK; emulator execution remains a separately recorded local check. The approved dynamic cleartext platform policy has one targeted `InsecureBaseConfiguration` XML suppression, justified by the per-endpoint connector gate. No other lint, Kotlin, or Gradle warnings remained.

Demo note: see [synthetic emulator screenshots and reproduction](demo/m2/README.md). The app paired to the real stock devserver, typed `Hi` through Wear's keyboard, received `You said: Hi`, captured and looped back emulator PCM, required a fresh gesture after mic permission, displayed prompts/cards/images, discarded a recording by downward swipe, and started a new session with a two-second Cancel hold. SDK output confirmed successful vibration, brightness, and notification results. Notification denial omitted its action; grant on reconnect added it. No timer-handler app exists on this emulator, so timer advertisement was correctly omitted. The emulator also reached Offline with zero foreground services after inactivity. Host microphone/audio were disabled; these checks do not measure physical microphone, speaker, STT, or vibration quality.

[GitHub CI for the final code commit](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37392054562) passed the Android build/test/lint/format job and secret-scan job for `ea5f3c0`. Both debug APKs were uploaded. The initial M2 implementation also passed [CI](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37391714134) for `aaa2e3a`. Runner migration / Node-action deprecation annotations remain separate from the strict application checks. Local staged-export gitleaks and personal-address/document-link checks passed.

Physical demo — 2026-10-06: wireless ADB pairing/connection succeeded with the maintainer's watch. Verified Samsung model `SM-L705U`, Android 16 / API 36, watch hardware, and its 480×480 display. The maintainer explicitly approved Hermes enrollment; the exact watch-specific pairing was approved on the supplied gateway. The watch reached Ready and subsequently reconnected using its saved identity without another approval. Installed the updated M2 debug APK with its encrypted setup preserved. Through Samsung's full-screen keyboard, entered `Reply exactly WATCH M2 OK, without tools.`, closed the keyboard with Done, and sent the message. Accessibility inspection showed the streamed reply including `WATCH M2 OK`; read-only gateway queries confirmed one matching user message and one exact final assistant reply. Physical captures and setup remain ignored. This proves typed input via automation and a real gateway round trip, not manual typing, physical audio, or all action behavior.

The physical test exposed a draft-loss bug: the connection could idle out while the keyboard was open, then Send cleared the draft despite the service rejecting it. The editor now retains endpoint-scoped in-memory drafts, shows an explicit Connect control when offline, and clears the draft only after the active WebSocket accepts the text frame. Reconnect never resends input automatically. Local acceptance is not a server delivery acknowledgement. Submission futures also resolve false when stopped or rejected by the bounded queue.

Updated validation: the strict full build/check command above passed again after the fix and final test changes. All 31 JVM tests passed with zero failures/errors/skips, including the enabled stock SDK tests and two new queued-submission rejection/close regressions. The opt-in draft UI regression passed on the owned round API 37 emulator with isolated stock SDK state: draft retained after disconnect, Send disabled offline, Connect inside the editor, draft retained after reconnect, no automatic replay, and a fresh Send producing the echo reply. It required visible editor nodes and fresh accessibility state after lazy-list updates. No identity-reset instrumentation ran on the personal watch. The physical display timeout was temporarily extended for the automated text demo and restored to its original value afterward.

The core physical M2 voice/STT/reply demo passed by maintainer report below. The remaining Ultra media/action, permission, lifecycle, and quality matrix remains open. Phase implementation advances in order under the maintainer's explicit authorization; incomplete hardware acceptance is recorded separately.

## M3 phone relay — 2026-10-06 — software verified, hardware gate open

Resolved relay trust: the maintainer requires watch-owned TLS through an opaque phone relay, with direct fallback under the configured endpoint's policy. Implemented bounded ChannelClient streams, an authenticated watch loopback CONNECT bridge, normal OkHttp TLS/hostname checks, same-package wearable capability discovery, Automatic/Phone/Direct selection, and same-endpoint network fallback. Certificate/authentication failures do not trigger fallback, and no input is replayed. The last reply is retained across transport replacement. The watch connects on app open and still idles out. The phone requires opt-in, provides Stop controls, uses a connected-device foreground service only for active channels, and offers Android companion association for background startup. No identity/key handling exists on the phone.

Local checks: the strict full build/check command passed with 38 JVM tests and zero failures/errors/skips. A live pinned SDK TLS test pairs and reconnects through a simulated byte-forwarding phone; ciphertext capture contains neither the enrollment key nor the synthetic plaintext prompt. TLS trust rejection, header bounds, proxy authentication/authority, manual overrides, and fallback restrictions are covered. An API 37 Android TLS test verifies untrusted-certificate rejection and a trusted HTTPS exchange through the real loopback socket. The first rapid follow-up connection exposed asynchronous pump teardown; the bridge now waits up to one second for its bounded slot before rejecting a new connection. Test trust exists only in test code.

Maintainer infrastructure: enabled a tailnet-only HTTPS proxy on the existing allowed Gadget port, with Funnel disabled and the backend unchanged. A default-trust TLS WebSocket upgrade negotiated `hermes-gadget.v1`; no enrollment or conversation was sent. The live policy already contained the own-device Gadget grant, with no Gadget port in the box-to-box grant, so no ACL edit was made. Configuration and verification files are ignored. This host probe does not prove the phone's Android VPN or off-network reachability. A new TLS URL receives an independent endpoint identity; the personal watch's saved LAN setup is preserved until the maintainer selects it.

Demo note: JVM relay pairing/text/reconnect and Android loopback TLS passed; actual ChannelClient, companion association, background foreground-service startup, Bluetooth interruption, phone Tailscale VPN, and off-network conversation remain hardware gates. M3 acceptance is not complete. No server extensions have been installed into the running Hermes environment.

## M4 Opus — 2026-10-06 — software verified, Ultra measurements open

Implemented optional per-direction `welcome.audio` selection, missing-selection PCM fallback, RFC OpusHead configuration separate from packets, Android unified CSD parsing, 20 ms packet validation, codec token guards, bounded capture/playback, and an actual local codec probe before advertising Opus. Capture release flushes the encoder before `audio.end`; cancellation discards it. Android decodes Opus at 48 kHz for AudioTrack. No mid-stream format switch or input replay occurs.

Prepared SDK patch 1 against the exact v0.2.0 base on local branch `wear/optional-extensions`, commit `9c400f9`, with SDK protocol documentation and tests. `enable_opus` defaults false and requires ffmpeg Opus support. Uplink wraps packets in CRC-protected Ogg and decodes off the event loop into stock STT WAV. Downlink uses a persistent bounded ffmpeg pipe, demuxes raw packets and enforces the playback lead. Elapsed-time testing caught a copied pacing-clock sign error; the optional Opus path now rebases correctly. Provider-direct Opus is not implemented. Nothing was installed into live Hermes.

Validation: strict build/check passed with 42 JVM tests and zero failures/errors/skips, including enabled unmodified SDK direct/relay integrations and four new negotiation/header/token/packet tests. The isolated exported patch series applies cleanly and its 21 Python tests pass, including real ffmpeg, live WebSocket enrollment/Opus loopback, stock extensions-off behavior, malformed input, sequence rejection, unpaired rejection, and elapsed pacing. Two Android codec tests plus the relay TLS regression passed on the owned round API 37 emulator. Android-to-ffmpeg and ffmpeg-to-Android packet decoding both passed. The stock SDK offline-draft UI regression passed after the capture changes. The opt-in service-level emulator demo passed negotiated Opus capture, flush on release, SDK reply/loopback playback path, and cancellation. Host microphone/speaker were disabled. No personal watch was reset or modified.

Demo note: [measured synthetic payload table](audio-measurements.md) records PCM 256 kbit/s versus Android Opus 23.584 kbit/s uplink and host ffmpeg 36.264 kbit/s downlink for one original three-second tone. This is not Bluetooth throughput, end-to-end latency, actual speech/STT accuracy, or Ultra battery evidence. M4 acceptance remains open for the required actual-device comparison. Continue M5 in order under the maintainer's advance authorization.

## M5 pets — 2026-10-06 — software verified, Ultra gate open

Implemented authenticated optional pet manifests/channel-4 PNG transfers with exact sequence/length/hash, dimension/compression/alpha limits and expiry. SDK patch 2 (`2bcdfc7`) resolves the current profile's active pet with confined paths, preserves base/legacy taxonomy, converts WebP to alpha PNG, trims blank tails and paces chunks. Missing pets/extensions do not block conversations. No running Hermes was changed.

The watch prepares pixel-scaled frames on a bounded worker, caches per endpoint/hash with an 8 MiB/64-file quota, maps state/legacy fallbacks, emits once-only turn cues and uses playback amplitude/hysteresis for talking or idle bob. An original procedural robot is the fallback. `tools/extend-pet.py` matches later rows to median idle height/center/baseline using shared row registration, rejects empty/clipped/drifting/extreme poses, and leaves the base and inputs unchanged. Same-hatch generation remains an upstream proposal.

Validation: the strict full build/check command passed; 46 JVM tests, zero failures/errors/skips. A fresh pinned checkout accepted the exported two-patch series and passed 29 Python SDK/tool tests. API 37 actual SDK→GadgetService pet transfer/cache/endpoint isolation and PNG corruption/bounds/alpha/blank-tail instrumentation passed. The stock SDK offline-draft/resend regression passed after the renderer changes. Personal-watch state remains untouched. Scope the patch whitespace attribute to preserve required unified-diff context prefixes; the inner SDK diff is whitespace-clean.

Demo note: [original sample on the round emulator](demo/m5/README.md), including cached rendering after fixture loss. Software checks do not establish personal-profile delivery, small-watch legibility, physical lip sync, or ambient/battery acceptance. Staged-export gitleaks, private-value and local-document-link checks passed. M5 hardware acceptance remains open. M4 commit `82a206b` [CI](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37468005261) passed all three jobs.

## M6 client speech — 2026-10-06 — software verified, provider/hardware gate open

Implemented opt-in phone-only shared/per-endpoint AES-GCM Keystore keys, voice overrides, read-only quota checks, readiness synchronization and a separate bounded ChannelClient speech stream. Watch relay readiness plus server acknowledgment is required; direct/stock paths use server voice. Native Ogg Opus is validated/demuxed without transcoding, or PCM16 is selected when the watch codec is unavailable. Cancellation closes the request/channel; synthesis is never automatically retried. The UI discloses reply-text/provider/billing access and blocks phone screenshots.

SDK patch 3 `8a1d854` adds optional acknowledgment, profile voice hint and bounded final `tts.speak`, suppresses automatic server TTS for those sessions and respects voice-off. A fresh pinned checkout accepted all three exported patches and passed 31 Python tests. Live Hermes remains unchanged.

Validation: strict full build/check, format/lint/warnings-as-errors, both app and instrumentation APKs passed; 53 JVM tests with zero failures/errors/skips. The live unmodified SDK and isolated extension negotiation test exposed a missing `tts.speak` dispatch; the fixed path now verifies stock-readable replies versus acknowledged client speech. TLS provider fixtures check quota/header, native Opus and PCM bytes/pacing, voice precedence, errors, redirects, cancellation and no paid retry. Phone API 37 Keystore tamper/missing-key/independent-selection instrumentation and watch API 37 native Opus decode passed on owned emulators.

Demo note: original 0.6-second tone and synthetic keys/provider responses only; no existing personal ElevenLabs key, paid synthesis, personal watch operation or real Data Layer exchange was used. Actual provider output, background delivery, billing and voice quality remain open. See [client speech evidence](client-speech.md) and [setup/disclosure](self-host.md). M5 `4f1d588` [CI](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37471127954) passed all jobs.

## M7 profiles — 2026-10-06 — v1 software verified, sync/hardware gate open

Implemented an encrypted eight-profile catalog with legacy active-record migration, independent URL keys, explicit switch/removal, endpoint pet isolation, and phone configuration/import review. Cleartext acceptance is renewed on watch import. Setup sync excludes device/ElevenLabs keys; an explicitly supplied access token is intentionally included. Removal retains identity/known markers until explicit whole reset, so re-adding cannot silently rotate a missing key. Profile teardown serializes stock/client media against epoch changes and rejects late old-profile callbacks. No input is replayed.

Validation: strict full build/check and both app/instrumentation APKs passed; 56 JVM tests, zero failures/errors/skips. Two actual stock SDK processes independently pair/reconnect with distinct identities. Five Android watch-vault migration/key/tamper/bounds/crash-recovery tests and one phone catalog encryption/tamper test passed on owned API 37 emulators. The actual watch service passed switching during recording, reply clearing, independent pairing/reconnect and removal against two synthetic stock servers.

Demo note: synthetic localhost profiles only; personal setup is unchanged. Actual Data Layer setup import, rotary/horizontal-swipe interaction and personal-profile pet/voice remain hardware gates. [V1 usage and v2 proposal](profiles-v2.md) document the gateway boundaries. V2 is not implemented or verified; explicit DECISION and upstream feedback are still required. M6 `ac329d1` [CI](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37476564998) passed all jobs.

## M8 software and release preparation — 2026-10-06

Implemented a local-only launch Tile with an encrypted 120-character last-reply excerpt,
a generic short-text complication, and Compose ambient mode with a sparse monochrome pet
outline and minute clock. Surface updates coalesce meaningfully at a two-second limit;
there is no background network polling or microphone initiation. Ambient observes pet changes,
not audio levels, stops capture and respects low-bit/system burn-in behavior. Unsent drafts
are service-owned memory, preserved across ambient/reconnect and cleared for a new endpoint.
The main conversation groups the pet/status and speaking hint compactly so typing is visible.

Foreground lifetime now follows protocol turn activity and actual playback drain, including
pending phone speech. Idle ready screens release the service. Playback callbacks are guarded
against an older stream clearing a newer stream's state. Whole Reset clears pet storage;
removing the active profile clears its encrypted surface excerpt without rotating enrollment.

Validation: strict full checks, both debug/instrumentation APK builds and both release bundles
passed; 56 JVM tests, zero failures/errors/skips. Eleven owned-watch instrumentation tests
passed across six vault/surface-storage checks, two outline/Tile/complication checks, one actual
stock-SDK foreground/ambient-capture check, the offline-draft regression and actual two-profile
media/switch/reconnect/removal. The draft regression passed again on the final compact layout.
Actual system Tile render/tap, process-death cached excerpt, sleep/ambient/wake and default/1.3
font captures passed. See the [synthetic demo](demo/m8/README.md). A complication provider
payload is verified; selecting it on a physical watch face is not yet verified.

Both unsigned release app bundles passed official bundletool 1.18.3 validation and manifest
inspection: matching package ID, distinct Wear/phone version codes, target API 37, correct
minimum APIs, Wear standalone=true, no debug flag or signing records. CI now builds/uploads
unsigned review bundles separately from debug APKs. Optional private signing configuration
is ignored; no key was created and no Play upload was made. Original 512×512 icon and
1024×500 feature graphic, privacy/Data Safety/listing drafts, setup/Ultra button instructions,
and a bounded reconnect-outbox proposal are prepared.

Demo note: only owned synthetic emulators and stock test servers were operated; the personal
watch and live Hermes remain unchanged. Ultra battery/PCM-versus-Opus speech/quality, real
Data Layer/VPN/background relay and import, paid provider output/billing, physical complication
and button mapping, publisher/privacy hosting, signing and Play internal-track installation
remain acceptance gates. M7 `009b174` [CI](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37478273116) passed all jobs.

## Milestones

Physical continuation — 2026-10-06–07: local app updates preserved the original watch
identity. Real direct and forced Phone TLS replies, phone setup import, independent TLS
enrollment and fresh locked-phone background startup passed. The companion request now
shows a device list; the installed phone correction retains one matching watch association.
Twelve safe Ultra instrumentation checks initially passed ten and failed two Opus burst
decoder cases. The bounded output-draining correction is installed; all 12 Ultra checks
now pass with both personal identity ciphertexts unchanged. Strict checks, 56 JVM tests,
four owned API 37 emulator regressions and both validated unsigned bundles also pass.
A fresh controlled cellular/VPN Phone TLS request passed with phone Wi-Fi off and no USB
tether function. Phone Disable/Enable preserved the unsent draft, but bounded failure/reconnect remains
open. Both screen timeouts and phone Wi-Fi were restored; relay is enabled and both
phone/watch TLS profiles are saved as Automatic. Reopening watch server settings verified
the saved preference; both identity ciphertexts were unchanged and the original watch
screen timeout was verified restored. An earlier idle app restart cleared the transient
test draft/screen without changing those identities. `e9bfbce` [CI](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37573623286) passed all three jobs. See the [sanitized physical report](physical-results.md).
Hardware speech, radio throughput, battery and Play acceptance remain open.

Continuation — 2026-10-06: the maintainer again confirms physical hold-to-talk, visible
transcription, command/tool activity, a reply and the configured ElevenLabs server voice.
The reported interruption was T3's Claude authentication, resolved by the maintainer with
`claude auth login`; it was not reported as a watch enrollment problem. A fresh host-side
probe passed default-trust certificate/hostname validation and the Gadget WebSocket
subprotocol upgrade without sending enrollment or conversation. The devices are home,
but the saved watch ADB connection is unavailable; current watch connection details and
phone USB debugging were requested. [Physical acceptance steps](device-testing.md) are
prepared. No new relay, BYOK, Opus, action or battery acceptance is inferred from this report.

Maintainer voice demo — 2026-10-06: the maintainer reports that the physical watch successfully asked about the weather, received an answer, and played the profile's ElevenLabs voice. This verifies a real hold-to-talk/STT/reply/playback path by maintainer report; no bitrate, STT comparison, battery, or audio-quality measurement is inferred. They described the flow as clunky and authorized advancing the remaining phases while away from the home network. The core M2 conversation demo has passed; the remaining physical media/action, permission, lifecycle, and quality matrix stays open. Phase implementation may advance in sequence under that authorization, with unverified milestone acceptance kept explicit.

| Milestone | Status |
| --- | --- |
| M0: Gradle skeleton, CI, PRD, architecture, pet analysis | Complete; local and CI checks passed |
| M1: Protocol vectors and devserver integration | Complete; local and CI checks passed |
| M2: Watch direct mode and real Hermes demo | Core physical typed/voice demo passed; remaining Ultra matrix open |
| M3: Phone relay and transport selection | Real TLS Data Layer/background/cellular VPN passed; radio/interruption gates open |
| M4: Opus and measurements | Software/cross-codec and Ultra synthetic codec verified; speech/radio/battery gates open |
| M5: Pets and companion-sheet tooling | Software/emulator verified; real profile/Ultra gate open |
| M6: Client BYOK TTS | Software/emulator verified; provider/Data Layer gate open |
| M7: Multi-endpoint profiles and v2 proposal | V1 software/emulator and physical phone import verified; remaining hardware open; v2 proposal only |
| M8: Tile, complication, ambient, battery, internal testing | Software/emulator and unsigned bundles verified; Ultra battery/signing/Play gates open |

Next: execute the real-device and publisher checklist in [release preparation](release.md). Keep untested hardware gates open and record each new physical observation separately. Relay TLS and direct cleartext decisions are resolved. Multiplexed gateway v2 remains proposal-only pending decision and upstream feedback.


## Recording cues and independent phone client checkpoint — 2026-10-12

Shared `client` Android adapters replace the watch-only PCM/MediaCodec classes; protocol stays
Android-free. Recording starts before the readiness cue, queued cue-time audio is discarded,
and finish/cancel cues follow recorder release. Permission failure emits no success cue.
Sound follows notification volume, silent/DND policy and the explicit synchronized preference;
short haptics remain available. Old capture completion cannot publish after replacement.

Six owned-Wear capture/codec tests pass. Replacement regression first failed with the guard
removed, then passed with it restored. Strict check/lint/build passes with warnings as errors;
physical cue audibility, tone contamination and speech quality remain owner observation gates.
The watch scroll/control update was installed with matching certificates and preserve-data
hash checks: 19 identities and 18 portrait files unchanged, scrolling stable, Server settings
reachable, original timeout restored. Private backups/receipts are in `.local/client-usability/`.

Phone client integration uses a new encrypted phone identity namespace and foreground-only
DirectConnection. Two isolated stock TLS server/vault tests pass: explicit Connect pairing,
separate identities/replies/drafts, authenticated reconnect, text, PCM capture, and shutdown
without changing the relay setting. It does not auto-enroll live profiles. Final UI/storage
regressions, actual phone voice observation and bounded GIF work remain in progress.


Phone organization and original QuantumInk Relay branding are committed. The directory
migration/reorder/empty-box/restart/override tests and prior repository tests pass: 13/13
owned-phone tests. An observed dormant-manual/discovered-picture URL collision was fixed by
keeping keyed picture records URL-free; encryption and artwork remain unchanged. Stock phone
acceptance reran successfully with a fresh audio-upload response. The shared-audio watch
carousel interaction scenario passed in 13.874 seconds. [Setup instructions](profile-setup.md)
now cover independent phone pairing and foreground Talk/Chat. Physical phone STT/voice,
full UI acceptance and animated-picture checks remain separate gates.


## Approved native appearance checkpoint — 2026-10-12

The maintainer approved Portrait Studio, original Voice Q, three independent appearances,
the watch picker without Previous/Next and one/four-profile tiles. Shared tokens, durable
appearance preferences, native phone/Wear adapters and original icon assets are committed
(c3381bd). Strict full check/lint/build passes with warnings as errors: 107 JVM tests and
11 owned-emulator appearance checks pass. Actual Light Scaffold/time/system-bar contrast
regressions were reproduced before correction. Task-scoped review is running; complete
native phone/watch layouts and tiles are subsequent tasks, not current physical evidence.

Authorized physical phone enrollment paired eighteen profiles through normal reviewed
stock approvals: twelve M5 Max, four GPU 5090 and two Win 11 ARM. All three default
profiles returned fresh typed markers; the other fifteen had enrollment-only checks.
GPU enrollment passed after the owner woke the box. Existing identity/art/preference hashes are unchanged; catalog pages refreshed and new
independent phone identities were added. Original five-minute timeout is restored. Real
phone speech/voice remains owner observation; prior watch speech is not substituted.
The owner sees phone transcripts/tools/replies but expects ElevenLabs voice; a read-only
audio probe found speaker media volume 0/15. Owner asked to raise media volume and retry.
Real capture/playback waveforms with bounded levels, lifecycle guards, reduced-motion
fallback and a muted-media hint are added to the approved Task 3 presentation work.

Demo note: owned native theme chooser screenshots and actual contrast/persistence checks
pass. Physical devices retain the previous preserving debug build; no server extension,
power helper, release signature, merge or Play publication was performed.


Native directory checkpoint — 2026-10-12: Task 1 review findings are resolved in838351c,
with14 affected native tests, strict affected checks and debug-only host release exclusion.
Scoped re-review is clean. Task 2 portrait rows, box groups, accessible options and native
brand/navigation are committed0696683. Nineteen native and13 mobile JVM checks pass; the
final directory rerun passes11 cases. Actual320dp/font1.8 native menu/details/vertical
reachability checks pass with system settings restored. Scoped review is running; physical
devices retain their prior debug build and later native conversation/watch/tile tasks remain.


Native phone conversation checkpoint — 2026-10-12: f8de6c3 implements compact profile
toolbar, connection details, reading/composer/voice controls, actual capture/playback
waveforms, muted-media hint and grouped relay/settings/setup. Strict mobile checks
pass; 13 JVM, five actual stock TLS/playback, five presentation, eight directory and
seven appearance cases pass. Actual320dp/font1.8 all-theme keyboard/Send checks pass
with settings restored. Scoped review is running. Owner confirms prior installed phone
voice works after raising media volume; no server TTS/provider change was made.

The supplied eighteen watch-256 GIFs are validated and matched to their profiles:
256x256, eight frames, six seconds, transparent corners and maximum47,528 bytes. Native
picker import/delivery is in progress; personal files/receipts stay ignored and originals
unchanged. No redesigned physical installation or final GIF acceptance is claimed yet.


Native watch picker checkpoint — 2026-10-12: Tasks 1–3 have clean scoped reviews.
Task 4 removes visual Previous/Next controls and adds the stable-key picker and bounded
page indicator. Review found a catalog-shrink crash; a failing native regression reproduced
it before correction in 3a75fd5. All three affected native tests and strict Wear checks pass,
including actual rotary input after dismissal. Scoped re-review is clean. Tile implementation,
final whole-branch review and preserving physical native delivery remain in progress.


Personal GIF verification — 2026-10-12: the maintainer completed the normal phone
picture assignments. All eighteen saved GIFs match every decoded source frame, and
the watch animation index associates each correct hash with its stable profile key.
The selected watch portrait shows visible motion; nearby artwork is fetched in bounded
groups of three. Existing identity/configuration bytes are preserved, temporary picker
copies removed, and both original screen timeouts restored. Personal art and captures
remain ignored. This verifies the prior installed build; native redesign delivery and
quantitative GIF battery measurements remain separate gates.


Profile tile checkpoint — 2026-10-12: eadcf42 adds bounded durable CAS pins, static
Focus/four-profile providers, local pin editing and stable-key cold/warm launch routing.
Six affected JVM and eighteen distinct native checks pass, including real activity/service
launches and recording/approval/busy deferral. Review found an unavailable-route ambient
regression; d44e968 fixes it with actual Light/Dark sleep/wake output coverage. Strict
Wear checks and scoped re-review pass. Phone pin configuration/synchronization and final
whole-branch review/delivery are next. Physical tile addition, taps and battery remain open.


Safe dog-walk checkpoint — 2026-10-12: all eighteen owner GIF assignments and stable
watch profile mappings are verified, selected-watch motion is visible, and both original
screen timeouts are restored. No physical operation or child task remains running.
Physical devices retain b724231, so walk observations exercise the existing GIF/relay
build; redesigned layouts and tiles await delivery.

Task 6 native phone tile configuration/sync is implemented at 5aa0d3b, with twenty-two
affected JVM tests, five native cases and strict affected checks passing. Scoped review
requires a fix for stale watch editor fields overwriting accepted phone edits. Resume
the original implementer for fix round 1 and a fresh scoped re-review before Task 7.
Actual associated Data Layer delivery remains open after the owned-emulator API_UNAVAILABLE
probe. No final native physical, battery or release acceptance is claimed.
