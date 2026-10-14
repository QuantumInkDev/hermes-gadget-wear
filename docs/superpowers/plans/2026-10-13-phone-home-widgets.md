# Phone Home Widgets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Provide the approved seven OS-themed phone portrait widgets with isolated configuration and exact-profile Talk/Chat launches.

**Architecture:** Seven native AppWidgetProvider components share a RemoteViews renderer, bounded local snapshot/cache and Compose editor. All widget work uses local metadata/static PNGs, while MainActivity resolves validated stable keys through existing foreground phone guards. A serial local updater stays independent of blocking watch publication.

**Tech Stack:** Kotlin, Android AppWidget/RemoteViews, existing Material 3 Compose, AtomicFile, existing JSON library; phone API 29+, compile/target API 37. No added widget framework dependency.

**Spec:** `docs/superpowers/specs/2026-10-13-phone-home-widgets-design.md`.

## Global Constraints

- User-facing dimensions mean rows × columns: 1×1, 2×2, 2×4, 1×4, 4×4, 1×5 and 2×5.
- Follow system is the default; API 31+ wallpaper/night colors, API 29–30 light/dark fallbacks; fixed Light/Dark/Moonlight are independent per instance.
- Portraits stay clear; captions below, targets at least 48 dp in supported sizes; insufficient space shows settings recovery.
- Preserve stable slot count/order on resize and missing/withdrawn profiles; never select another bot by index.
- Configs and intents carry no endpoint, access token or identity. Widget rendering performs no catalog networking, microphone, Gadget connection or periodic polling.
- Use existing GIF PNG fallbacks, bounded 8-MiB thumbnail storage, decoded RemoteViews bitmap budget at most min(1 MiB, one RGBA screen).
- Preserve phone/watch identities/art, FLAG_SECURE, foreground capture/socket rules and SDK v0.2.0 protocol compatibility. Personal setup/art stays ignored.
- Work on the authorized existing feature branch; no physical data reset, no shared ADB reset, no merge/release.

## Task 1: Validated durable widget state and static snapshot

**Files:**
- Create `mobile/src/main/kotlin/dev/quantumink/hermesgadget/mobile/widgets/HomeWidgetModel.kt`, `HomeWidgetStore.kt`, `HomeWidgetPictures.kt`.
- Test `mobile/src/test/kotlin/dev/quantumink/hermesgadget/mobile/widgets/HomeWidgetModelTest.kt` and `mobile/src/androidTest/kotlin/dev/quantumink/hermesgadget/mobile/widgets/HomeWidgetStoreTest.kt`.

**Interfaces:**
- Produces `HomeWidgetPreset` (rows, columns, capacity, provider name); `WidgetAppearance`; `WidgetConfig(preset, slots, appearance, revision)`; `WidgetProfile(key, name, source, status, portraitHash)`.
- Produces `HomeWidgetStore(directory: File)` with `config(id): WidgetConfig?`, `save(id, config, expectedRevision): WidgetConfig`, `delete(id)`, `restore(oldIds, newIds, preset)`, `profiles(): List<WidgetProfile>`, `saveProfiles(profiles)`.
- Produces `HomeWidgetPictures(directory: File)` with `prepare(hash, source)`, `bitmap(hash, side)`, `retain(hashes)`. These read only bounded validated local PNGs.

- [x] Write behavioral tests before the implementations. The model test's representative exact-target case is:

```kotlin
@Test fun horizontalStripKeepsOrderedStableKeys() {
    val config = WidgetConfig(HomeWidgetPreset.STRIP_FOUR, listOf("a:one", "b:one", null, null))
    assertEquals(1, config.preset.rows)
    assertEquals(4, config.preset.columns)
    assertEquals(listOf("a:one", "b:one", null, null), config.slots)
    assertThrows(IllegalArgumentException::class.java) {
        config.copy(slots = listOf("a:one", "a:one", null, null))
    }
}
```

- [x] Run `./gradlew :mobile:testDebugUnitTest --tests '*HomeWidgetModelTest' --warning-mode=fail`. Expected: missing feature fails before implementation; reject fixture/import typos separately. Native tests cover independent files, stale CAS edits, malformed content, delete/restore, source-bound PNG validation and cache bounds.
- [x] Implement the declared types with strict key/name/hash/count/version validation. JSON contains only the declared fields; reject malformed/non-string values. AtomicFile save increments revision and rejects stale expectedRevision. Bounded reads return safe missing state; restore never overwrites an existing destination.

```kotlin
require(id > 0)
val previous = config(id)
require((previous?.revision ?: 0L) == expectedRevision)
val saved = config.copy(revision = Math.addExact(expectedRevision, 1))
```

