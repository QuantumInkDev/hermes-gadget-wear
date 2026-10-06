# Client speech verification

The phone implements opt-in shared/per-endpoint encrypted keys, voice precedence, a read-only quota check, bounded streaming native Opus or PCM16, cancellation, and no automatic paid synthesis retries. Production requests use the fixed ElevenLabs HTTPS origin with normal certificate validation, no proxy, redirects or automatic connection retries. Test origins/trust are injected only in host tests. The SDK and watch independently negotiate client speech; an old server leaves readable replies and server voice working.

`client-speech.ogg` in protocol/mobile test resources and watch instrumentation assets is an original 0.6-second 440 Hz mono tone, created with ffmpeg/libopus at 48 kHz, 32 kbit/s and 20 ms frames, with metadata removed. It contains 31 packets and no human recording.

Local provider tests check the API-key header, remaining quota, exact PCM bytes, native Ogg validation/pacing, voice override, forbidden responses, redirects and cancellation. The phone API 37 Keystore test verifies shared/profile selection, ciphertext, tamper rejection and refusal to regenerate a missing encryption key. The watch API 37 decoder test consumes the original native Opus fixture. Actual paid/provider traffic and phone/watch Data Layer speech were not performed.
