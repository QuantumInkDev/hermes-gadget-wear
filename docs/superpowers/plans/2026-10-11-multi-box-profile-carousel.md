# Multi-box Profile Carousel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Discover independent host profiles on the phone and browse stable portrait/pet pages on the watch, preserving endpoint enrollment and conversation isolation.

**Architecture:** A standalone read-only HTTPS publisher projects explicitly mapped profiles and normalized artwork. An encrypted phone source/cache vault merges independent responses, publishes a bounded watch roster and portrait assets, and services on-demand refreshes. The watch retains approved endpoint setup separately from discovered metadata and connects only the settled approved page.

**Tech Stack:** Kotlin/JVM, Android Keystore/AtomicFile, OkHttp, Compose Foundation pager, Wear Compose, Play Services Data/Channel clients; Python standard library plus Pillow for the isolated publisher.

**Spec:** [Approved design](../specs/2026-10-11-multi-box-profile-carousel-design.md).

## Global Constraints

- Stock Hermes Gadget SDK v0.2.0 remains compatible; no multiplexed Gadget v2.
- 32 profiles, 64 KiB metadata, 8-second source and 10-second overall discovery deadlines.
- HTTPS catalogs, no redirects, source-bound artwork; catalog credentials stay encrypted on the phone.
- Watch-owned WSS conversations and independent canonical-URL keys; explicit existing LAN/tailnet WS fallback remains supported.
- Portraits at most 256×256 / 256 KiB with SHA-256 and atomic promotion; separate 8 MiB portrait and pet quotas.
- Refresh on demand, 60-second freshness, no periodic idle/ambient fleet polling.
- Stable source/profile identities and page order; failure retains cached cards, successful absence marks withdrawal.
- Discovery cannot enroll a new/changed URL. Never replay input or switch to a different agent on failure.
- No live deployment or new personal endpoint pairing in this plan. Private source configuration/artwork stays ignored.

## Task 1: Bounded catalog contract and isolated publisher

**Files:** Create `protocol/src/main/kotlin/dev/quantumink/hermesgadget/protocol/ProfileCatalog.kt`, `protocol/src/test/kotlin/dev/quantumink/hermesgadget/protocol/ProfileCatalogTest.kt`, `server/catalog/publisher.py`, `server/catalog/test_publisher.py`, `server/catalog/README.md`; modify `protocol/src/main/kotlin/dev/quantumink/hermesgadget/protocol/EndpointProfiles.kt` and its test.

**Interfaces:** `CatalogProfile(id, name, gadgetUrl: String?, portrait: PortraitDescriptor?)`; `CatalogSource(id, label, url, credential)` redacts secrets; `CatalogPage(sourceId, sourceLabel, profile, status, checkedAt)`; `ProfileCatalog.decodeSource(text)` and `encode/decodePages`; `CatalogRoster.merge(source, response?, status, checkedAt)` retains order/cache. Publisher consumes owner mapping with explicit URLs and local profile/avatar paths, emits version-1 minimal projection, persists UUIDs in separate private state, and serves bearer-authenticated GET catalog/assets only.

- [x] Write contract tests: duplicate names on separate sources remain distinct, disappearance retains withdrawn page, source failure retains artwork, invalid/duplicate identities/URLs and envelope/profile/asset bounds fail; 32 unique setup profiles round-trip and 33 fail.
  ```kotlin
  val profiles = (0 until 32).map {
      EndpointProfile(Endpoint.parse("wss://box-$it.example.invalid/gadget"), "Agent", "")
  }
  assertEquals(32, EndpointProfiles.decode(EndpointProfiles.encode(profiles)).size)
  ```
- [x] Run `./gradlew :protocol:test --tests '*EndpointProfilesTest' --tests '*ProfileCatalogTest' --warning-mode=fail`. Expected: new behavior fails before implementation.
- [x] Implement strict parsing and stable roster merge; extend setup bound without changing encrypted record format or URL key derivation. Publisher normalizes orientation/metadata and performs constant-time credential checks, rejects path escapes, never publishes tokens or device keys.
  ```python
  response = {"version": 1, "profiles": [{"id": stable_id, "name": name,
               "gadget_url": explicit_wss_url, "portrait": portrait_descriptor}]}
  ```
