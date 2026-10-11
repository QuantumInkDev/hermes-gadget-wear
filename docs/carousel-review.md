# Carousel review resolutions — 2026-10-11

A fresh read-only whole-branch reviewer found eight Important issues and no Critical or
Minor findings. The implementer reproduced and fixed them in one test-driven pass; no
second review round is claimed. Strict build/check/lint/format with warnings as errors
passes: 69 JVM tests, zero failures/errors/skips. The publisher passes six Python tests.
Owned API 37 phone storage/repository instrumentation passes eight tests. Watch regressions
below ran individually on the owned emulator with actual isolated SDK processes.

| Finding | Result and regression observed RED → GREEN |
| --- | --- |
| Startup replaced the saved catalog selection with the active endpoint | Restore roster and selection before enabling connection effects or remote refresh; `startupRestoresUnapprovedSelectionBeforeConnecting` |
| Same-key avatar changes left stationary artwork stale | Reconcile the current page metadata, not just keys; `stationaryPagePromotesRefreshedPortrait` |
| A duplicate URL or merged-limit conflict cancelled healthy discovery | Reject the offending source, retain its cards with Attention, continue healthy updates and publish partial rosters; `conflictingSourceDoesNotCancelHealthyDelayedUpdates`, `healthyPartialRosterReachesPublicationBeforeSlowBoxFinishes` |
| TLS accept could stall before installing the timeout | Bound workers and set socket deadlines before handshaking; `test_stalled_tls_handshake_does_not_block_authorized_catalog` |
| Historical portrait Data Items accumulated | After replacement metadata succeeds, delete only local-phone unreferenced portrait items; `replacementsAndRemovalDeleteOnlyLocalUnreferencedPortraitsAfterMetadata` also protects other nodes/namespaces and failed metadata |
| Failed source ingestion bypassed the image quota | Enforce the quota on every image promotion, protect committed fallback references, and clean failures; `repeatedUncommittedPortraitsRemainBoundedAndKeepProtectedFallback` |
| Refresh discarded chosen fallback pictures | Persist fallbacks separately and restore after portrait-less responses/restart, including immediate replacement; `ownerFallbackSurvivesPortraitlessRefreshAndRestart` |
| Retained withdrawals exhausted capacity | Offer owner-confirmed Forget withdrawn card, retaining independent endpoint setup; `confirmedRetirementFreesHistoricalCapacityWithoutChangingSetup` |

The related changed-URL manual-ID fallback was also reproduced and fixed:
`changedManualEndpointRequiresReviewWithoutOldConnection` verifies review, no old session,
and preserved page identity. Original switching, companion carousel, gestures, recording/
approval locks, separate drafts/identities and ambient setup restoration still pass.
The runner now requires a nonzero executed test count; `OK (0 tests)` is never acceptance.
A corrupt or timed-out source image retains the last committed phone portrait through
cleanup; `corruptSourceImageKeepsLastCommittedPortraitDuringCleanup` was also RED → GREEN.
Both rebuilt debug APK signatures verify against the existing local debug certificate.

Forgetting a withdrawn card removes its phone catalog history only. An independently saved
phone/watch connection can still appear as a manual card. At the combined watch capacity,
use the existing owner-controlled setup removal if retiring that connection too; its
URL-scoped identity/known markers remain until explicit full reset. No automatic cleanup
removes setup, changes a canonical URL, or rotates a pairing key.

## Decisions and remaining gates

- An isolated feature branch in the configured checkout substitutes for a second checkout,
  preserving private tooling and the thread binding. Cost if wrong: checkout isolation is
  weaker than a separate worktree.
- Live addresses, credentials, HTTPS/ACL and deployment remain private proposals for owner
  review. Cost if wrong: real-source availability stays unproven until approved rollout.
- Actual paired Data/Channel exchange and locked-phone catalog delivery remain a hardware
  gate. Seeded caches prove the carousel only. Cost if wrong: delivery defects can remain
  undiscovered until that test.
- Personal artwork and physical legibility require owner/small-watch review. Cost if wrong:
  actual art readability or registration may differ from synthetic fixtures.
- Paid speech, radio throughput, lip sync and battery remain their existing physical/provider
  gates. Cost if wrong: these tests do not establish voice quality or power use.
- Signing, Play and upstream acceptance remain release gates. This branch supplies debug
  apps and a draft application PR. Cost if wrong: shipping still requires release access and
  acceptance.

No deferred minors. No live service, Serve/ACL policy, personal artwork, enrollment record
or pairing key was changed. The [release gates](release.md) and ignored private rollout
package remain the next steps after this reviewable application change.
