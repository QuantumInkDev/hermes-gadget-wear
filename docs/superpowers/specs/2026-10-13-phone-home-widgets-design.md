# Quantum Relay for Hermes: phone home widgets

Status: approved by the maintainer on 2026-10-13, including all seven rows × columns layouts,
per-widget selection/order, clear portraits, OS theming with explicit overrides and exact
Talk/Chat launch. This is the implementation authority, not an installation claim.

## Purpose and layouts

Put chosen bots on the Android phone home screen, with their existing effective portraits
and one-tap navigation to the exact profile's foreground Talk/Chat screen. Preserve the
Portrait studio direction, original Voice Q identity and clear artwork. These are phone
widgets; existing watch Focus/Pinned profiles tiles retain their independent configuration.

All user-facing dimensions mean **rows × columns**:

| Picker label | Rows | Columns | Bot slots | Android target width × height |
|---|---:|---:|---:|---|
| 1×1 · Focus | 1 | 1 | 1 | 1×1 |
| 2×2 · Four bots | 2 | 2 | 4 | 2×2 |
| 2×4 · Eight bots | 2 | 4 | 8 | 4×2 |
| 1×4 · Four-bot strip | 1 | 4 | 4 | 4×1 |
| 4×4 · Sixteen bots | 4 | 4 | 16 | 4×4 |
| 1×5 · Five-bot strip | 1 | 5 | 5 | 5×1 |
| 2×5 · Ten bots | 2 | 5 | 10 | 5×2 |

The app's widget picker previews and editor repeat the convention; provider metadata uses
Android's width/height convention. Cells fill left to right, then top to bottom; RTL mirrors
the visual reading order while keeping stable slot assignments.

