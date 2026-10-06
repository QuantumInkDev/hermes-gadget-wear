# Self-host connection guide

Status: direct typed and voice conversations have passed on the physical Ultra against real Hermes. Phone relay software is implemented; the actual phone/watch and off-network relay checks remain open.

Install the stock Hermes Gadget SDK plugin and run `hermes gadget info` on the host to obtain its actual endpoint. Pairing is approved on that host using `hermes gadget pair` or the approve command displayed by the server. Keep all actual addresses and credentials in local configuration, never this repository.

## Private TLS through Tailscale Serve

For the phone relay, install Tailscale on the Hermes host and Android phone and join both to the same tailnet. The phone uses the Tailscale system VPN. Tailscale Serve can terminate TLS on the Hermes host and proxy to its local cleartext gadget listener; watch-owned TLS through the opaque relay ends at that trusted host.

On the Hermes host, inspect existing Serve configuration before changing it:

```bash
tailscale serve status
```

If HTTPS port 8765 is unused and the local gadget plugin uses port 8765:

```bash
tailscale serve --bg --https=8765 http://127.0.0.1:8765
```

Substitute the actual local plugin port if different. Follow Tailscale's consent flow if HTTPS certificates need enabling. From the printed HTTPS address, replace the scheme with `wss` and append the plugin's path (normally `/gadget`); preserve the printed TLS port. Use that DNS name for certificate validation, not the old LAN address. Verify a WebSocket upgrade with certificate validation and then an authenticated, paired conversation. A browser HTTP error alone is not a gadget test.

