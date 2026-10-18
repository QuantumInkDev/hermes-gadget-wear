# Phone Quick Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking. This assigned implementation uses no child agents; the controller owns review.

**Goal:** Build editable, encrypted, exact-profile prompt actions with deliberate review and Android launcher shortcuts.

**Architecture:** Pure JVM models validate action records and expand literal `{{input_name}}` placeholders without recursive substitution. A private AES-GCM Android Keystore vault stores bounded ordered records. Native Compose pages manage and review actions; opaque shortcut IDs resolve current records and foreground PhoneConversation handles explicit Connect/Send.

**Tech Stack:** Kotlin/JVM, existing kotlinx JSON, Android API 29+, Compose Material3, Android ShortcutManager, Android Keystore.

## Global Constraints

- Preserve stock Hermes Gadget SDK `v0.2.0`; protocol has no Android imports.
- Reuse Courier branding and Light/Dark/Electronic Moonlight.
- No watch/server/other feature/root status/PRD/catalog changes; MainActivity is exclusively assigned here.
- No silent recording, pairing, sending, scheduling, Tasker or provider integration.
- Stable catalog keys, separate phone pairing, foreground lifetime and busy/approval locks remain authoritative.
- No physical devices, live endpoints/providers/messages/personal art; use an owned emulator only.
- Private action records contain IDs, stable profile keys, titles and templates, never enrollment/provider credentials. Shortcut intents contain opaque IDs only.
- Seed six editable unbound templates; require explicit profile and all inputs before review transfer.

---

### Task 1: Pure action model, templates and route validation

**Files:** Create `protocol/src/main/kotlin/dev/quantumink/hermesgadget/protocol/QuickActions.kt` and matching `protocol/src/test/kotlin/dev/quantumink/hermesgadget/protocol/QuickActionsTest.kt`.

**Interfaces:** Produces `QuickAction(id: String, profileKey: String?, title: String, template: String)`, `QuickActions.encode/decode/seeds/expand/inputs/resolve`. Resolve takes current `List<CatalogPage>` and returns the single non-withdrawn exact key; offline targets remain explicit. Limit 32 actions, 256 KiB encoded, 4096 template chars, 8192 expanded chars, eight inputs; IDs are UUIDs and profile keys match catalog key syntax.

- [x] Write failing JVM tests for missing profile/input, literal single-pass substitution, malformed placeholders, size/count/duplicate ID bounds, codec round-trip and duplicate-name/offline/withdrawn/deleted route isolation. Representative assertion: `assertNull(QuickActions.resolve(action, listOf(otherSameName)))`.
- [x] Run `./gradlew :protocol:test --tests '*QuickActionsTest'`; record RED (missing domain) in private report.
- [x] Implement immutable records, strict versioned JSON, input parser and six useful unbound seeds. Expansion uses `Regex.replace` with a callback, so supplied braces remain literal.
- [x] Rerun focused JVM test and then protocol suite; record GREEN.

### Task 2: Encrypted store and launcher integration

**Files:** Create `mobile/src/main/kotlin/dev/quantumink/hermesgadget/mobile/QuickActionStore.kt`, `QuickActionShortcuts.kt`, and `mobile/src/androidTest/kotlin/dev/quantumink/hermesgadget/mobile/QuickActionStoreTest.kt`.

**Interfaces:** Consumes `QuickActions` codec. Produces `QuickActionStore.load/save(actions, expected)` with atomic ordered-list writes, optional expected-list comparison and shared process lock; absence seeds once, corruption never resets. `QuickActionShortcuts.intent/parse/sync/pin` carries only UUID and routes explicitly to MainActivity. Sync limits dynamic publication to `maxShortcutCountPerActivity`, updates pinned current records and disables removed ones.

- [x] Add native tests for real Keystore restart/order/edit/delete, ciphertext privacy, tamper/oversize/missing-key fail-closed and opaque latest-record/deletion launches.
- [x] Run `./gradlew :mobile:compileDebugAndroidTestKotlin`; record RED for absent store/shortcuts.
- [x] Implement AES-256-GCM with authenticated version/context, 12-byte nonce, bounded reads and AtomicFile; no plaintext fallback or auto-reset on corruption.
- [x] Implement ShortcutManager API 25/26 methods compatible with API29; generic Courier icon, action title label, opaque intent data. Surface pin/rate-limit failures without claiming launcher placement.
- [x] Compile native tests and run on only the owned emulator; record GREEN and runtime API.

### Task 3: Native editor, management, review and guarded conversation integration

**Files:** Create `mobile/src/main/kotlin/dev/quantumink/hermesgadget/mobile/QuickActionsPage.kt`, `QuickActionReview.kt`, `QuickActionNavigation.kt`; modify only `mobile/src/main/kotlin/dev/quantumink/hermesgadget/mobile/MainActivity.kt`. Create `mobile/src/androidTest/kotlin/dev/quantumink/hermesgadget/mobile/QuickActionUiTest.kt` and `QuickActionNavigationTest.kt`.

