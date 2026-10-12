# Set up boxes, profiles and watch pictures

The phone discovers names and pictures from up to three boxes. Discovery does not enroll
the watch or create conversation listeners. Each stock Gadget v0.2.0 profile needs its own
listener, certificate-valid `wss` URL and pairing approval. A box that is off the network
keeps its cached page and picture; the app does not move its conversation to another box.

## What each box needs

| Item | Once per box | Once per profile |
|---|---|---|
| Hermes | Installed, with a working gateway/service | Existing profile home and working model credentials |
| Gadget SDK | Obtain the pinned stock v0.2.0 plugin | Install/enable it in this profile's plugin scope |
| Conversation | TLS reverse proxy and private access rules | A distinct loopback port and explicit `wss` URL |
| Catalog | Isolated publisher, catalog-only credential, HTTPS route | Stable ID, short name, explicit conversation URL and optional portrait |
| Phone | Save the HTTPS catalog URL and its credential; enable relay | Optional watch picture; optional manually saved endpoint token |
| Watch | Install the matching app; review discovered pages | Review the URL, connect, approve pairing on the correct profile's box |

The catalog credential allows only catalog/portrait reads. It is separate from an optional
Gadget access token and from the watch's device key. Never paste credentials into URLs,
public issues or repository files. The phone stores source credentials and picture choices
encrypted with Android Keystore. Keep server mapping, credentials, state and personal art
in owner-only storage outside the public repository.

## Configure every conversation profile

