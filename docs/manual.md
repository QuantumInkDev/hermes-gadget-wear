# Quantum Relay for Hermes: user guide

Your own Hermes agent on your phone and your wrist. Hold to talk, read the reply as it streams
in, hear it in that profile's voice, and answer Hermes's questions with a tap. Everything stays
between your devices and your own computer.

Set things up first with the [install guide](install.md). Features marked **Relay** need the
Relay preview server (see [tiers](install.md#what-you-need)).

## Contents

- [The idea in one minute](#the-idea-in-one-minute)
- [On the watch](#on-the-watch)
- [On the phone](#on-the-phone)
- [Conversations, inbox and handoff](#conversations-inbox-and-handoff-relay)
- [Voice](#voice)
- [Pictures and pets](#pictures-and-pets)
- [Shortcuts: tiles, widgets, quick actions and sharing](#shortcuts)
- [Away from home](#away-from-home)
- [Privacy and security](#privacy-and-security)
- [Accessibility](#accessibility)

## The idea in one minute

- **Box:** a computer running Hermes.
- **Profile:** one Hermes personality on that box, with its own model, memory, voice and
  picture. A box can have many profiles.
- **Pairing:** each device gets its own key per profile. You approve it once on the box with
  `hermes pairing approve`. Nothing gets in without that approval.
- **Relay:** the watch reaches your box through your phone. The phone only forwards encrypted
  traffic it can't read; the encryption runs from the watch to the box.

## On the watch

### Talk

- **Swipe left or right** to change profile. Each profile keeps its own draft and scroll
  position.
- **Hold the picture to talk.** Let go to send; drag down to cancel. A tick and a ring tell
  you recording really started.
- **Type** instead, from the message field below the picture.
- The reply streams in as text and is spoken in the profile's voice. Scroll down to read more.
- **Hold Cancel for two seconds** to start a new session with that profile.

### Approvals and questions

When Hermes wants permission to run something, or asks you a question, the watch shows it
with the buttons for your answer. Other controls lock until you answer, and taps in the first
half second are ignored so you can't approve by accident. Approvals expire on the box's
schedule, never early.

### Find a profile

Scroll to the end of any profile to reach **Jump to profile**, or open **Choose a profile** to
see them all. The order matches the phone (see [Arrange profiles](#arrange-profiles)).

### Conversations & inbox (Relay)

Open **Conversations & inbox** from a profile to see recent conversations and tasks on the
watch, and pick up where you left off.

### Walking mode

Turn on **Walking mode** for bigger controls and shorter spoken answers. Replies play on your
earbuds when they're connected, and on the watch speaker otherwise. The full answer stays on
screen. You still hold to talk; nothing listens in the background.

### Watch face, tiles and buttons

- **Ask Hermes** tile: shows the last reply. Tap to open, then hold to talk.
- **Focus** and **Pinned profiles** tiles: one favourite profile, or up to four. Choose them
  on the phone under **Watch → Edit profile tiles**, then add the tile from the watch's tile
  list.
- **Hermes state** complication: shows idle, busy or waiting on a watch face. It never shows
  message text.
- **Galaxy Watch Ultra:** assign Quantum Relay to the Action button or to a double press of
  Home under Settings → Buttons and gestures, if your watch offers it.
- **Ambient mode** shows a quiet outline of the profile until you raise your wrist.

## On the phone

The phone has four tabs: **Profiles**, **Inbox**, **Watch** and **Settings**.

### Profiles

All your profiles, grouped by box. Tap a box header to fold it. Search finds profiles and
boxes by name. A box that's offline keeps its cached pictures.

Tap a profile for **Talk/Chat**: the same conversation on the phone, with its own pairing.
**Connect this phone** pairs it the first time. Hold to talk, type, **Stop voice**,
**Cancel turn** and **New session** all work as on the watch.

### Arrange profiles

Tap **Arrange** on the Profiles page. Drag a profile by its ≡ handle, or long-press the row,
to move it within its box. Use the arrows on a box's header to move the whole box. Tap
**Done**. The watch carousel and its profile list use the same order.

### Watch

Relay on/off, the background relay permission, profile tiles and the state of the connection
to the watch.

### Settings

| Section | What's there |
| --- | --- |
| Boxes and discovery | Add a box directory (catalog) and its key; refresh |
| Connections & speech | Saved connections, manual watch endpoints, ElevenLabs voice |
| Appearance | Light, Dark or Electronic Moonlight. The watch has its own setting |
| Home screen widgets | Layouts for the phone widgets |
| Recording sound cues | Start and stop sounds |
| Move setup | Setup codes and configuration export and import |

### Connection health

In Talk/Chat, **Connection health** shows each step of the connection (network, TLS, pairing,
request) and where it stopped. **Share report** produces a report you can attach to a bug. It
leaves out keys, messages, pictures and private addresses.

## Conversations, inbox and handoff (Relay)

### Conversations that follow you

Conversations are saved on the box, the real Hermes session, not a copy on your phone. Start
on the watch, then use **Continue exact conversation** on the phone, or the other way round.
Conversations can be renamed, bookmarked, searched and deleted. The phone keeps an encrypted
text copy for 90 days so search works offline. Deleting on the phone removes that copy; it
doesn't delete the conversation on the box unless you choose that.

### Inbox

The **Inbox** tab collects work from every profile on every box: **Working**,
**Needs input**, **Finished** or **Failed**. Tap an item to open the right profile and
conversation. Notifications are private on the lock screen by default and never speak aloud
on their own.

### Catch-up after a disconnect

If a reply finishes while your watch or phone is offline, the box holds it for up to 24 hours
and delivers it when you reconnect, once, with no duplicates. Recordings, approvals and
actions are never replayed.

### Hand off to another profile

In Talk/Chat, **Hand off** carries selected messages to a different profile, for example from
your research profile to your writing profile. You choose the messages and review the text
before anything is sent. Nothing else from the conversation goes along.

### Wake a box (Relay)

If a box is asleep, **Wake a box** asks an awake box on your network to wake it. This only
works for machines the box owner has allow-listed and whose hardware supports it. "Wake sent"
means the request went out, not that the box is ready yet.

### Export history

Conversation history can be exported to a file, encrypted with a passphrase you choose. The
format is documented in [history-export-format.md](history-export-format.md).

## Voice

- **Server voice** is the default: the box's own text-to-speech, in each profile's configured
  voice.
- **Phone voice (ElevenLabs)** is optional. Add your own key under
  **Settings → Connections & speech**, shared or per profile. Usage is billed to your
  ElevenLabs account. The key stays in your phone's encrypted vault and is never sent to
  Hermes. Walking mode's short answers and **Tell me more** use phone voice.
- **Opus** audio is used automatically when both sides support it, for smaller and faster
  audio. Otherwise the apps fall back to plain PCM.

## Pictures and pets

- **Pictures** come from the box directory. You can replace one per profile on the phone with
  **Watch picture → Choose picture** (PNG, or a GIF up to 10 seconds). **Use box picture**
  undoes it.
- **Pets (Relay server build)** are animated characters that listen while you talk, talk
  while the reply plays and nap when idle. Choose **Portrait** or **Pet** for each profile on
  the watch under **Artwork**. Only the profile on screen animates. Reduced motion, Battery
  Saver and ambient mode show a still picture.
- To make or update a pet, see [Pets in the install guide](install.md#pets).

## Shortcuts

- **Home screen widgets:** seven sizes from 1×1 to 4×4, each with its own profiles, order and
  theme. Tap a face to open that profile.
- **Quick actions:** saved prompts tied to one profile, such as "Summarise my inbox" for your
  assistant profile. Create, edit, reorder and add them to your home screen as Android
  shortcuts. You always see the full prompt before it sends. Nothing runs on a schedule.
- **Share to a profile:** share text or a link from any app to a profile. You review the
  recipient and the content before sending.
- **Setup codes:** **Settings → Move setup → Show a setup code** shows a QR code with one
  connection address, for adding it to another phone. It contains no keys.

## Away from home

Keep Tailscale on and the relay enabled on the phone. The watch then reaches your boxes from
anywhere your phone has signal. Each box must stay on and awake; a sleeping laptop can't
answer. Before you rely on it, turn the phone's Wi-Fi off and test one message per box.

## Privacy and security

- No account, no hosted server, no analytics, no ads. The apps talk only to the boxes you
  add, and to ElevenLabs if you turn on phone voice.
- Each device and each profile has its own key, kept in Android Keystore. Keys are never
  exported or shared between devices.
- Encryption runs from the watch to your box. The phone relay forwards bytes it can't read.
- Private lock-screen notifications, no automatic speech, and no background microphone.
- Connections that aren't encrypted are allowed only to private network addresses, and only
  after you confirm per address. TLS is never quietly downgraded.

Full details: [privacy policy](privacy.md).

## Accessibility

Every control has a screen-reader label. Recording has Start and Finish buttons as well as
hold to talk, and profiles can be moved with accessibility actions instead of dragging. Text
follows your font size. Reduced motion stops animations. All three themes are checked for
contrast.
