# Requested PR review follow-up

The maintainer requested a fresh PR review and testing on the already enrolled physical
watch. The review of `235ba3c` found four Important defects. Resolve these within the
approved carousel design; preserve stock compatibility, existing enrollment and signatures.

1. Add a failing watch regression for an oversized restored roster and manual setup at the
   combined 32-page limit. End startup loading on catalog rejection and validate capacity
   before creating enrollment. Verify recovery and existing profile access.
2. Add a failing phone admission regression for concurrent conversation relay, slow catalog
   refresh and speech. Give discovery its own bounded admission and executor while retaining
   combined idle/stop accounting. Verify cancellation releases capacity once.
3. Add a failing real TLS publisher regression with all four workers receiving slow headers.
   Enforce an absolute connection deadline covering TLS and request parsing; verify slots
   recover and an authorized request succeeds.
4. Add a failing phone repository regression for owner removal after repeated fallback-photo
   selection. Retire unreferenced fallback records/assets only on confirmed setup/card
   removal; retain pictures still referenced by cached source cards or saved setup.
5. Run strict checks, affected owned-emulator instrumentation and relevant regressions.
   Update review/progress/session evidence and the draft PR. Keep live rollout gates open.

Physical pass: use only the already approved endpoints. Back up encrypted enrollment and
verify the installed APK signature before updating. The maintainer approved extending the
watch screen timeout temporarily; restore its recorded original value at the end. Never
reset the personal watch or seed synthetic enrollment there. Record actual observations
separately from owned-emulator and maintainer-reported evidence. Live publisher deployment,
new URL pairing, credentials/artwork, battery and Play acceptance remain separate gates.