- [x] Run focused JVM/native tests. Expected: all requested methods pass, no assumption skip on the verified owned phone emulator. Commit `feat: persist isolated home widget profiles and static artwork`.

## Task 2: Native rendering, seven providers and ordered local updates

**Files:**
- Create `widgets/HomeWidgetRenderer.kt`, `HomeWidgets.kt`, `HomeWidgetProviders.kt`.
- Create `res/layout/home_widget*.xml`, `res/xml/home_widget_*.xml`, `res/drawable/home_widget*.xml`, `res/values*/home_widgets.xml`.
- Modify `mobile/src/main/AndroidManifest.xml` and `CatalogRepository.kt`.
- Test `widgets/HomeWidgetRenderTest.kt` under androidTest; add debug `HomeWidgetTestActivity` and synthetic PNG assets under androidTest only.

**Interfaces:**
- Consumes Task 1 types/store/pictures.
- Produces `HomeWidgetRenderer.render(context, id, config, profiles, pictures, widthDp, heightDp): RemoteViews`.
- Produces `HomeWidgets.preset(context, id): HomeWidgetPreset?`, `instances(context): List<Pair<Int, HomeWidgetPreset>>`, `publish(context, pages)`, `update(context, ids)`, `editIntent(context, id)`; publication is serial and independent of watch network work.
- Task 3 uses providers registered in the manifest and these ownership/edit helpers.

- [x] First add a native test asserting seven registered provider mappings, 1×4 four horizontal targets, unknown keys unavailable, sixteen real portrait targets and total bitmap budget.

```kotlin
val views = HomeWidgetRenderer.render(context, id, config, profiles, pictures, 349f, 455f)
val rendered = views.apply(context, FrameLayout(context))
assertEquals(16, targets(rendered).size)
assertTrue(targets(rendered).all { it.contentDescription.isNotBlank() })
```

- [x] Run the focused native method against a failing placeholder renderer; the missing cells must fail the count assertion before the real renderer is introduced.
- [x] Implement weighted RemoteViews rows/cells; use supported platform views, circle thumbnails, caption text, full accessible source/name, original fallback and explicit immutable activity PendingIntents. Unique intent identity includes instance and slot. Use OS-themed XML resources for Follow system and fixed semantic palette resources for overrides.
- [x] Register seven receiver/metadata pairs with targetCellWidth=columns, targetCellHeight=rows, explicit preview layouts, resize bounds, reconfigurable flag, configure activity and updatePeriodMillis=0. Deleted/restored IDs are scoped to the receiving provider.
- [x] Add local snapshot enqueue hooks to savedPages/publishCurrent before watch publication. Never wait for Data Layer/network to update widgets. Coalesce/order updates; check current config immediately before installing RemoteViews.
- [x] Run native host render/target/contrast/bitmap tests and strict compilation. Expected: supported geometries preserve count/order, undersized hosts get recovery, no network/vault creation. Commit `feat: render seven system-themed home widget presets`.

## Task 3: Native per-widget bot editor and settings entry

**Files:**
- Create `widgets/HomeWidgetConfigActivity.kt`, `HomeWidgetEditor.kt`, `HomeWidgetsPage.kt`.
- Modify `MainActivity.kt`, `PhoneSettingsPage.kt`, manifest; reuse existing portrait/name components without exposing credential metadata.
- Test `widgets/HomeWidgetEditorTest.kt` in androidTest.

**Interfaces:**
- Consumes Task 1 config/store and Task 2 owned instances, editIntent/update.
- Produces exported configuration activity accepting only a currently owned appWidgetId and matching provider. Editor works on a draft and returns a saved config only after explicit Save.
- Produces `HomeWidgetsPage(back: () -> Unit)` for Settings → Home screen widgets.

- [x] Write a native editor test first: bind an owned test widget, choose two same-named profiles from different boxes, reorder, change theme, save, reopen/cancel, then verify only that widget changes.

```kotlin
val original = store.config(firstId)
openEditor(firstId)
choose("b:one")
cancelEditor()
assertEquals(original, store.config(firstId))
assertEquals(secondBefore, store.config(secondId))
```

- [x] Run to a real missing-editor failure; implement box-grouped search, live static preview, slot chooser, remove/replace/Move earlier/Move later, appearance choices, Save/cancel and invalid/stale-ID recovery. Use rememberSaveable for the draft across Activity recreation.
- [x] Keep RESULT_CANCELED until atomic save and update succeed; report errors without discarding the draft. Save with expected revision; stale parallel editors require reload. Keep production FLAG_SECURE.
- [x] Run native editor/system reconfigure tests, cancellation and settings routing. Expected: distinct instances and unchanged phone appearance, pairing data and directory state. Commit `feat: configure home widget bots and appearance natively`.

## Task 4: Exact cold/warm profile launch without interrupting turns