Serve access remains within the tailnet and is subject to its access rules. Do not replace or reset existing Serve/Funnel services. To remove only this new listener, use `tailscale serve --https=8765 off`. See [Serve](https://tailscale.com/docs/features/tailscale-serve) and its [command reference](https://tailscale.com/docs/reference/tailscale-cli/serve).

## Native plugin TLS or public direct access

For a direct LAN watch connection, use a DNS name and TLS listener reachable by the watch without the phone's tailnet VPN. Native plugin TLS is one option. Merge these fields into the existing Hermes configuration's `platforms.gadget.extra` block:

```yaml
tls_cert: /path/to/fullchain.pem
tls_key: /path/to/private-key.pem
```

The SDK loads these fields on startup. Obtain a certificate valid for the endpoint DNS name, protect its private key, arrange renewal, and restart the gateway after configuration or certificate changes. The configured listener then uses `wss` on its existing port and path. A self-signed certificate is not automatically trusted by Android; never disable certificate/hostname verification to make it connect. See the pinned [SDK integration guide](https://github.com/Adolanium/hermes-gadget-sdk/blob/323e3303ab68981f810fc3208119cd8a22e64af0/docs/hermes-integration.md).

Tailscale Funnel is a separate opt-in choice for public direct `wss` access, including an LTE watch without the phone. It publishes the endpoint to the internet; pairing and optional access-token controls still matter. It is unnecessary for private phone-relayed tailnet access. Follow the pinned [Funnel guide](https://github.com/Adolanium/hermes-gadget-sdk/blob/323e3303ab68981f810fc3208119cd8a22e64af0/docs/tailscale-funnel.md) and preserve existing services.

## Configure the direct watch app

Install the watch debug APK, open Hermes Gadget, and enter a short server name plus the actual Gadget WebSocket URL. Access tokens are optional separate masked fields. Prefer a valid `wss` URL. For a private endpoint, mark it as LAN/tailnet so Android 17 can request local-network permission. Public cleartext endpoints are rejected.

For an approved private `ws` endpoint, both the LAN/tailnet declaration and the endpoint-specific cleartext warning must be accepted before Save & connect is enabled. Changing the URL clears warning acceptance. The app also checks every cleartext DNS result against private ranges; the choice cannot be used to reach public destinations or silently downgrade TLS. Settings and the separate endpoint identity are encrypted with Android Keystore.

Approve the displayed code on your own Hermes host. Conversations are disabled until the server marks the watch paired. Type a message or hold the screen to speak; release sends, and moving down while holding discards. After the first microphone grant, hold again to begin capture. Hold Cancel for two seconds to request a new session. A server approval blocks unrelated conversation controls and ignores answers for the first 600 ms.

Vibration, notifications, app-window brightness, and a system-clock timer are advertised only when supported/permitted. Notification denial leaves typed/voice conversations available. Timer execution requires an installed clock activity handling the Android timer intent. Screen brightness resets when leaving or disconnecting.

The app disconnects after 90 seconds of ordinary inactivity; a stalled active turn/prompt has a 120-second inactivity cap. Server pushes sent while disconnected are lost with the stock SDK, except pending prompts re-sent by the adapter. A direct watch connection cannot use the phone VPN automatically. For direct mode, validate reachability from the watch itself.

## Enable phone relay

Install the matching phone APK and enable relay in the companion. Keep Tailscale connected on the phone for private tailnet endpoints. Use Allow background relay to associate the watch in Android’s companion dialog; otherwise Android may reject a background foreground-service start. Grant nearby network permission when needed. An active relay shows a private notification with Stop relay, and disabling relay closes active channels.

On the watch, enter the certificate-valid `wss` URL and select Automatic (phone preferred), Phone relay, or Direct in setup. The phone forwards raw TLS bytes; pairing keys, HMAC, prompts, audio, and replies remain inside watch-owned TLS to the trusted Hermes host. The phone can observe the target address, timing, and volume. An unavailable automatic relay can fall back directly to the same endpoint and security policy, with no input replay. TLS/authentication failures require correction and do not trigger a weaker transport. Private `ws` URLs work only in direct mode with the existing warning.

A new endpoint URL receives an independent identity and pairing. Changing a LAN URL to a TLS URL does not silently reuse its old device key. Actual Data Layer, companion association, foreground startup, Tailscale Android VPN reachability, Bluetooth interruption, and off-network conversations remain hardware acceptance checks.

## Optional phone voice and ElevenLabs keys

Server voice is the default and requires no key in the app. To use phone voice, enable relay, open **ElevenLabs voice** in the companion, and save a shared key or an endpoint-specific key. Enter the exact same certificate-valid `wss` URL used by the watch; each URL has separate settings. Choose Shared or Profile for that endpoint. A profile voice override wins over the shared override, then the server's optional voice hint. Blank key fields preserve a saved key. Delete controls erase their selected phone record; they do not change the server's settings.

The optional SDK patch series must be installed on a separate reviewed deployment and `enable_client_tts` enabled before phone voice can be negotiated. The app does not modify live Hermes or install extensions. Without a compatible acknowledgment, a ready phone, or phone relay, the watch uses server voice. Reconnect after changing voice mode.

Opting in sends reply text and the selected voice to the phone and ElevenLabs, with usage billed to the supplied key. Pairing identity/HMAC stay inside watch-owned TLS; the separate client-speech channel intentionally reveals reply text to the phone. Keys stay in the phone's Android Keystore-backed vault and are never sent to Hermes. The phone screen blocks screenshots and backup is disabled. **Test saved key** makes a read-only subscription/quota request; a restricted key can synthesize yet lack quota permission. No synthesis is automatically retried after a failure, and a failed stream is not silently billed again. Check settings or select Server and reconnect.

The provider documents native Opus output; the app validates Ogg/Opus packets without ffmpeg, with PCM16 selected if the watch codec is unavailable. Mock TLS provider tests and an original tone prove the software path. Real provider output, billing, perceived voice quality, background behavior and Data Layer delivery remain acceptance gates. See [ElevenLabs streaming API](https://elevenlabs.io/docs/api-reference/text-to-speech/stream) and [subscription API](https://elevenlabs.io/docs/api-reference/user/subscription/get).

## Multiple profiles

Each stock Hermes profile has its own adapter URL/port and enrollment store. Save it as a separate watch profile with an independent device key. Use Profiles or a horizontal swipe to choose it. Phone setup can be imported after watch review; it does not remotely switch the watch. See [profile management and gateway proposal](profiles-v2.md). Separate v1 endpoints are supported; the multiplexed v2 gateway remains a proposal.

## Tile, complication, ambient and Ultra launch

Add **Ask Hermes** to the watch's Tile carousel. Tap it to open the app, then hold to speak;
Tiles do not record in the background. The Tile shows a bounded local last-reply excerpt.
Choose **Hermes state** in a watch face's short-text complication slot; tap opens the app.
It shows generic state, never conversation text or endpoint credentials.

The ambient display uses a static monochrome idle-pet outline, minute pixel shifting when not provided by the system, and no
mic gestures or animated reply. Wake to interact. Capture is canceled when entering ambient.
The watch releases its foreground service after the active turn and audio drain. A connection
can remain while the app is visible until its existing idle timeout; opening via Tile, app
or button connects on demand. Stock offline-push limitations still apply.

On Galaxy Watch Ultra, Samsung documents Settings → Buttons and gestures → Action,
then Start action with → Short press or Double press. The available list depends on
software and can be limited to supported features. Select Hermes Gadget only if offered;
this project does not claim arbitrary Quick Button mapping or intercept a reserved button.
Use Home button → Double press → Hermes Gadget when the Quick Button list excludes it.
Older menus may be under Advanced features → Customize buttons. Verify on the physical
watch. See Samsung's [Quick Button guide](https://www.samsung.com/us/support/answer/ANS10007058/)
and [Home key guide](https://www.samsung.com/us/support/answer/ANS10003380/).
