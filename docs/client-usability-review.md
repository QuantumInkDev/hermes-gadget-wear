# Client usability and animated-picture review

Fresh whole-branch review, 2026-10-12: GPT-6-Astra, read-only, merge-base `be3a2ef` through
`97e9ccc` plus the pending animation integration. It found no Critical issue, six Important
issues and one Minor contract mismatch. The correction regrade is clean for all seven original
findings. It inspected source and regression tests; execution receipts were supplied evidence,
not independently rerun by the reviewer. Physical acceptance remains separate.

| Finding | Required correction and evidence |
|---|---|
| Phone playback completion versus hide lock inversion | Fixed: atomic completion never acquires the session monitor under the adapter monitor. Controlled actual playback/hide overlap fails before and passes after. |
| Stop voice disappears on playback start | Fixed: one player per guarded stream; an empty stop emits no completion. Actual server PCM playback keeps speaking true and explicit Stop clears it. |
| Short/automatic recording cue mismatch | Fixed: accepted reducer/transport outcome joins recorder shutdown. Tests cover short release, automatic finish, rejected sends, both arrival orders and stale tokens. |
| GIF replacement at full capacity | Fixed: one durable 256-KiB staging slot protects published assets until success. Full-32 failure/restart/retry and second-pending-import tests pass. |
| Selected unapproved GIF card stays static | Fixed: forward selected state. The production-carousel test observes both exact original GIF palette colors before endpoint enrollment. |
| Late retired portrait fetch consumes replacement slot | Fixed: promotion rechecks current roster/epoch atomically with retirement/reset; no lock during fetch. Delayed retirement at 32, same-key change and reset tests pass. |
| Ambient portrait fallback absent | Fixed: dim static PNG or sparse low-bit representation, pet priority and existing burn-in shift. Actual rendered output and source preservation pass. |

Local strict checks/builds pass with warnings as errors. JVM reports contain 105 tests and no
failures/errors/skips. Owned Android results include 19 phone storage/directory tests, three
stock TLS/vault/playback tests and 14 Wear animation/ambient/cache/capture/codec/surface tests.
The final three-profile carousel passed in 15.134 seconds and stationary vertical touch,
profile return, aborted drag and metadata refresh passed in 9.679 seconds.

The tiny original GIF is a checkerboard. Its enlarged center boundary blends palette colors;
the render regression samples an accessible image's source-pixel interior and requires both
exact palette values. A fast touch release also legitimately flings beyond a briefly visible
Type control; the stationary-position regression stops the drag before release. These are
fixture corrections, not production workarounds.

Physical phone STT/voice, cue audibility/contamination and GIF battery cost remain observation
gates. They are not inferred bugs or established by synthetic input and source review.
