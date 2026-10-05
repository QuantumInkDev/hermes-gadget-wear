# Self-host connection guide

Status: TLS setup reference. Application onboarding and physical watch validation are still under development.

Install the stock Hermes Gadget SDK plugin and run `hermes gadget info` on the host to obtain its actual endpoint. Pairing is approved on that host using `hermes gadget pair` or the approve command displayed by the server. Keep all actual addresses and credentials in local configuration, never this repository.

## Private TLS through Tailscale Serve

For the planned phone relay, install Tailscale on the Hermes host and Android phone and join both to the same tailnet. The phone uses the Tailscale system VPN. Tailscale Serve can terminate TLS on the Hermes host and proxy to its local cleartext gadget listener; watch-owned TLS through the opaque relay ends at that trusted host.

On the Hermes host, inspect existing Serve configuration before changing it:

```bash
tailscale serve status
```

If HTTPS port 8443 is unused and the local gadget plugin uses port 8765:

```bash
tailscale serve --bg --https=8443 8765
```

Substitute the actual local plugin port if different. Follow Tailscale's consent flow if HTTPS certificates need enabling. From the printed HTTPS address, replace the scheme with `wss` and append the plugin's path (normally `/gadget`); preserve the printed TLS port. Use that DNS name for certificate validation, not the old LAN address. Verify a WebSocket upgrade with certificate validation and then an authenticated, paired conversation. A browser HTTP error alone is not a gadget test.

Serve access remains within the tailnet and is subject to its access rules. Do not replace or reset existing Serve/Funnel services. To remove only this new listener, use `tailscale serve --https=8443 off`. See [Serve](https://tailscale.com/docs/features/tailscale-serve) and its [command reference](https://tailscale.com/docs/reference/tailscale-cli/serve).

## Native plugin TLS or public direct access

For a direct LAN watch connection, use a DNS name and TLS listener reachable by the watch without the phone's tailnet VPN. Native plugin TLS is one option. Merge these fields into the existing Hermes configuration's `platforms.gadget.extra` block:

```yaml
tls_cert: /path/to/fullchain.pem
tls_key: /path/to/private-key.pem
```

The SDK loads these fields on startup. Obtain a certificate valid for the endpoint DNS name, protect its private key, arrange renewal, and restart the gateway after configuration or certificate changes. The configured listener then uses `wss` on its existing port and path. A self-signed certificate is not automatically trusted by Android; never disable certificate/hostname verification to make it connect. See the pinned [SDK integration guide](https://github.com/Adolanium/hermes-gadget-sdk/blob/323e3303ab68981f810fc3208119cd8a22e64af0/docs/hermes-integration.md).

Tailscale Funnel is a separate opt-in choice for public direct `wss` access, including an LTE watch without the phone. It publishes the endpoint to the internet; pairing and optional access-token controls still matter. It is unnecessary for private phone-relayed tailnet access. Follow the pinned [Funnel guide](https://github.com/Adolanium/hermes-gadget-sdk/blob/323e3303ab68981f810fc3208119cd8a22e64af0/docs/tailscale-funnel.md) and preserve existing services.
