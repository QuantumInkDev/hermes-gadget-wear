# Courier launcher artwork

Approved original app identity for Quantum Relay for Hermes Agents (2026-10-14).
The [approved concept](../../design/phone-messaging-2026-10-14/courier-concept.png)
was generated without reference art. The foreground master removes its background using
the built-in image generation tool. No personal portrait or Nous mascot is included.

`courier-foreground-master.png` retains the generated alpha and character. Run
`bash tools/courier-icons.sh` with ImageMagick 7 to reproduce the padded foreground,
512-pixel color export and density/round fallbacks. Exporting only scales/composites the
master; it does not redraw it. Android's themed layer uses the exact foreground alpha
as a single white head/headset silhouette, rather than a separate unrelated symbol.
Adaptive foreground/background/monochrome resource names remain compatible.
The apps support API 29 and later, so they package adaptive icons. Density/round
exports are retained under `legacy/` as reusable artwork, outside runtime resources.

The prompt and manifest record origin and final hashes. This artwork is a client identity;
it implies no upstream endorsement. Publication naming/rights gates remain in release.md.
