# Phone messaging refinement review

The owner approved the [phone refinement](superpowers/specs/2026-10-14-phone-messaging-design.md)
and selected original Courier A. The phone has an inline text/voice composer, hold/release/
physical-left cancellation, actual microphone level history, a leading full-name header
and a shared picture sheet with a separate Cancel footer. Existing server voice playback,
profile identities, artwork precedence and widget configurations retain their owners.

## Independent review and one corrective pass

One fresh GPT-6 Astra reviewer inspected `1b1aead..822464c` read-only against the approved
spec, plan and implementation ledger. It found no Critical or Minor items and three
Important items; its verdict was With fixes. All three were accepted. The single corrective
pass is `ee2cd11`; there was no second review and no declined-to-judge item.

1. **Recorder startup after release.** A slow AudioRecord initialization could pass the
   old stopped check after Finish. `CaptureStartGateTest` first failed without the new
   gate. The adapter now serializes the actual hardware start against end/cancel; an
   attempt ended before startup is discarded. Callbacks run outside the gate lock.
2. **Accessible start failure left the composer holding.** The native failure regression
   reproduced the stuck Starting state. A terminal-attempt revision and error reconciliation
   return the composer to idle; permission completion never auto-starts recording. Queued
   CaptureStart effects after an ended intent are rejected. Native keyboard/accessibility
   paths cover Start, Finish, Cancel and granted/denied permission completion.
3. **Physical-left cancellation under RTL.** The native regression reproduced mirrored
   geometry that put the microphone against the left edge. Composer controls now retain
   physical order, with Cancel to the left and microphone to the right; text entry retains
   its locale direction. The same real leftward pointer gesture cancels without sending.

Negative and positive receipts are retained privately. Pointer tests assert one terminal
action through level recomposition, short presses, visible cancellation, release, pointer
cancellation and disconnect. The source protocol module is unchanged. Large-text acceptance also increased header
leading after a 320-dp/font-1.8 hierarchy failure; its corrected native assertion passes.

## Fixture diagnostics

An initial combined native run timed out during an existing withdrawal test. That fixture
wrote directly to the vault after a progressive OFFLINE publication while the repository's
refresh was still running. It now waits for the actual enabled UI action, indicating the
refresh has ended, before its direct test mutation. Repository behavior is unchanged.

The landscape picture test initially tried an offscreen row, then exposed a stale native
accessibility cache: the screenshot contained Orbit/Options while the queried lazy-list tree
omitted it. The fixture scrolls vertically and clears UiAutomation's virtual-node cache before
queries on supported test APIs. Direction filtering alone did not resolve it; that failed
receipt is retained. Picture and directory production behavior are unchanged.

The intentionally failing RTL assertion also left an injected pointer down. InputDispatcher
reported `Invalid DOWN event - pointers already down`. Fixture cleanup now sends a terminal
ACTION_CANCEL so an assertion cannot poison subsequent pointer tests. This is test cleanup,
separate from production gesture behavior. Failed receipts are retained, not counted as passes.

## Implementation decision

Courier density/round exports live in the art folder, while the supported API 29+ apps
package adaptive resources only. This avoids conflicting fallback resources and obsolete
qualifiers without changing the API floor or suppressing lint. Cost if wrong: a future
unsupported pre-26 consumer must copy the supplied density exports.

The compact mic uses a 56-dp target to align with the native text field, retaining the
approved inline placement and exceeding the 48-dp minimum. Cost if wrong: four additional
dp of width compared with the 52-dp mockup; native narrow-window acceptance covers the fit.

In very short windows, opening the keyboard temporarily hides the conversation toolbar
so the minimum composer remains above the IME. Cost if wrong: Back/details require first
dismissing the keyboard there. The landscape geometry assertion reproduced the overlap
before this adaptive layout correction in `d26e790`. Normal layouts retain the toolbar.

There are no deferred Minor findings. Physical speech/gesture comfort, away replies,
quantitative battery tests and release gates remain owner/device acceptance; synthetic
layout levels do not prove microphone or server audio. Stock TLS acceptance is recorded
separately from the layout gallery. Keep the existing PR draft.

## Final evidence and preserving delivery

Warning-strict checks and all four APK builds pass 133 JVM tests /38 reports, zero
failures/errors/skips. The final combined run passes 24 methods; three font/window replays
pass four methods each. Six actual stock TLS audio/vault methods bring the final total to
42 executions /30 distinct methods. Stock fixtures wait for a completed turn and an enabled
mic before interaction, dismiss only a present IME and read button bounds in the successful
query. Earlier idle/query timing failures remain in private receipts; no product change was
made for those fixture adjustments. All original emulator settings are restored.

[Thirty-nine original native PNGs](demo/phone-messaging/README.md) bind to source and APK
hashes. Courier's 13-file production-art manifest is hash-verified. New tracked text passes
the scoped private endpoint/key pattern check; personal artwork and delivery data stay ignored.

The physical phone has production build `d26e790`, installed without launching its UI. A
current APK/data backup, matching certificate and installed hash are verified. All 102
baseline files remain unchanged, including 18 identities and 54 artwork files; five-minute
timeout and all seven widget providers remain. The physical watch is unchanged. No new
pairing, reset, server change, merge or release is part of this delivery.