- [x] Run the focused JVM tests and `python3 -m unittest discover -s server/catalog -p 'test_*.py' -v`. Expected: contract/auth/persistence/artwork tests pass, unchanged inputs remain unchanged.
- [x] Commit contract, publisher and documentation as `feat: add bounded read-only profile catalog`.

## Task 2: Phone discovery, encrypted cache and manual fallback

**Files:** Create `mobile/.../CatalogClient.kt`, `CatalogVault.kt`, `CatalogRepository.kt`, `CatalogPage.kt` under `mobile/src/main/kotlin/dev/quantumink/hermesgadget/mobile/`; tests `mobile/src/test/kotlin/dev/quantumink/hermesgadget/mobile/CatalogClientTest.kt` and Android `CatalogVaultTest.kt`; modify `ProfilesPage.kt`, `ProfileSync.kt`, `RelayService.kt`, `MainActivity.kt` and strings/manifest as necessary.

**Interfaces:** Client `fetch(source): List<CatalogProfile>`, `portrait(source, descriptor): ByteArray`; vault `sources()/pages()/saveSources()/savePages()`; repository `refresh(force, changed)` with independent fanout/coalescing, source status and portrait cache; sync `publishCatalog(context, pages, portraits)`. Data path `/hermes/catalog/v1`, portrait path `/hermes/portraits/v1/<hash>`, refresh channel `/hermes/catalog/refresh/v1`.

- [x] Write actual TLS HTTP-server tests for bearer scope, redirect denial, cross-source asset paths, source auth/offline distinction, partial fanout, cache freshness and explicit retry. Write encrypted-vault restart/tamper tests.
  ```kotlin
  val response = client.fetch(source)
  assertEquals("Agent", response.single().name)
  assertEquals("Bearer syntheticCatalogCredential", server.takeRequest().getHeader("Authorization"))
  ```
- [x] Run `./gradlew :mobile:testDebugUnitTest --warning-mode=fail`. Expected: missing catalog consumer fails.
- [x] Implement encrypted sources/cache, bounded OkHttp reads, independent refresh workers and atomic portrait storage. Source form uses masked catalog credentials; existing manual setup remains usable and gets photo-picker fallback. Sync never includes source credentials. Channel lifetime is bounded and closes after publishing/replying.
  ```kotlin
  val page = CatalogPage(source.id, source.label, profile, CatalogStatus.AVAILABLE, now)
  // Only metadata and normalized assets cross the Data Layer.
  ProfileSync.publishCatalog(context, pages, portraitFiles)
  ```
- [x] Run phone JVM tests plus owned-phone `CatalogVaultTest` and original `ProfileVaultTest`. Expected: independent failures preserve successful cards, vault decrypts after restart, malformed input never replaces the cache.
- [x] Commit as `feat: discover and sync phone profile catalogs`.

## Task 3: Watch roster, portrait cache and endpoint draft isolation

**Files:** Create `wear/.../WatchCatalog.kt`, `PortraitCache.kt`, `ProfileDrafts.kt` and Android `PortraitCacheTest.kt`, `WatchCatalogTest.kt`; modify `GadgetService.kt`, `IdentityVault.kt` only where needed, `WatchProfileTest.kt`, and `MultiEndpointIntegrationTest.kt`.

**Interfaces:** WatchCatalog reads cached phone Data Items and bounded refresh response; PortraitCache `store(hash, bytes)/load(hash)/retain(hashes)`; service state adds roster, refreshing and selected/adjacent prepared artwork. `ProfileDrafts.select(url)/update(text)/remove(url)/clear()` stores bounded memory only. Setup remains an independent approved URL catalog.

- [x] Write tests for bad hash/length/dimensions, asset isolation and cache quota; three independent stock servers and independent identities; unsent draft A -> B -> A restore with no automatic submission; stale callbacks never update the current page.
  ```kotlin
  drafts.select("wss://a.example.invalid/gadget")
  drafts.update("unsent A")
  drafts.select("wss://b.example.invalid/gadget")
  assertEquals("unsent A", drafts.select("wss://a.example.invalid/gadget"))
  ```
