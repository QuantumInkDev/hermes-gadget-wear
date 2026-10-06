# Audio measurements

Software measurements on 2026-10-06 use a three-second original 440 Hz tone, mono PCM16 at 16 kHz. These are payload counts, not Bluetooth/VPN throughput or speech tests. Configuration: nominal 24 kbit/s, 20 ms Opus packets. VBR packet sizes differ by encoder and content.

| Direction / encoder | PCM payload | Opus payload | Packets | PCM kbit/s | Opus kbit/s |
| --- | ---: | ---: | ---: | ---: | ---: |
| Uplink / API 37 ARM64 Wear emulator MediaCodec | 96,000 bytes | 8,844 bytes | 150 | 256 | 23.584 |
| Downlink / host ffmpeg libopus | 96,000 bytes | 13,599 bytes | 151 | 256 | 36.264 |

The four-byte Gadget header adds 600/604 bytes respectively; WebSocket, TLS, Data Layer and network overhead are excluded. Decoder pre-skip/end padding affects output duration. The Android encoder packets decode through the SDK's real ffmpeg helper to 95,930 bytes of 16 kHz PCM. SDK ffmpeg packets decode through Android MediaCodec at 48 kHz. Android same-codec output was 287,794 bytes at 48 kHz; the latest combined encode/decode test took 76 ms on the emulator host, not an end-to-end conversation latency.

Validation: actual MediaCodec encode/decode and cross-codec tests, bounded header/duration/sequence tests, strict app checks, real ffmpeg SDK upload/output tests and live WebSocket negotiation/loopback. Opus is advertised only after a local codec round-trip probe succeeds. Missing server negotiation retains PCM16. Failures cancel the current media stream and never replay input. Synthetic audio fixtures are original and contain no personal recording.

Open Ultra gates: run identical utterances through PCM and Opus; record actual uplink/downlink bytes, capture/playback latency, interruption/drop behavior, STT transcripts/accuracy, intelligibility, CPU and battery. Measure Bluetooth/Tailscale relay throughput on the actual phone/watch. No STT or audio-quality nonregression is claimed from the synthetic tone. Keep M4 acceptance open until those measurements pass.