**Interfaces:** Page consumes ordered actions and current catalog pages, callbacks save/review/pin. Review consumes current action and pages; returns `(QuickAction, Map<String,String>)` on explicit draft transfer. Navigation re-resolves exact current key and verifies action/config equality immediately before `conversation.open` and `conversation.draft`; never calls connect/send/record.

- [x] Add native UI tests exercising create/edit/reorder/delete, explicit profile choice, required input, preview/transfer and three themes; add launch/lock tests for busy and approval, deleted/changed action, removed/withdrawn/offline/duplicate-name profiles and lifecycle generations.
- [x] Compile test before implementation for RED.
- [x] Implement accessible vertical native management/editor/review screens with bounded fields and readable source/key labels. `{{name}}` fields require nonblank input; preview shows full expanded text, exact recipient and explicit Talk/Chat transfer. Recovery links edit missing targets; no default name-based substitution.
- [x] Add Settings entry and saved destination/action ID; consume shortcut only while resumed. Busy/approval launches show a pending notice and require deliberate retry after unlock. Use existing PhoneProfileNavigation generation checks and recheck current config and lock immediately before transfer.
- [x] Run focused native suite and existing affected navigation/conversation native tests. Inspect actual UI via T3 device tools/agent-device; capture private synthetic receipts.

### Task 4: Final verification and handoff

**Files:** Update this plan's checked steps; write ignored `.local/quick-actions-report.md`.

- [x] Run `./gradlew check :mobile:assembleDebug :mobile:assembleDebugAndroidTest` with local SDK path and warning-strict defaults. Full protocol SDK integration requires optional isolated local Python tools; report any skips honestly.
- [x] Inspect `git diff --check` and exact file scope, self-review latest-record routing, credentials exclusion, tamper handling and lock/lifecycle boundaries. Report absent live/physical/API29 acceptance separately.
- [x] Commit exact assigned paths with `git add <explicit paths>` and `git commit`; never push. Include SHA, command receipts, native results, files, self-review and integration limits in private report.

## Plan self-review

N4 coverage maps to Tasks 1–3; six seeds, encryption, missing targets, opaque shortcuts and explicit foreground transfer are included. No external provider or scheduler is implied. Official Android primary references checked: https://developer.android.com/reference/android/content/pm/ShortcutManager and https://developer.android.com/develop/ui/compose/system/shortcuts/creating-shortcuts . The launcher owns pin acceptance and placement; dynamic count/rate limits do not limit stored actions.

## Applied implementation rulings

- The existing conversation owns Connect and Send. Review expands the prompt and transfers a draft explicitly; no action launch creates enrollment, invokes recording or sends text.
- Native ICU requires escaped closing braces in the placeholder regex, although the JVM accepts unescaped closers. The first native suite caught this; both runtimes now use `Regex("\\{\\{([a-z][a-z0-9_]{0,31})\\}\\}")`.
- Store saves compare the editor's expected list and write under the same process lock. A stale editor fails instead of losing a newer edit.
- MainActivity integration needs no manifest or dependency changes. The existing Courier resources come from the client module.
- The ignored report is this scoped executor's progress ledger; root status/PRD docs remain the controller's responsibility. No child reviewer is dispatched.

## Final interface anchors and verification record

Store mutation is a compare-and-write operation under the same lock:

```kotlin
fun save(actions: List<QuickAction>, expected: List<QuickAction>? = null) = synchronized(lock) {
    val previous = read()
    check(expected == null || previous == expected) {
        "Actions changed. Reopen to review the latest data."
    }
    write(actions.toList())
}
```

The foreground transfer gate consumes immutable reviewed data and the current exact profile:

```kotlin
if (phoneSelectionLocked(state) ||
    latest.singleOrNull { it.id == reviewed.id } != reviewed
) return null
val (page, profile) = resolved ?: return null
if (QuickActions.resolve(reviewed, listOf(page)) !== page ||
    !profile.endpoint.isTls || page.profile.gadgetUrl != profile.endpoint.url
) return null
return runCatching { QuickActions.expand(reviewed, inputs) }.getOrNull()
```

Preview removes input fields from composition, clears focus/hides IME and resets scroll;
Change inputs returns to the preserved transient form. The large-font regression exposed
retained focus with inline input/preview, so the final UI uses dedicated input and review modes.

Verified commands/results and source-bound synthetic captures are recorded in the ignored
`.local/quick-actions-report.md`. Strict root checks include isolated loopback stock/extension
fixtures; native checks use only the newly created API37 Relay_N4_QuickActions AVD.
API29 runtime, physical delivery and real Hermes sends are explicitly outside these receipts.
Controller owns final independent review; no child agent or push is part of this plan.
