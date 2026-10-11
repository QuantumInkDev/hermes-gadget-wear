# Multi-box profile carousel

Status: recommended design approved by the maintainer, 2026-10-11. App and isolated
catalog development were authorized first. The maintainer's later request to set up the
profiles authorizes the concrete live catalog and independent listener rollout. New endpoint
review/pairing still uses the normal watch-owner flow; optional SDK extensions remain isolated.

## Intended experience

Open Hermes on the watch to the last selected profile. Swipe left or right through profiles
from three independently configured Hermes boxes. Each page shows a large profile portrait,
profile name, source-box label and connection status, followed by the conversation controls.
The selected page uses that profile's pet when a valid pet is available; its portrait remains
the fallback. Duplicate profile names on different boxes remain separate.

Keep pages in a stable owner-defined order when a box sleeps or leaves the network. Cached
profiles and pictures remain browsable with an Offline or Last checked label. Coming back
online refreshes availability without moving the current page. Losing connectivity never
deletes setup, rotates an enrollment key, selects another agent or replays a message.

## Approaches and recommendation

1. **Automatic phone catalog, independent Gadget endpoints — recommended.** Each box publishes
   a small authenticated, read-only watch catalog projected from its existing Hermes profile
   and avatar inventory. The phone merges the three catalogs and synchronizes metadata/assets
   to the watch. Conversations retain the existing per-profile Gadget URLs and watch-owned
   TLS. This needs catalog publication and explicit Gadget endpoint mapping on each box.
2. **Phone-configured catalog.** Enter each profile's Gadget URL/name and choose its picture
   on the phone. Reachability still refreshes and the carousel works with stock servers;
   adding or removing server profiles requires updating phone setup manually. Retain this
   as the fallback for boxes without catalog support.
3. **Multiplexed Gadget gateway per box.** List/select profiles through one Gadget connection.
   This is the existing [v2 proposal](../../profiles-v2.md), with a different routing and
   authorization contract. Its explicit decision/upstream-feedback gate remains in force.

The recommended approach implements the requested automatic roster without depending on
multiplexed Gadget routing. Catalog publication and live rollout require review of concrete
changes; the existing three SDK extension patches remain isolated until that review.

## Verified starting points

- Stock SDK v0.2.0 at `323e3303ab68981f810fc3208119cd8a22e64af0` binds a Gadget adapter to
  its profile home and port. Device keys and approval are independent per endpoint.
- Existing watch code stores eight endpoint profiles, explicitly imports phone setup and
  opens a name list on horizontal swipe. It does not implement a profile-page carousel.
- Existing phone sync contains setup metadata, not discovered host inventories or pictures.
- The separately installed Hermes desktop/backend source has `/api/profiles` inventory,
  display names and avatar metadata. Desktop's union-roster logic retains remembered profiles
  during source failures. These source observations do not prove phone-accessible endpoints
  or the installed capability/version of the other boxes.
- Pet manifests, endpoint-isolated caching, state mapping and companion-sheet registration
  tooling already exist. Live pet delivery is still an optional server-extension gate.

## Catalog and availability

Configure each source on the phone with a stable random source ID, display label and an
explicit HTTPS catalog address. Store its catalog-only credential encrypted in the phone
Keystore vault; omit it from watch setup. Publish only owner-approved profiles and artwork.
The watch-facing projection excludes filesystem paths, environment values, administrative
credentials, conversation history and device keys.

A record carries source ID, stable profile ID, display name, explicit canonical Gadget URL,
portrait asset hash and bounded availability metadata. The catalog never derives a Gadget
URL from an SSH address or dashboard port. Profiles without a published Gadget URL can show
their picture and a Watch setup needed state, but cannot initiate a conversation.
Use an existing persistent backend profile identity when available; otherwise the catalog
publisher assigns and persists a UUID with the profile, retaining it through display-name
changes. Scope every identity to its configured source. Catalog records exclude Gadget
access tokens; preserve those from existing encrypted setup for unchanged URLs, or request
them through the normal setup UI when needed.

Use HTTPS hostname/certificate verification, no redirects, and source-bound asset paths.
Separate catalog reachability from Gadget transport reachability and paired Ready state.
An authentication failure is Connection needs attention, not an offline result or an empty
catalog. Only a successful authoritative catalog response can mark a profile withdrawn;
keep its local identity and require owner confirmation to remove saved watch setup.

