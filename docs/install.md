# Install Quantum Relay for Hermes (beta)

Quantum Relay puts your own Hermes agent on your Android phone and Wear OS watch. Hermes
keeps running on your computer. The apps connect to it privately over Tailscale. There is no
Quantum Relay account and no hosted server, and nothing is sent to us.

> **Beta.** Expect rough edges, and please report them (see [Get help](#get-help)).
> Placeholders in angle brackets, like `<your-box>`, are yours to fill in. Never post your real
> addresses, keys or tailnet names in an issue.

## What you need

| | Requirement |
| --- | --- |
| Hermes host | A Mac, Linux or Windows computer running [Hermes Agent](https://github.com/NousResearch/hermes-agent) with a working model. It must be on and awake when you want to talk to it |
| Phone | Android 10 or later |
| Watch (optional) | Wear OS 4 or later, paired with that phone. Tested on Galaxy Watch Ultra |
| Network | A free [Tailscale](https://tailscale.com) account, with Tailscale on the host and the phone |
| Optional | `ffmpeg` on the host for Opus audio; an ElevenLabs key for phone voice (billed to you) |

There are two tiers:

- **Core** works with stock Hermes and the Gadget plugin from the Hermes plugin catalog. You
  get talking and typing, spoken replies, approvals, several profiles, pictures, watch tiles,
  phone widgets and the phone relay.
- **Relay preview** adds conversation history, the task inbox, offline catch-up, handoff and
  remote wake. It needs the Quantum Relay server build and a patched Hermes. During the beta
  we set it up with you one to one. Ask in Discussions.

## 1. Connect the host and phone with Tailscale

1. Install Tailscale on the Hermes host and on the phone. Sign in to the same tailnet on both.
2. On the host, enable HTTPS certificates for your tailnet if Tailscale asks you to (admin
   console → DNS → HTTPS Certificates).
3. Keep Tailscale connected on the phone whenever you use the app away from home.

Your watch does not need Tailscale. It reaches Hermes through the phone.

## 2. Install the Gadget plugin on the host

```bash
hermes plugins install gadget
hermes plugins enable gadget
```

Hermes loads a separate copy of the plugin for every profile. Repeat for each profile you want
on the watch, with `-p <profile>`:

```bash
hermes -p <profile> plugins install gadget
hermes -p <profile> plugins enable gadget
```

## 3. Give each profile its own listener

Each profile needs its own port. Add this to the profile's `config.yaml` and use a different
unused port for every profile (8765, 8766, 8767 …):

```yaml
platforms:
  gadget:
    enabled: true
    extra:
      host: 127.0.0.1      # only this computer; Tailscale publishes it in step 4
      port: 8765
      path: /gadget
      speak_replies: true
      unauthorized_dm_behavior: pair
      # Optional, only with the Quantum Relay server build:
      # enable_pets: true
      # enable_opus: true
      # enable_client_tts: true
```

Restart the Hermes gateway. Check that it's listening:

```bash
hermes gadget info
```

## 4. Publish each listener privately with Tailscale Serve

First check what is already served, so you don't overwrite anything:

```bash
tailscale serve status
```

Then publish each profile's port on the tailnet only:

```bash
tailscale serve --bg --https=8765 http://127.0.0.1:8765
```

Your profile's address is `wss://<your-box>.<your-tailnet>.ts.net:8765/gadget`. That's the
HTTPS name Tailscale prints, with `https` replaced by `wss`, plus the port and `/gadget`.

Don't use **Tailscale Funnel** unless you really want the endpoint on the public internet.
The phone relay doesn't need it.

To remove a listener later: `tailscale serve --https=8765 off`.

## 5. Optional: publish a box directory

With a directory (the "catalog"), the phone finds every profile on a box with its name and
picture, and you don't type addresses by hand. Follow
[the catalog publisher guide](../server/catalog/README.md). It runs a small read-only service
with its own key. You can skip this step and add profiles manually in the app instead.

## 6. Install the apps

**From Google Play (closed testing):** open the tester link from your invite on the phone and
install. The watch app appears in the Play Store on the watch, under "Apps on your phone".

**From GitHub Releases (APK):**

1. Download `quantum-relay-phone-<version>.apk` and `quantum-relay-watch-<version>.apk` from
   the [releases page](https://github.com/QuantumInkDev/hermes-gadget-wear/releases).
2. Phone: open the APK and allow installing from your browser when Android asks.
3. Watch: enable **Developer options** (Settings → About watch → Software → tap Software
   version five times). Then turn on **Wireless debugging** and tap **Pair new device**. On a
   computer with Android platform-tools:

   ```bash
   adb pair <watch-ip>:<pairing-port>      # enter the code the watch shows
   adb connect <watch-ip>:<port>
   adb install quantum-relay-watch-<version>.apk
   ```

The phone and watch apps must come from the same source. A Play phone app and a GitHub watch
app can't talk to each other.

## 7. Set up the phone

1. Open **Quantum Relay for Hermes**.
2. Add your profiles:
   - With a directory: **Settings → Boxes and discovery**, enter the box name, its
     `https://…/catalog/v1` address and the catalog key, then **Refresh**.
   - Without one: on **Profiles**, open the ⋮ menu → **Manual setup**, then enter a name and
     the `wss://` address from step 4.
3. Open **Watch** and turn on the relay. Tap **Allow background relay** and accept Android's
   companion dialog. Grant the nearby-devices and local-network permissions it asks for.

## 8. Pair the watch

1. Open Quantum Relay on the watch and swipe to a profile.
2. Review the address and choose **Automatic (phone preferred)**, then **Save & connect**.
3. The watch shows a pairing code. On the host, approve it for **that** profile:

   ```bash
   hermes -p <profile> pairing approve gadget <CODE>    # leave out -p for the default profile
   ```

4. Type a short message, then hold the picture and speak. Let go to send.

Every profile pairs separately. Approving one doesn't pair the others.

## 9. Pair the phone (optional)

To talk from the phone too: on the **Profiles** page, tap a profile, then **Talk/Chat** →
**Connect this phone**. Approve its code the same way. The phone has its own identity; it
never receives the watch's key.

## Pets

A pet is an animated character for a profile. It listens while you hold to talk, talks while
the reply plays, and sleeps when idle. Pets need the Quantum Relay server build with
`enable_pets: true`.

### Add a pet

1. Make the pet. The easiest way is the **Pet Factory** skill in this repo
   (`skills/hermes-pet-factory`). Ask your agent "Use Pet Factory to create a pet for my
   research profile". It walks you through look, animation, personality and voice, and asks
   for your approval at each step. You can also use any existing Hermes desktop pet.
2. Install it into the profile. Copy the complete pet folder (`pet.json`, the sprite sheet
   and the watch companion sheet) into the profile's pets directory. Then set the profile's
   `display.pet.slug` to the folder name. Pet Factory does this for you after you approve.
3. Give an existing desktop pet watch poses (listening, talking, sleeping):

   ```bash
   python tools/pet-companion.py --metadata <pet.json> --companion <watch-sheet.png> \
     --output-dir <new-folder> --mouth-box L T R B
   ```

4. Show it on the watch. On the profile's page on the watch, scroll down to **Artwork** and
   choose **Pet**. A new pet never replaces your picture automatically.

### Update an existing pet

1. Install the new version under a **new** folder name (for example `nova-v2`) and point
   `display.pet.slug` at it. Keep the old folder until you're happy, so you can switch back.
2. Reconnect the watch to that profile. The watch notices the changed pet and downloads it
   again. It keeps up to 64 MiB of pets and drops the least recently used first.
3. To roll back, set `display.pet.slug` back to the old folder.

Pictures work without a pet. On the phone, open the profile's options →
**Watch picture → Choose picture** and pick a
PNG or a short GIF (up to 256×256, 256 KiB). **Use box picture** goes back to the directory
image.

## Update

- **Apps:** install the new APKs over the old ones, or let Play update them. Your pairing and
  settings stay. Never uninstall to "fix" something; that deletes your pairing.
- **Plugin:** `hermes plugins update gadget` for each profile.
- **Relay preview testers:** do **not** run `hermes update` until the release notes confirm
  your new Hermes version. An update can remove the patch the relay features need.

## Move to a new phone

**Settings → Move setup → Export configuration** saves profiles, pictures, quick actions and
widget layouts to a passphrase-protected file. Import it on the new phone. Keys and pairing
are never exported, so pair the new devices again.

## Uninstall

1. Phone and watch: uninstall the app. Your pairings on the host stay until you remove them.
2. Host: `hermes gadget devices` lists paired devices; `hermes gadget forget <id>` removes one.
3. `hermes -p <profile> plugins disable gadget`, then remove the `platforms.gadget` block.
4. `tailscale serve --https=<port> off` for each listener you added.

## Troubleshooting

| You see | Do this |
| --- | --- |
| Pairing code | The host is reachable. Approve the code for the right profile (step 8) |
| Offline / cached picture | The host is asleep or off, or the phone lost Tailscale. Check both |
| Connection needs attention | Check that the address is `wss://`, that the Tailscale name is right and that Serve is running |
| Watch setup needed | That profile has no address in the directory. Add the listener and refresh |
| Every profile fails on the same port | Each profile needs its own port (step 3) |
| Replies show as text but don't speak | Check text-to-speech is set up in Hermes for that profile |

## Get help

Open a [GitHub Discussion](https://github.com/QuantumInkDev/hermes-gadget-wear/discussions)
for setup questions, or an [issue](https://github.com/QuantumInkDev/hermes-gadget-wear/issues)
for bugs. For a connection problem, attach a report from **Talk/Chat → Connection health →
Share report**. It leaves out keys, messages and addresses. Report security problems privately
as described in [SECURITY.md](../SECURITY.md).

Quantum Relay is an independent project. It is not affiliated with or endorsed by Nous
Research, Google, Samsung, Tailscale or ElevenLabs.