- [x] Run focused protocol/host tests and build Android tests. Expected: draft restoration/portrait consumer missing before implementation.
- [x] Implement atomic verified portrait cache and safe metadata application for unchanged approved URLs. New/changed URLs remain review cards. Keep enrolled identity files intact. Load only selected and adjacent artwork off the main thread; preserve source failure cards.
- [x] Run three-server integration and owned-watch cache/catalog/service tests. Expected: independently paired replies, preserved per-endpoint drafts and rejected old media/callbacks.
- [x] Commit as `feat: retain watch catalog artwork and endpoint drafts`.

## Task 4: Stable swipe pages and ambient-safe review

**Files:** Create `wear/.../ProfileCarousel.kt`, `WatchNavigation.kt`; modify `WatchScreen.kt`, `MainActivity.kt`, strings; create Android `WatchCarouselTest.kt` and extend `WatchLifecycleTest.kt`.

**Interfaces:** `WatchNavigation` is activity-owned memory containing route/editing/setup fields/selected page key; `ProfileCarousel` consumes roster plus approved setup, stable keys, selected-page artwork and settle callback; service `selectProfile` remains URL-scoped.

- [x] Write owned-emulator tests: actual horizontal swipe selects one next page, duplicate labels remain distinct via source label, recording/approval blocks switching, explicit Previous/Next works, vertical/rotary scroll remains available, setup draft and selected page survive ambient composition replacement.
  ```kotlin
  assertEquals("Second source", visibleSourceLabel())
  assertEquals(secondUrl, service.state.value.profile?.endpoint?.url)
  ```
- [x] Build/run regression before replacing list swipe. Expected: carousel navigation fails on the existing list UI.
- [x] Implement stable-key horizontal pager, only settled approved page connects, setup-needed/withdrawn/offline cards have explicit review/retry actions; hoist all unfinished setup/navigation memory above ambient conditional. Keep last selected page and bounded drafts; never keep display awake.
  ```kotlin
  HorizontalPager(state = pager, userScrollEnabled = !recording && !approval) { page ->
      ProfilePage(pages[page], selected = page == pager.settledPage)
  }
  ```
- [x] Run owned-watch carousel, original draft/profile and lifecycle regressions. Expected: gesture, accessibility, ambient/wake and separate conversation behavior pass.
- [x] Commit as `feat: swipe through stable watch profile pages`.

## Task 5: Portrait-to-pet transitions and animation evidence

**Files:** Modify `wear/.../PetView.kt` and carousel artwork presentation; create original synthetic portrait/companion fixtures with manifest under `docs/demo/carousel/`; extend `WatchPetTest.kt`; update `docs/pets.md`, `docs/upstream.md`, `docs/progress.md`, `SESSION_STATUS.md` and `server/catalog/README.md`.

**Interfaces:** Page artwork chooses pet -> portrait -> original placeholder, crossfades on validated pet arrival; `PetView(..., animated: Boolean)` freezes unselected/offline previews; existing companion rows and mouth hysteresis remain unchanged.

- [x] Write tests showing corrupt/missing pet keeps portrait, independent endpoint art survives switching, neighbor previews remain static and selected foreground cue/companion states animate.
- [x] Run before transition implementation. Expected: existing pet-only renderer lacks portrait fallback/selection freeze.
- [x] Implement crossfade and selected-only animation; prepare original three-source demo and registered companion sheet through existing `tools/extend-pet.py` without changing base art. Prepare private live rollout mapping/checklist, with unknown Gadget URLs left unset and no deployment.
- [x] Run `./gradlew ktlintFormat check :wear:assembleDebug :wear:assembleDebugAndroidTest :mobile:assembleDebug :mobile:assembleDebugAndroidTest --warning-mode=fail --max-workers=4`, publisher Python suite, and owned-emulator feature/regression tests. Expected: zero warnings, failures or enabled integration skips; synthetic captures prove pages/portraits/pets and ambient behavior.
- [x] Run fresh whole-branch review, fix important findings with failing regressions, validate staged documentation links/private-value scan/gitleaks; commit evidence and remaining real-device gates. Report APKs and prepared live rollout for review without claiming physical three-box acceptance.

## Review Focus

Trust boundary between source catalog and approved conversation setup; credential/asset path leaks; catalog limits across independent partial responses; interruption/freshness/deadline closure; stable order through withdrawal/return; selected-page versus callback epoch; actual ambient composition disposal; cache quotas and personal enrollment preservation. Live source addresses, credentials, server rollout, paid speech and physical battery behavior are outside isolated verification.