Use the [pinned SDK integration guide](https://github.com/Adolanium/hermes-gadget-sdk/blob/323e3303ab68981f810fc3208119cd8a22e64af0/docs/hermes-integration.md)
and its `plugin/` directory. The plugin must be available and enabled in each profile's
home, rather than installed only in the default home. Merge `gadget` into that profile's
existing `plugins.enabled`; preserve other plugins and remove only a deliberate Gadget
disable entry. Older Hermes installations with no allow-list need the normal plugin
migration first; replacing their selection with `[gadget]` can disable unrelated plugins.

Merge this block into the selected profile's `config.yaml`. This example port is a
placeholder: check current listeners and assign an unused, distinct port to every profile.

```yaml
platforms:
  gadget:
    enabled: true
    extra:
      host: 127.0.0.1
      port: 8770
      path: /gadget
      speak_replies: true
      auto_home: true
      unauthorized_dm_behavior: pair
```

Do not point all profiles at the default profile's `/gadget`. The stock protocol does not
choose a profile through a catalog ID. Separate listeners keep agent configuration,
conversations, approval policy and device stores separate. Do not copy enrollment databases
or device keys into another profile. Keep existing `home_channel` and token settings when
updating an already used profile.

Check the effective administrator/managed `plugins.enabled` allow-list too. A managed list
can override the profile's selection; the administrator must add `gadget` while preserving
the other enabled plugins. Installing the plugin and enabling it only in the profile file
will not start a listener when the managed list excludes it.

Check for administrator/managed configuration that pins `platforms.gadget.extra.port` or
`home_channel` across all profiles. Such a pin overrides the profile files and causes port
collisions or incorrect home channels. The administrator must scope these settings correctly;
the default profile's settings belong to that profile, not a host-wide Gadget pin.

Activate the configuration through the Hermes gateway. A current host multiplexer can serve
multiple profiles, each with a separate Gadget adapter. Newly installed plugins can require
plugin rediscovery before the profile rescan. A failed adapter already queued for reconnect
may retain its old port; a controlled profile/service reload is then needed. Check running
work before restarting. Do not force a second gateway onto a profile already served by the
host: that can duplicate its other bot connections. Older gateways may require a normal
restart or separate, deliberately managed profile services.

Verify a listener on each assigned loopback port. Then publish each through TLS, following
[self-host TLS setup](self-host.md). With Tailscale Serve, preserve existing routes and use
the printed DNS name and port in `wss://…/gadget`. Keep Funnel off for private relay access.
Grant the phone owner only the exact box addresses and necessary TCP ports; a catalog grant
alone does not grant the additional conversation ports. Validate certificate trust and a
real WebSocket upgrade for **each** URL before putting it in the catalog. An HTTP dashboard
or successful TCP connection does not prove a Gadget endpoint works.

## Publish the box catalog

Follow [publisher setup](../server/catalog/README.md). Install Pillow in a dedicated
environment and run the publisher on loopback behind HTTPS. Use a fresh catalog-only bearer
credential, a persistent private state directory, and an explicit owner-reviewed mapping:

```json
{
  "profiles": [
    {
      "id": "stable-profile-id",
      "name": "My profile",
      "home": "/absolute/path/to/profile-home",
      "gadget_url": "wss://my-box.example:8770/gadget",
      "avatar": "assets/avatar.png"
    }
  ]
}
```

Use actual paths and verified URLs, not these placeholders. Names must be 1–32 characters;
give a longer source name a short watch alias without renaming the Hermes profile. Preserve
IDs through display-name changes and home moves. A null `gadget_url` intentionally publishes
a picture-only page. The publisher reads existing portraits without changing their originals.
It serves normalized, metadata-free PNGs at most 256×256 and 256 KiB.

Use an owner-context service with explicit working directory, interpreter, arguments and
restart policy. On Windows, an interactive-token Scheduled Task starts at the owner's login
and runs while the session is locked. It does not run while that owner is signed out; an
unattended boot service needs its own deliberate account/service configuration. Set catalog
state and asset directory ACLs explicitly for that user SID. An elevated SSH process can
otherwise create a Python mode-0700 directory that the limited interactive task cannot read.
Provision the Python environment with the normal Windows environment, including `SystemRoot`;
an SSH shell can omit it. Do not give broad permissions to resolve these errors.

Check unauthenticated `/catalog/v1` returns 401, authenticated catalog and every portrait
return 200, and hashes/lengths match. Check non-catalog paths return 404. Repeat through the
HTTPS front end with default certificate/hostname verification. Finally verify from the
actual phone; host-side checks and access-policy propagation alone are insufficient.

## Add the sources and pair on the watch

1. On the phone, open **Quantum Relay for Hermes → Settings → Boxes and discovery**. Enter the box
   name, full HTTPS URL ending in `/catalog/v1`, and its catalog-only credential. Save and
   refresh. Repeat for each box. Keep the phone's tailnet VPN connected when using tailnet URLs.
2. Enable relay and grant the requested local-network/nearby/background companion permissions.
   Use **Allow background relay** for the Android companion association. See [relay setup](self-host.md#enable-phone-relay).
3. Open Quantum Relay for Hermes and swipe to a discovered profile. Review its proposed
   connection URL. Select **Automatic** or **Phone relay** for private tailnet `wss` access.
   The phone forwards watch-owned TLS; it does not receive the pairing key.
4. Save/connect on the watch. Approve the displayed code on the **correct box and profile**,
   using the server's exact displayed command. For a named profile this is normally
   `hermes -p PROFILE pairing approve gadget CODE`; for default, omit `-p PROFILE`.
5. Confirm paired state, send a short typed message, then hold to talk. Confirm the text,
   transcript and spoken reply. Repeat per profile; one approval does not pair every page.

These new profiles cannot be silently paired from the catalog. Existing approved endpoints
retain their own identities; a new URL receives a new identity. Profiles do not copy each
other's keys, tokens, drafts, voice settings or conversations.

## Use box pictures or watch-specific pictures

Imported box pictures are the default. In the phone's Profiles page, open the profile's
**Picture** control. Choose **Choose picture** to use the system photo picker, or
**Use box picture** to return to the latest imported image. The app does not edit the box's
original. Overrides use the box/profile identity, so identical profile IDs on different boxes
stay separate. Choices survive refreshes, offline periods and restart, including a profile
that has not yet been paired. A validated pet can later replace the portrait during conversation;
portrait choices remain separate from pet packages and endpoint enrollment.

For custom round portraits, export an exact **256×256 RGBA PNG**, maximum **256 KiB**,
transparent corners, a centered circle and no embedded labels. Suggested geometry: visible
circle diameter 236 px, critical face/headgear within diameter 176 px. Keep a 1024×1024 master
on a 4× pixel grid if using pixel art. The app renders names separately. Test at actual watch
size rather than judging only the full-size master.

An animated watch override uses the same **Picture → Choose picture** picker. Export a GIF
with a canvas at most **256×256**, encoded size at most **256 KiB**, at most **120 frames**
and a loop at most **10 seconds**. Delays below 50 ms are clamped, so check the loop duration
after that adjustment. The total canvas pixels across frames must be at most **2,097,152**:
at 256×256 that permits 32 frames. Keep transparent corners and important features inside
the circle. The app strips extra GIF metadata, validates frame data and saves a PNG first
frame for compatibility and paused display.

Only the selected available foreground watch page animates. Neighbor pages, reduced-motion
and battery-saver modes use the PNG; ambient mode uses a dim monochrome static representation.
A validated pet takes priority. The phone shows the static portrait inline. **Use box picture**
removes the local animation override and restores the latest box portrait. GIFs are portrait
loops, not state-driven pets or speech mouth animations.

If picture synchronization fails, the phone keeps the pending selection and the published
animation until a retry succeeds. One pending GIF is allowed in addition to the settled
32-asset/8-MiB cache. Sync that picture before importing another distinct GIF. Refresh/retry
from the app; do not reset pairing or erase app storage to resolve a picture transfer.

## Before leaving the home network

Keep the phone's tailnet VPN connected, Hermes relay enabled, and its association with the
watch active. Use the saved `wss` profile in Automatic or Phone relay mode. With phone Wi-Fi
off, test one short request on each box you plan to use; confirm **Phone relay · watch TLS**,
the actual reply, then a fresh connection after locking the phone. Restore Wi-Fi afterward.

Each box must remain powered on, awake and connected to its tailnet. A locked desktop can
continue serving; sleeping, signed-out or powered-off hosts can be unavailable. Owner-login
Windows jobs require that owner's logged-in session. Check plugged-in sleep settings and
laptop lid behavior explicitly; app installation does not keep a server awake. Plan to use
an available box when another is away or asleep. Cached pages and pictures remain browsable.

Pair once per endpoint before departure. The catalog credential, Gadget pairing and model/
speech provider credentials are separate. A paired page can still return a provider auth or
rate-limit error; resolve that on the correct box/profile rather than resetting watch keys.
Run a hold-to-talk check and listen to the selected profile's voice before depending on it.

## Understand setup and connection messages

| Message or symptom | Meaning and next step |
|---|---|
| No conversation endpoint / Watch setup needed | Catalog has no Gadget URL. Configure that profile's listener/TLS mapping, then refresh. |
| Review setup | A URL exists but needs watch-owner review. Open that profile's setup on the watch. |
| Pairing code / Unpaired | Listener is reachable; approve this watch on that profile's box. |
| Offline / cached page | Box or network is unavailable. Cached art remains; reconnect when it returns. |
| Connection needs attention | Check HTTPS trust, catalog credential, limits and actual phone reachability. |
| Every listener attempts the same port | Check managed configuration and stale adapter configuration; assign distinct ports. |
| Catalog works in SSH but returns 503 from the Windows task | Check the task owner's access to state/assets and its environment. |
| Picture refresh replaces a custom portrait | Choose an explicit watch override; a source refresh must retain it. |

Release acceptance still includes real phone/watch delivery, per-profile pairing and speech,
off-network/offline recovery, locked-phone behavior, battery and publication gates in
[release.md](release.md). Infrastructure checks alone do not complete those gates.


## Talk or chat on the phone

Quantum Relay for Hermes opens on **Profiles**. Tap a full-width box header to expand or collapse its profiles. The header shows the box name,
profile count and catalog status; expanded profiles sit inside that box panel. Search names, or enable
**Reorder** and use the accessible up/down controls. Box order and the order within each box
are saved; the watch receives that same profile order. Catalog availability describes discovery,
not an authenticated Gadget conversation. **Watch** contains relay/association controls;
**Settings** contains catalogs, manual watch endpoints, voice configuration and recording cues.

1. On a profile row, choose **Talk/Chat** and review its canonical `wss` endpoint.
2. Choose **Connect this phone**. Grant local-network access if Android requests it for your
   LAN/tailnet. The phone creates its own identity only after this explicit action.
3. Approve the phone's displayed code on that exact box/profile with the server's displayed
   command. A watch approval does not approve the phone; no watch key is transferred.
4. Send a short text, or hold **Hold to talk**. Release sends; drag down cancels. Screen readers
   can use Start/Finish recording. Microphone permission is requested on recording action;
   after granting it, hold again. Listen for start/finish/cancel cues and the profile's voice.
5. **Stop voice**, **Cancel turn**, approval buttons and **New session** are explicit controls.

Phone conversations require TLS and the phone's own tailnet connectivity. Initial phone audio
uses server speech, including that profile's configured voice. Watch BYOK settings remain a
separate watch feature. Foreground phone conversations close and stop audio when you leave the
screen or lock the phone; the watch relay continues independently. Drafts and displayed replies
survive screen rotation; reconnect explicitly after returning. Messages are never replayed on
reconnect. A corrupted phone identity fails closed rather than silently re-pairing the watch.