Refresh on watch app open, explicit refresh, phone source edits and a connectivity recovery
event. Coalesce duplicate requests and honor a 60-second freshness interval; explicit retry
can bypass freshness. Do not run periodic fleet polling while the watch app is idle or ambient.
Fan out source reads independently with eight-second per-source deadlines and a ten-second
overall deadline; partial results update promptly and failed sources retain their cache.
Close discovery/asset channels and release their foreground lifetime after the request.

Support at most 32 profiles and a 64 KiB metadata envelope in this first delivery. Reject
oversized responses explicitly rather than truncating silently. Preserve existing setup
records during migration. Previously approved unchanged URLs may receive label/art/status
updates; new or changed URLs enter the existing watch review/pairing flow. Discovery does
not authorize enrollment. Conversation keys remain keyed by canonical URL and never cross
profiles, boxes or the phone relay.

## Watch navigation and switching

Use a horizontal pager for profile pages and vertical scrolling within the current page.
Rotary input scrolls that page; provide explicit accessible Previous/Next controls as well.
Connect only the selected approved page after it settles; neighboring pages do not establish
Gadget sessions. An unavailable profile offers Retry and remains selected rather than
silently falling through to another profile.

Keep hold-to-talk, release-to-send and downward recording cancellation. Disable horizontal
switching while recording or a live approval is displayed. Switching during other states
stops prior capture/playback/phone speech, closes the old transport and increments the
selection epoch before connecting the new endpoint. Guard late replies, media, pet assets
and actions by that epoch. Closing the client does not establish cancellation of server-side
tools, so do not present it as a server-task cancellation.

Retain unsent text drafts per approved endpoint in bounded service-owned memory, never
automatically submit them, and restore the matching draft when returning to its page.
Move page selection, navigation and setup-review drafts outside the composition replaced
by ambient mode. A sleep/wake cycle preserves the page and unfinished setup review without
keeping the display awake. Explicit process death clears memory-only unsaved drafts.

## Portraits, pets and animations

Read each profile's existing Hermes avatar through the authorized catalog projection;
provide phone photo selection as a fallback. Normalize static portraits to at most 256×256
pixels and 256 KiB, apply orientation and strip metadata before transfer. Verify length and
SHA-256, decode off the UI thread and promote atomically. Maintain an 8 MiB portrait cache;
remove superseded unreferenced assets before evicting current portrait fallbacks.

Display priority is a validated pet for that endpoint, its cached portrait, then an original
neutral placeholder. Pet arrival crossfades the portrait into the pet without changing the
profile identity or blocking speech. A missing or corrupt pet preserves the portrait.
Prepare only the selected page and adjacent previews; animate only the selected foreground
page. Offline pages remain static and ambient uses a sparse monochrome outline.

Include greeting/wave, attentive listening, working/thinking, amplitude-driven talking,
success/jump, error and sleeping/offline states. Use the existing base rows and optional
separate listening/talking/sleeping companion sheet. Talking uses closed/half/open mouth
poses with amplitude hysteresis. Preserve the original base sheet and register new poses
against its scale and baseline using the existing tool; same-hatch generation remains an
upstream generator proposal. New personal companion art stays in ignored maintainer storage
and requires visual review. Public tests/demos use original synthetic pictures and pets.

## Delivery and verification

1. Implement the bounded catalog model, read-only projection/export contract and phone merge,
   with manual-catalog fallback, source failures and migration tests. Update the upstream
   proposal with the isolated server changes and exact compatibility checks.
2. Implement portrait synchronization and the watch carousel, safe selection, memory-only
   per-profile drafts and ambient-review preservation. Exercise three independent stock SDK
   processes, with duplicate names, delayed callbacks and boxes disappearing/reappearing.
3. Integrate per-page cached pets and new companion-animation fixtures. Verify portrait-to-pet
   transitions, corrupted assets, missing companion states, cache isolation and idle behavior.
4. Run strict warning-free builds/checks and owned-emulator gesture/lifecycle/asset tests.
   Before live rollout, review catalog/TLS/Gadget configuration for each real box; request
   approval for any newly paired endpoints and preserve existing enrollment ciphertexts.
   Record real three-box relay, lock/background, ambient/wake and artwork observations
   separately from software tests. Battery, speech quality and Play acceptance remain open.

Personal source names, addresses, tokens, portrait/pet paths and setup results belong in
ignored `.local/` files. This design deliberately contains only generic source identities.
