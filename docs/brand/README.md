# QuantumInk Relay identity

The approved phone label is **QuantumInk Relay**; the wearable label is
**QuantumInk Relay Watch**. Application IDs, signing and Gadget protocol paths stay
the same. These names have not been represented as legally cleared for publication.

[SVG source](quantumink-relay.svg) is original project artwork licensed with this
repository under MIT. The speech outline surrounds three connected orbit points;
it uses no Nous mascot, official wordmark, traced character or personal profile art.
The three points and connected speech shape remain recognizable in one color.

Native resources live in `client/src/main/res` and are merged by both applications:
`@mipmap/ic_quantumink_relay` and `@mipmap/ic_quantumink_relay_round` select adaptive
assets on the supported API 29+ devices, with monochrome foregrounds on API 33+
and vector fallbacks. Unversioned `mipmap-anydpi` supplies the adaptive default.
The 108-unit artwork fits within the central 72-unit area; the launcher supplies
its round or other mask. Background is `#141A24`, outline `#E9EDF8`, orbit links
`#9DABFF` and points `#7DDDD2`. Notifications keep their existing small white icons.

Compatibility wording for About: “An independent community client compatible with
Hermes Agent and the Hermes Gadget protocol. Not affiliated with or endorsed by
Nous Research.” The SDK is credited in the existing repository license/attribution
documents. The SDK maintainer's [trademark notice](https://github.com/Adolanium/hermes-gadget-sdk#affiliation-and-trademarks)
does not grant rights to Nous names or marks; it is not a blanket permission from
Nous to publish their mascot. Store-name and rights review remain publication gates.

Directory artwork comes only from the effective normalized portrait in the encrypted
catalog. Choosing a watch picture changes the client override and never changes box
art. Any future public screenshots must use the original synthetic fixtures under
`docs/demo/carousel`, not personal photos or private setup.
