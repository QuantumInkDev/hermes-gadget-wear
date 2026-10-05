# Session status

Updated: 2026-10-05

M0 foundation and M1 protocol are implemented and verified locally. The repository contains the design documents, Gradle modules, both Compose starter apps, strict build/lint/format CI, and a pure JVM implementation of the stock Gadget v1 handshake, identity, framing, and PCM negotiation.

Verified: both debug APKs build; ktlint and Android lint pass with warnings treated as errors; 13 protocol tests and one actual SDK devserver integration test pass with zero skips. The SDK's four Python protocol tests also pass. The integration covers enrollment, pairing approval, UTF-8 cumulative replies, exact PCM loopback, and enrolled HMAC reconnect. See [progress](docs/progress.md) for commands and evidence boundaries.

CONTINUE HERE: resolve the network-policy decision, then implement M2 direct mode. The maintainer has supplied a cleartext LAN endpoint in conversation; never copy it into tracked files or infer a new endpoint. TLS setup instructions are in [self-host](docs/self-host.md). Obtain pairing approval for the real Hermes demo. No hardware, emulator, microphone, or real Hermes demo has been completed.

Open decisions: TLS-only versus explicitly allowed per-endpoint cleartext; watch-owned TLS through an opaque phone relay versus accepting the phone as trusted; later multiplexed gateway v2. No relay or upstream extensions are implemented. The starter apps have no networking/microphone permissions or persistent device-key storage yet.

Maintainer notes, upstream checkouts, Python environment, downloaded toolchains, and build state are ignored under `.local/` or standard build directories. Keep the public tree free of personal endpoints, keys, pet art, and identifying test reports.
