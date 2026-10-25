# Beta launch kit

Everything needed to put Quantum Relay for Hermes in front of beta testers. Nothing here has
been published. Merging, signing, Play upload, the plugin submission and the posts are each a
separate step you start yourself.

| File | What it is |
| --- | --- |
| [This page](README.md) | Blockers, decisions, and the full launch checklist |
| [Plugin submission](hermes-plugin-submission.md) | How to get the server side into the Hermes plugin catalog, with drafts |
| [Announcements](announcements.md) | X thread, LinkedIn post, portfolio blurb, store listing copy |
| [Screenshots](screenshots.md) | Shot list, capture rules and what can be reused |
| [Install guide](../install.md) | Tester-facing: server, Tailscale, phone, watch, pets |
| [User manual](../manual.md) | Tester-facing: every feature on the phone and the watch |

## The three blockers

These decide what "beta" can mean. Everything else is checklist work.

### 1. The relay features need a patched Hermes core

History, the task inbox, the reconnect outbox, handoff and remote wake depend on
`relay-core-seams.patch`: about 15,000 lines across 50 Hermes core files, ported to
v0.22.5. A tester would have to patch their own Hermes install, and every `hermes update`
can break it until the patch is re-ported. The plugin catalog also refuses plugins that
patch core (rule 9). The way out is to turn the seams into real Hermes hooks upstream. See
[plugin submission](hermes-plugin-submission.md).

**For the beta:** ship two tiers.

- **Core** works on stock Hermes with the listed `gadget` plugin. You get conversations,
  voice, approvals, profiles, pictures, tiles, widgets, the phone relay and phone Talk/Chat.
- **Relay preview** adds history, inbox, outbox, handoff and wake. It is opt-in, for
  technical testers who accept a pinned Hermes version and the core patch.

Before claiming Core works, test the current apps against the stock catalog plugin. The
catalog pins `gadget` 0.3.0 and upstream is at 0.4.1; our server work is still based on
0.2.0. Opus and profile faces landed upstream in a different form from our patches, so
negotiation needs checking.

### 2. Our plugin is a fork of an already listed plugin

`gadget` is listed in the catalog and maintained by Adolanium. Our server code is 166 commits
(about 57,000 lines) on top of their v0.2.0. Catalog rule 16 means a rebranded fork will not
be listed while the original exists. Improvements have to go upstream as pull requests first.
A separate listing is possible only if the result is materially different, or if the author
declines the pull requests or doesn't answer them for 30 days.

### 3. Signing keys and your own devices

The phone and watch you use today run debug-signed builds. A Play or release-signed build has
a different signer, so Android won't install it over the debug build without an uninstall.
Uninstalling is off-limits on your devices. Options:

- Keep your own devices on debug builds and give testers release builds. Recommended for the
  beta.
- Or move your devices later with **Settings → Move setup → Export configuration**, then a
  fresh install and new pairing. You choose when that happens.

The phone and watch release builds must share one signing key, or the Data Layer between
them won't work.

## Decisions only you can make

1. **Who the beta is for.** A small invited group: technical Hermes users on Android with
   Galaxy or Pixel watches. Alternatively a public open beta. Recommended: invited, 10 to 25
   people.
2. **How testers get it.**
   - Play closed testing: needs a Play developer account, a signing key, a hosted privacy
     policy and Wear OS review.
   - Signed APKs on GitHub Releases: faster, but testers sideload onto their watch over adb,
     which is painful.
   - Recommended: start with signed APKs for the first five testers, then move to Play closed
     testing.
   - Note: personal Play accounts created after November 2023 need a closed test with at
     least 12 testers opted in for 14 days in a row before production. The beta can count
     towards that.
3. **Name.** "Quantum Relay for Hermes" describes compatibility without implying endorsement.
   Keep "Hermes" only in the "for Hermes" form, never the Nous logo or mascot, and add the
   non-affiliation line everywhere.
4. **Relay preview or Core only** for the first wave (see blocker 1).
5. **Support channel.** GitHub Issues plus Discussions (recommended), or Discord.

## Launch checklist

### Product

- [ ] Arrange sheet tested on the phone, and the order confirmed on the watch.
- [ ] Owner retest of history, inbox, wake and handoff on the m5 profiles.
- [ ] Watch shows "catalog offline" when the phone's Tailscale is off, instead of quietly
      showing still portraits.
- [ ] Core tier tested end to end against stock Hermes plus the catalog `gadget` plugin,
      with no relay flags. Record the results in `docs/compatibility.md`.
- [ ] A fresh install on a second phone and watch that were never set up. This is the real
      tester experience; your devices carry months of state.
- [ ] Opus battery measurement, or drop the battery claims from all copy. The copy in
      [announcements](announcements.md) makes none.
- [ ] Decide whether image and document sharing (N3) is in the beta or "coming soon".
- [ ] Known-issues list written from the open items.