Android launchers choose their own cell sizes and spacing. Five-column presets require a
host/grid with enough horizontal space. We advertise these targets and adapt within the
space supplied; we do not promise identical physical dimensions on every launcher.
[Android flexible layouts](https://developer.android.com/develop/ui/views/appwidgets/layouts)
documents target cells, dp size ranges and host variation.

## Approaches considered

| Approach | Benefit | Tradeoff |
|---|---|---|
| Seven native presets, recommended | The requested shapes and capacities are explicit in the launcher; one renderer/editor serves them all | Seven small provider declarations/previews need maintenance |
| One universal responsive grid | One picker entry, broad resizing | Resizing can change the visible shape/capacity unexpectedly; less clear for fixed strips |
| Pinned launcher shortcuts | Familiar single-profile icons | Cannot deliver the requested grouped layouts or consistent widget surfaces/themes |

Use seven AppWidgetProvider components with one shared RemoteViews renderer and a native
Compose configuration activity. RemoteViews is sufficient for static portrait shortcuts and
adds no widget framework dependency. Glance is a viable alternative, but its separate
composables cannot directly reuse the existing Material 3 screen components.
[Glance overview](https://developer.android.com/develop/ui/compose/glance)
describes that boundary. Configuration remains Compose in either approach.

## Appearance and portrait treatment

Default every new widget to **Follow system**: system light/dark plus wallpaper-derived
colors on API 31+, with deliberate light/dark brand fallbacks on API 29–30. The widget choice
is separate from the phone app's explicit appearance. Changing a phone theme does not silently
override a Follow system widget.

The shared editor also offers explicit Light, Dark and Electronic Moonlight for an individual
widget. Explicit choices use the existing semantic palette; they stay fixed until edited.
System resources/DayNight widget themes provide system colors; production captures must verify
actual wallpaper and night changes instead of relying on these mockup palettes.
[Android dynamic widget colors](https://developer.android.com/develop/ui/views/appwidgets/enhance)
supports system DayNight theming and API-specific fallbacks.

Use a quiet rounded tonal container, circular portraits and captions beneath each image.
Keep microphones, menus, selection badges and status dots off the artwork. One entire cell
is the profile target, at least 48 dp in either dimension at supported placement sizes.
Names use real scalable text with bounded two-line ellipsis where space allows; the full
name and source label remain in the accessible description. Narrow short strips use one
caption line if necessary. The 1×1 preset uses one portrait/name with no extra toolbar.

Widgets show the effective phone/watch portrait override, including the existing PNG fallback
of a GIF. They do not animate GIFs or pets on the launcher. Missing art uses the original
initials/Voice Q fallback. Offline sources retain their selected picture and name. Widgets
do not display an unverified live-online indicator or personal conversation content.

All presets preserve their slot count/order when a launcher grants a larger or differently
shaped rectangle; cells and portrait sizes adapt. Resizing never silently adds, removes or
reorders bots. If a host grants too little room for safe targets, show a single clear
Open widget settings recovery target rather than overlapping or unreachable bots.

## Configuration and normal use

Adding a preset opens **Choose bots** with a live local preview, box-grouped search, selected
slots and Appearance. The owner chooses up to the preset's capacity; empty slots remain
visible as Add bot targets. Do not preselect personal profiles. The same profile can appear
in different widget instances, but duplicate keys in one widget are rejected.

Reorder the chosen slots with accessible Move earlier/Move later controls in the editor;
remove or replace a slot without disturbing unrelated slots. Save persists that instance
atomically, renders it and returns RESULT_OK. Back/cancel keeps the previous configuration;
a cancelled first setup returns RESULT_CANCELED. Validate the supplied widget ID and its
provider against this app before reading or saving configuration.

Placed widgets support the host's reconfigure action on API 31+. **Settings → Home screen
widgets** lists the app's actual active instances and opens the same editor, including on
older supported Android versions. Empty cells open the editor for that exact instance.
No settings affordance floats over a portrait. This follows the platform
[configuration lifecycle](https://developer.android.com/develop/ui/compose/glance/configuration).

Tapping a configured cell opens MainActivity at that stable profile key's Talk/Chat screen.
The normal Connect, pairing, hold-to-talk, text send and voice controls remain explicit.
Widget taps never start a microphone, send a message, approve pairing or change credentials.
Cold and warm launches use the same validated resolution path as directory Talk navigation,
with off-main lookup and lifecycle/request-generation guards.

If a warm launch requests the already open profile, keep its draft and active turn. If it
requests a different profile while recording, replying, speaking or awaiting approval,
keep the current turn and show a pending Open requested bot action. Require a deliberate tap
once the current turn is idle; do not switch automatically or discard a prompt. Backgrounding
still follows the existing phone capture/socket lifecycle. Superseded or cancelled navigation
cannot later select a stale profile.

Offline profiles can open their normal conversation/setup screen. Withdrawn, retired or
unknown keys display an unavailable state and a route to edit the widget; they never resolve
to whichever profile happens to occupy an old index. A changed endpoint is resolved through
the current catalog and normal phone review/TLS/pairing rules, never through a widget-saved URL.

## Data flow and component boundaries

1. **Widget configuration store:** per appWidgetId, persist provider/preset, ordered optional
   stable keys and appearance. Store no endpoint, access token or device identity. Atomic
   commits and delete handling affect only that instance. Validate counts/duplicate keys,
   provider ownership and serialized bounds. Reconcile restored old/new widget IDs through
   the platform restore callback without reusing an unrelated instance's configuration.
2. **Local widget snapshot:** project the repository's committed effective pages into stable
   keys, names, source labels, status and validated static picture references. Apply existing
   roster/key/name bounds. Write ordered immutable snapshots off-main after local publication,
   picture replacement, setup removal or retirement. Do not initialize discovery, decrypt
   endpoint vaults or fetch pictures from a widget broadcast.
3. **Static portrait cache:** derive circular launcher thumbnails from validated normalized PNGs
   on an IO worker. Bound it to 8 MiB, share content-addressed entries, retain active references
   and retire unused entries. Changed GIF overrides use their already validated PNG fallback.
   Missing/corrupt/evicted thumbnails fall back safely and cannot decode unbounded content.
4. **Shared renderer/updater:** read only configuration/snapshot/thumbnails. Build explicit
   immutable PendingIntents unique to the widget and slot; carry only the widget ID, slot and
   stable key. Validate them against current configuration and roster at launch. Update on
   configuration, committed local roster/art changes, host size changes and platform update
   requests. Coalesce ordered updates so older work cannot overwrite newer selections.
5. **Foreground navigation:** share guarded profile resolution between directory and widget
   entry points. Preserve directory search/collapse/scroll state and independent phone
   identities. Do not refactor unrelated relay, watch tile or protocol behavior.

Providers use updatePeriodMillis=0; there is no periodic network poll, foreground service,
socket, microphone or wake lock for widgets. System-following resource themes handle host
night/color reinflation; normal platform/update callbacks refresh the view as needed.
Process death/restart and app upgrade rebuild from durable local state. Device reboot before
unlock does not expose credential-encrypted app data or initialize a connection.

Across the entire RemoteViews object, decoded bitmap allocation stays at most the smaller
of 1 MiB and one screen's RGBA size. Select thumbnail resolution by visible count, cache and
reuse bitmap instances across variants; avoid full-size portrait arrays. Verify actual
allocation and transaction behavior on the highest-capacity preset. Android imposes an
additional screen-derived bitmap limit, enforced at target API 37.
[AppWidgetManager update limits](https://developer.android.com/reference/android/appwidget/AppWidgetManager#updateAppWidget(int,%20android.widget.RemoteViews))
are the platform authority; the smaller project limit is deliberate headroom.

## Failure handling and acceptance

Invalid IDs/providers, malformed stores, duplicate keys or stale launch payloads fail closed
to an actionable setup/unavailable surface. One corrupt image cannot prevent other slots
rendering. Removing an instance deletes only its own config and prunes unreferenced thumbnails.
Concurrent widget configuration and catalog changes retain exact stable keys and never
replace an active phone turn. No personal art/endpoints/keys enter public previews or logs.

Required software checks:

- All seven metadata mappings and slot counts; independent instances, atomic cancel/save,
  delete/restore, malformed data, duplicate keys and stale requests.
- Actual RemoteViews inflation/host render at supported small/large dp ranges, portrait and
  landscape, 1.0/1.3 font scale, LTR/RTL, long names, missing art, empty/unavailable slots and
  the sixteen-slot bitmap budget. Safe targets and caption contrast must pass.
- System light/dark and two wallpaper palettes on API 31+, explicit Light/Dark/Moonlight,
  and the API 29–30 fallback. System widget changes leave explicit app appearance intact.
- Native editor grouped search/order/save/cancel, multiple widget instances, host reconfigure,
  process recreation and persistence through a preserving app update.
- Exact stable-key cold/warm MainActivity launch; same-profile idempotence, busy/approval
  deferral, stale/removed keys, navigation cancellation and unchanged directory context.
- No microphone/Gadget connection/identity creation from widget rendering or tap alone;
  rendering never invokes catalog networking. Opening MainActivity may perform its existing
  normal foreground catalog refresh, independently of the validated shortcut payload. Existing
  phone audio/navigation regressions and warning-strict lint/JVM/all APK checks pass.

Physical acceptance requires a preserving phone install, actual One UI placement of the
supported seven presets, custom ordering and exact-target taps, wallpaper/night changes,
multiple instances and a reboot/process restart. A launcher with only four columns cannot
establish the two five-column placements; record that capability limit and test a supported
five-column host rather than marking unsupported placement passed. Existing pairing/artwork,
watch settings and the owner's original phone timeout remain unchanged.

## Review artifacts and scope

The [interactive design mockup](2026-10-13-phone-home-widgets-mockup.html) shows the seven
rows × columns shapes, unobscured synthetic pictures and system/explicit appearance examples.
It is a proposed design using original public fixtures, not an Android host capture.

This pass covers phone portrait shortcut widgets, their editor/cache and exact-profile launch.
Animated home-screen GIFs, inline launcher recording/replies, new server changes, watch tile
configuration changes and Play release are outside this pass. Publication and current
hardware gates stay in docs/release.md.
