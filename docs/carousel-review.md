# Carousel review resolutions — 2026-10-11

A fresh read-only whole-branch reviewer found eight Important issues and no Critical or
Minor findings. The implementer reproduced and fixed them in one test-driven pass. The
maintainer then requested an additional PR review; its four Important findings are resolved below. Strict
build/check/lint/format with warnings as errors passes: 71 JVM tests, zero failures/errors/
skips. The publisher passes seven Python tests. Owned API 37 phone storage/repository
instrumentation passes ten tests. Watch regressions below ran individually on the owned emulator with actual isolated SDK processes.

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

## Maintainer-requested PR review

The second read-only review examined `235ba3c` and found four Important issues, with no
Critical or Minor findings. Each defect was reproduced before its fix; no additional
review round is claimed. The follow-up is recorded in the
[implementation plan](superpowers/plans/2026-10-11-carousel-review-followup.md).

| Finding | Resolution and RED → GREEN regression |
| --- | --- |
| Restored catalog plus saved setup exceeded 32 pages and left startup loading forever | Reject oversize metadata while ending startup loading and preserving an actionable notice and editable setup; validate the combined roster before creating enrollment and reserve pending setup during sync; `oversizedRestoredCatalogLeavesSavedSetupUsable`, `manualSetupCannotExceedCombinedPageCapacity` |
| A slow catalog channel consumed a conversation/speech slot | Separate one bounded catalog slot/executor from two conversation slots; release each lease once and account for both kinds before ending the foreground service; `slowCatalogDoesNotPreventRelayAndSpeechAdmission`, `cancellationReleasesEachKindOnceAndKeepsForegroundForOtherWork` |
| Slow HTTP headers kept all publisher workers busy indefinitely | Apply an absolute eight-second connection deadline across TLS and headers, independent of incoming bytes; all four slow TLS clients expire and an authorized request succeeds; `test_slow_tls_headers_release_all_workers_at_absolute_deadline` |
| Confirmed setup/card removal never retired fallback records or their pictures | Retire fallbacks only when neither saved phone setup nor cached source cards reference them; remove unreferenced assets, preserve offline-source/manual references, and allow repeated replacement after restart; `confirmedSetupRemovalReclaimsFallbackCapacityAcrossRestart`, `sourceReferenceKeepsFallbackUntilConfirmedCardRetirement` |

The admission checks use the production limiter and do not contact a paid speech provider.
The watch tests use only the owned emulator and independent synthetic SDK servers.

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
