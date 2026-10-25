# Announcement copy

Drafts only. Post them after the repo, the install guide and a downloadable build are live.
Add the demo video wherever there's a video slot. Replace `<link>` with the landing page or the
repo. Never show personal pets, profile names or tailnet addresses in the attached media.

Things the copy deliberately doesn't claim yet: battery life, Play availability before it
exists, catalog listing, endorsement by Nous Research.

## X / Twitter

### Single post

> I put my Hermes agent on my wrist.
>
> Quantum Relay: a Wear OS + Android app for your self-hosted Hermes. Hold to talk, approve
> with a tap, continue on your phone. No account, no cloud, just your box over Tailscale.
>
> Beta → <link>

(About 240 characters with a 23-character link. Recount after editing. Edit "the past few
weeks" in the LinkedIn post to match the real timeline.)

### Thread

1. > I put my Hermes agent on my wrist. 🧵
   >
   > Quantum Relay is a Wear OS watch app and Android phone app for your own self-hosted
   > Hermes Agent. Beta is open. [video]

2. > Hold the watch face, ask, let go. The reply streams in and plays in that profile's own
   > voice. When Hermes needs permission to run something, the approval shows up on your
   > wrist.

3. > Every Hermes profile gets a page. Swipe between your research agent, your writing
   > agent, your ops agent. Each has its own animated pet that listens while you talk and
   > talks while it answers. [pet gif]

4. > Conversations live on your own box, not in an app cache. Start one on the watch while
   > walking, continue the exact same session on your phone. A task inbox shows what every
   > agent is working on, waiting on, or finished.

5. > No account. No hosted server. No analytics. The watch reaches your box through your
   > phone over Tailscale. The encryption runs from the watch to your box, so the phone
   > relay can't read it.

6. > Built for real use: walking mode with short spoken answers on your earbuds, catch-up
   > for replies that finished while you were offline, home screen widgets, share-to-agent,
   > and full screen-reader support.

7. > Open source (MIT). Needs Hermes, an Android phone, and optionally a Wear OS watch. Setup
   > guide and beta builds: <link>
   >
   > Built on the community Hermes Gadget SDK. Independent project, not affiliated with Nous
   > Research.

## LinkedIn

> **I put my AI agent on my wrist.**
>
> For the past few weeks I've been building Quantum Relay, an Android and Wear OS client for
> Hermes Agent, the open-source agent framework from Nous Research. It's now in beta.
>
> The idea is simple: my agents run on my own machines, and I want to reach them from
> wherever I am without handing my conversations to another cloud service.
>
> What it does:
> • Hold the watch face to talk; the reply streams in and is spoken in that agent's voice
> • Approve or deny an agent's request to run a command, from your wrist
> • Swipe between agents (research, writing, operations), each with its own animated pet
> • Continue the exact same conversation on your phone, with a task inbox across every agent
> • Walking mode: short spoken answers on your earbuds, with the full text kept on screen
>
> How it works: the watch connects through the phone over a private Tailscale network to
> Hermes running at home. The connection is encrypted end to end between the watch and my
> computer; the phone just forwards bytes. No accounts, no analytics, no hosted backend.
>
> A few things I learned building it:
> 1. On a watch, the hard part is the half second around the interaction: knowing for sure
>    the mic started, never sending a stray tap as an approval, and failing loudly rather
>    than silently.
> 2. "Offline-first" for an agent means being honest about state. A reply that finished
>    while you were away should arrive exactly once, and an approval should never replay.
> 3. Accessibility from day one (screen-reader labels, large fonts, reduced motion) shaped
>    the design for the better, not just for compliance.
>
> It's open source under MIT and built on the community Hermes Gadget SDK. If you run Hermes
> and have an Android phone (a Wear OS watch is optional), I'd love beta testers:
> <link>
>
> #AI #AIAgents #WearOS #Android #OpenSource #SelfHosted #Kotlin

## Portfolio

### One line

> Quantum Relay: a private Wear OS and Android client for self-hosted Hermes AI agents.
> Hold to talk, approve from your wrist, continue on your phone.

### Card (about 50 words)

> **Quantum Relay for Hermes.** A Wear OS watch app and Android phone app that put
> self-hosted Hermes AI agents on your wrist. Hold to talk, answer approvals, swipe between
> agents with animated pets, and continue the same conversation on your phone. End-to-end
> encrypted over Tailscale, with no accounts or cloud.

### Project page

> **Quantum Relay for Hermes**
> *Android · Wear OS · Kotlin · Jetpack Compose · Python*
>
> **The problem.** Self-hosted AI agents live on a desktop. Reaching one away from the desk
> usually means a chat bridge through someone else's cloud, and none of them work well on a
> watch.
>
> **What I built.** A native Wear OS app and Android companion for Hermes Agent. The watch is
> the primary interface: hold to talk, read the streamed reply, hear it in each agent's own
> voice, and answer command approvals with a tap. The phone adds a profile directory across
> several machines, conversations you can continue between devices, a task inbox,
> handoff between agents, home screen widgets and share-to-agent.
>
> **How it works.**
> - The watch keeps its own TLS session to the Hermes host. The phone relays the encrypted
>   bytes over Tailscale without being able to read them.
> - Every device and agent pair has its own Keystore-backed key, approved once on the host.
> - Server extensions to the Gadget plugin add durable per-device history, task lifecycle
>   events, a bounded reconnect outbox, Opus audio, and animated pets with listening,
>   talking and sleeping states.
> - A reusable "Pet Factory" pipeline turns a character brief into a validated sprite
>   atlas, watch companion poses, a personality file and a voice.
>
> **Craft.**
> - Strict, warning-free builds with over 1,200 tests.
> - Three themes, contrast-checked.
> - Full TalkBack support and reduced-motion fallbacks.
> - Every failure mode stated explicitly: no silent TLS downgrades, no replayed approvals.
>
> **Status.** Open beta, MIT licensed. [GitHub] [Install guide] [Demo video]

## Store listing (Play)

- **Title:** Quantum Relay for Hermes
- **Short description (80 characters max):** Talk to your own self-hosted Hermes agent
  from your phone or Wear OS watch.
- **Full description:**

> Quantum Relay puts your own Hermes Agent on your wrist and in your pocket.
>
> Hold your watch face to talk, read the reply as it streams in, and hear it in your agent's
> own voice. When Hermes asks to run something, approve or deny it with a tap. Swipe between
> your Hermes profiles, each with its own picture or animated pet.
>
> On your phone: a directory of every profile on every computer you run Hermes on,
> conversations you can continue between watch and phone, a task inbox across all your
> agents, home screen widgets, quick actions and share-to-agent.
>
> Private by design: no account, no hosted server, no analytics, no ads. Your watch connects
> to your own computer through your phone over Tailscale, encrypted end to end.
>
> Requires Hermes Agent running on your own computer with the Gadget plugin, and Tailscale
> on that computer and your phone. Some features need the Quantum Relay server build. Phone
> voice is optional and uses your own ElevenLabs key and billing.
>
> Quantum Relay is an independent open-source project. It is not affiliated with or endorsed
> by Nous Research, Google, Samsung, Tailscale or ElevenLabs.

## GitHub

- **Repo description:** Wear OS and Android client for your self-hosted Hermes Agent: hold
  to talk, approvals, cross-device conversations, task inbox and profile pets.
- **Topics:** `hermes-agent`, `wear-os`, `android`, `jetpack-compose`, `kotlin`,
  `ai-agent`, `self-hosted`, `tailscale`, `voice-assistant`.

## Hermes community post (Discord or Discussions)

> Hi all. I've built a Wear OS and Android client for Hermes on top of the Gadget plugin, and
> it's in beta. Watch hold-to-talk, approvals on your wrist, a page per profile with
> animated pets, and phone Talk/Chat. The core works with the catalog `gadget` plugin. The
> extended features (history across devices, task inbox, offline catch-up) currently need
> a core patch. I'm proposing proper hooks for them upstream and would welcome feedback on
> the design. Setup guide: <link>
