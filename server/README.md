# Upstream-bound SDK patch series

Base: SDK `v0.2.0`, commit `323e3303ab68981f810fc3208119cd8a22e64af0`.

Apply the numbered [patches](patches/) in order to a clean checkout. Patch 1 adds optional Opus negotiation, bounded raw packets/OpusHead configuration, ffmpeg decode/encode pipes, paced downlink, protocol documentation, and compatibility tests. Default `enable_opus` is false; missing capabilities or ffmpeg support keeps stock PCM16. Local development branch: `wear/optional-extensions`. No upstream PR or maintainer agreement is claimed, and this series is not installed into live Hermes.

Run `python3 tools/setup-extensions.py` from this repository for an isolated patched checkout/environment, then run its protocol, plugin-unit and Opus tests. Keep the unmodified SDK environment for stock integration. See [upstream status](../docs/upstream.md) and [measurements](../docs/audio-measurements.md).