**Files:**
- Create `widgets/HomeWidgetLaunch.kt` and focused `PhoneProfileNavigation.kt` if needed to share the existing directory resolution.
- Modify `MainActivity.kt`; use the existing PhoneConversation state/controller.
- Test JVM `HomeWidgetLaunchTest.kt` and native `widgets/HomeWidgetLaunchTest.kt`.

**Interfaces:**
- Consumes current owned widget ID/slot/config and current local catalog key resolution.
- Produces validated widget launch request and a pure decision `IGNORE`, `OPEN`, `DEFER` or `UNAVAILABLE` from current requested key/active key/busy/roster state.
- Both directory and widget navigation use one off-main lookup with request-generation, lifecycle and current-page guards.

- [x] Write behavioral tests for provider ownership; stale slots; same-profile idempotence; retired profiles; busy, approval and speaking deferral; and cancelled async lookup before implementation.

```kotlin
assertEquals(LaunchDecision.DEFER, decideLaunch("b:one", "a:one", busy = true, available = true))
assertEquals(LaunchDecision.IGNORE, decideLaunch("a:one", "a:one", busy = true, available = true))
assertEquals(LaunchDecision.UNAVAILABLE, decideLaunch("b:one", null, busy = false, available = false))
```

- [x] Run focused tests to observe missing behavior. Implement onCreate/onNewIntent consumption once, request replacement/cancellation, current-profile no-op and explicit pending Open requested bot when idle. Missing/withdrawn keys lead to edit/unavailable, never an index fallback. No Connect/send/record/pair side effect from launch.
- [x] Run actual secure MainActivity cold/warm exact-target and directory context tests, plus existing phone conversation regressions. Expected: no identity creation and protected active turns/drafts. Commit `feat: open exact widget profiles with foreground turn guards`.

## Task 5: Native acceptance, review and preserving delivery

**Files:**
- Expand the above native tests and debug synthetic host; add `docs/demo/home-widgets/` captures/manifest/evidence.
- Update PRD, architecture, release, progress, SESSION_STATUS and this plan.

**Interfaces:**
- Consumes complete widget providers/editor/launch behavior; produces reproducible receipts and explicit physical/capability limits.

- [x] Run real host geometry/theme/font/RTL/empty/missing/corrupt cases. Save only raw synthetic native screenshots; verify PNG dimensions/SHA and source/artifact hashes.
- [x] Exercise two real wallpaper palettes/night colors where the owned emulator supports them; record unsupported older OS/physical launcher cases as open instead of passed. Verify storage/bitmap ceilings, process recreation and preserving emulator reinstall.
- [x] Run `./gradlew ktlintFormat check :mobile:assembleDebug :wear:assembleDebug :mobile:assembleDebugAndroidTest :wear:assembleDebugAndroidTest --warning-mode=fail --max-workers=4`. Expected: no warnings and every JVM report has zero failures/errors/skips. Run changed native checks and relevant existing navigation/audio checks.
- [x] Dispatch one fresh final review of the complete widget change from the approved baseline. Regrade findings; fix Important/Critical issues once with failing-then-passing regression tests and a green strict suite. Record deferred Minor findings.
- [x] Stage only public source/synthetic evidence/docs; run git diff --check, private-value/art and staged secret scans; commit and update the linked draft PR. No merge or Play release.
- [x] Build the matching debug APK for preserving physical install. If the phone is available and authorized, back up APK/app files, verify serial/signing certificate, install -r, verify installed hash and all identity/art hashes, retain original timeout. Otherwise leave the verified APK ready and request the missing device connection. Do not mark One UI placement or physical speech passed without observations.

## Review Focus

Deliberately inspect provider/ID ownership, restore collisions, forged/stale PendingIntents,
serial publication versus blocked watch Data Layer, old artwork retirement, bitmap allocation,
background/lifecycle/request races and prompts while a different widget requests navigation.
Verify system colors reinflate in a real AppWidgetHost and explicit palettes survive OS
changes. Ensure configuration cancellation/recreation and multiple editors cannot overwrite
unrelated widgets, phone credentials, watch tile pins or directory return state.

## Completed software handoff

Implemented through `5a9a459`, with one fresh review and one corrective pass. Final strict
checks: 125 JVM tests / 35 reports, zero failures/errors/skips, all four APKs. Final native
receipts: 32 affected methods + one blocked-watch publication + five stock TLS audio/vault
methods, with 112 host geometry cases. See [review resolutions](../../phone-home-widgets-review.md)
and [native exports](../../demo/home-widgets/README.md).

Task 5's conditional delivery path applies: the phone is absent, the matching-signer APK
is staged privately, USB reconnection was requested, and no physical install is claimed.
Two-wallpaper, API29–30 runtime and physical launcher gates remain explicitly open.