### Setup friction (the biggest beta risk)

Setup today is several manual steps per profile: plugin, port, TLS, Serve route, pairing,
plus the catalog publisher. Most testers will stall here.

- [ ] A `relay-setup` script for the host. It checks Hermes, the plugin and Tailscale, assigns
      ports, adds the Serve routes, starts the catalog and prints a setup QR code. Phone QR
      import already exists (Settings → Move setup).
- [ ] A setup checker that confirms the WebSocket upgrade, TLS and pairing state for each
      profile, plus the catalog 401/200 checks.
- [ ] A `hermes gadget relay-grants allow <device> --profile <p>` command. Today grants are a
      hand-written `relay-grants.json` with key hashes and group ids, which testers can't
      reasonably do. Until it exists, Relay preview setup is one to one.
- [x] Uninstall steps for the server side ([install guide](../install.md#uninstall)).

### Repository

- [x] README.md rewritten for testers, with an install guide, user guide and SECURITY.md
      (drafts on `relay/delivery`).
- [ ] Enable GitHub private vulnerability reporting and Discussions on the repo.
- [ ] Merge `relay/delivery` into `main`. This is a separate step.
- [ ] Run a secret scan over the full git history, not just the tree. The repo is already
      public.
- [ ] Review `docs/` for personal profile names and character references. Older plans and
      specs name personal profiles and pets based on commercial characters.
- [ ] Move internal working docs to `docs/internal/` or drop them, so testers find the
      install guide and manual first.
- [ ] Add issue templates (bug, setup help, feature), a pull request template, and a
      `CHANGELOG.md` with a 0.2.0-beta entry.
- [ ] Add `THIRD_PARTY_NOTICES` (Opus, Compose and other licenses) and an open-source licenses
      screen to the app.
- [ ] Publish the relay plugin as a public repo or branch that testers can install from. Today
      it exists only on your machines.

### Release engineering

- [ ] Set the version: name `0.2.0-beta`, a higher phone `versionCode`, and the watch
      `versionCode` kept above the phone's.
- [ ] Create a release key in `.local/signing.properties`, backed up in Proton Pass. One key
      covers both apps.
- [ ] Build signed bundles and APKs, then run `bundletool validate`.
- [ ] Add a CI job that attaches signed artifacts to a draft GitHub Release (key from CI
      secrets).

### Play (if you choose Play)

- [ ] App created in Play Console, with the Wear OS form factor enabled.
- [ ] Privacy policy hosted. Draft in `docs/privacy.md`; GitHub Pages works. Fill in the
      publisher name, contact and effective date.
- [ ] Data safety form filled in from `docs/data-safety.md`.
- [ ] Foreground service declarations with demo videos: watch microphone and media playback,
      phone connectedDevice.
- [ ] Microphone and notification permission explanations. Content rating questionnaire.
- [ ] Store listing: icon, feature graphic, phone screenshots and round watch screenshots,
      all synthetic. See [screenshots](screenshots.md).
- [ ] Reviewer test access. Play reviewers need a working server. Provide a demo endpoint
      with a scripted demo server, or explain clearly that the app is self-hosted. Without
      this, rejection is likely.
- [ ] Internal testing first, then closed testing with the tester list.

### Plugin catalog

See [plugin submission](hermes-plugin-submission.md). It runs in parallel and does not block
a sideload beta.

### Communications

- [ ] A 30 to 60 second demo video: watch hold-to-talk, the reply streaming in, an approval
      tap, the pet reacting, then the phone inbox. The posts land much better with video.
- [ ] Landing page: a GitHub Pages site or a portfolio page that the posts link to.
- [ ] Posts scheduled after the repo, install guide and release are live. Drafts in
      [announcements](announcements.md).
- [ ] Tester welcome note: what to test, how to send feedback, and how to attach a connection
      report (Talk/Chat → Connection health → Share report).

## Things that are easy to forget

- **Character IP.** Several personal pets are based on commercial game characters. They must
  never appear in screenshots, the video, the store listing or posts. Use the synthetic demo
  fixtures only.
- **Personal endpoints in captures.** Tailnet names and box names show up in setup screens
  and connection reports. Capture against a synthetic demo server.
- **Hermes version drift.** Relay preview testers must not run `hermes update` until the core
  patch is confirmed for the new version. Say so loudly in the install guide.
- **No telemetry means no crash reports.** Bugs arrive only as reports from testers, so
  describe exactly how to share a connection report and `adb logcat`.
- **Watch sideloading.** Without Play, testers need wireless debugging on the watch.
  Document it step by step, or use Play from the start.
- **ElevenLabs costs** are billed to the tester's own key. State that wherever phone voice
  is mentioned.
- **Tailscale Funnel.** Never recommend it by default. It makes the endpoint public.
- **Accessibility.** TalkBack labels, large fonts and reduced motion are in place. Say so;
  it is a real differentiator.
