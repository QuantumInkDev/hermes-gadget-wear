# Native client review

The whole-branch review at `ccb2953` found four Important issues and one Minor, with no
Critical findings. One corrective wave implements the five resolutions in `c248b03` and
refreshes the native gallery/evidence in `6b00947`. The single scoped re-review verifies all five findings addressed, with no new
Critical/Important breakage. It independently checks current source/APK/receipt hashes and
existing XML/native outputs without rerunning suites. This is software review approval;
physical acceptance remains separate.

| Finding | Correction | Behavioral evidence |
|---|---|---|
| Catalog refresh interrupts an active turn | Service defers the latest catalog while recording, replying or awaiting approval; the mounted carousel retains the approved endpoint and page projection. Unlock applies normal review/fallback behavior. | Changed URL, absent URL and removal across all three locks; service deferral while the interactive surface is absent; existing horizontal/vertical carousel checks. |
| Clock overlaps settled scrolled controls | Show the page clock only at the original first-view inset. Preserve the 160-dp portrait, 28-dp top and identity positions. | Initial/settled touch/rotary/reply/end checks in 18 theme/font/round-size scenarios; 72 refreshed native PNGs. |
| Phone Talk waits on blocking watch publication | Resolve profiles off-main with request/navigation/lifecycle guards; serialize immutable publication outside the repository monitor. Retain only the active publication's referenced media until completion, within existing cache limits. | Controlled blocked publisher, responsive actual MainActivity navigation, stale-click and stop/resume rejection, publication ordering, in-flight picture survival and eventual retirement, GIF capacity/pending/restart checks. |
| Sprite pets ignore motion policy | Share the observed foreground lifecycle, reduced-motion and power policy with GIFs; stop frame/mouth/bob animation and render a static frame when disallowed. | Same selected pet remains mounted while animator scale and observed lifecycle change; static frames/closed mouth and resumed motion asserted. |
| Directory context disappears after navigation | Retain search, collapsed boxes and list position across destinations; prune removed source state without resetting unrelated groups. | Actual Talk → Back and Settings → Back with searched/collapsed/scrolled profiles; source withdrawal/reintroduction. |

The final warning-strict run passes **116 JVM tests across 33 XML reports**, with zero
failures/errors/skips and both debug plus both instrumentation APKs built. Focused native
receipts record **34 passing executions / 32 distinct methods**, with no failures/skips in
the final receipts. The geometry method runs once per density and covers 18 scenarios;
scenarios are not counted as separate methods. Intermediate fixture failures and capability
skips are recorded separately. No unchanged broad native suite was repeated.

The [native demo](demo/native-clients/README.md), [verification](demo/native-clients/verification.json)
and [manifest](demo/native-clients/manifest.json) identify source/artifact/receipt hashes and
all 173 original synthetic captures. Unchanged images reuse their reviewed provenance.
Actual secure phone navigation uses native input/accessibility without a screenshot bypass.
An existing emulator's ambiguous data was preserved; a separately guarded clean stock
fixture supplied the real navigation evidence and was stopped afterward.

Physical matching-signature delivery is verified on both devices. Phone installation preserves
all 99 baseline files, including 18 identities and 18 GIFs; normal UI then preserves all
identities and 54 artwork files. Physical associated-watch snapshot readback matches existing
Focus revision 1, the empty grid and 19 profile keys. Associated phone edit acknowledgement/
system tile placement, owner speech/haptic/microphone observations, away reply/sleep receipt and quantitative
Light/GIF/audio/battery acceptance remain separate gates in [release preparation](release.md).
The owned Wear emulator cannot enable actual OS power saver; that capability is not passed.
The earlier associated-emulator Data Layer attempt returned API_UNAVAILABLE. Functional
policy/store/concurrency checks do not replace those physical or transport results.
