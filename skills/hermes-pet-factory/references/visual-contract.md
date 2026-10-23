# Hermes and Quantum Relay pixel contract

Re-check the target's actual `agent/pet/constants.py`, render/store code and Relay validators before overriding this contract. This is **Hermes/Relay**, not ChatGPT Work Pets v2: don't import the latter's eleven-row/look-direction schema or upload APIs.

## Deliverables

- Crisp pixel-art square card: 1024×1024 master, 512×512 export. Circular card: square RGBA canvas with genuine transparent outside-circle area, 512×512 master/export plus **256×256 watch export**. Keep the face comfortably inside the circular safe area. Match approved identity, palette and proportions across cards and sprite. Never crop a square into a circle that cuts hair, ears, fans or tentacles. Additional sizes follow the actual destination's limits.
- Standard base: **8 columns × 9 rows, 192×208 cells, 1536×1872 PNG/RGBA**. First one to six occupied cells per row form a COMPLETE cycle; all remaining cells transparent. Never truncate an eight-frame walk into six or animate blank tails.
- Base order: `idle`, `running-right`, `running-left`, `waving`, `jumping`, `failed`, `waiting`, `running`, `review`. Hermes aliases: wave→waving, jump→jumping, run→running. `running` means **working in place**, not foot-running.
- **Signature**: current pets use row6 `waiting` as the signature slot. State that mapping in the brief and preview it as **Signature / waiting**. This is not a thirteenth runtime row or a new event trigger. A separately authored signature is only an exported asset unless the target actually supports its trigger. If restrained waiting and signature are both requested, resolve the runtime mapping up front; never claim an unused row will play.
- Companion: **6 columns × 3 rows, 1152×624 RGBA PNG**, rows listening6, talking3, sleeping6. The unused talking cells are transparent. Metadata:

```json
{"gadgetSheet":{"spritesheetPath":"gadget-sheet.png","rows":[{"name":"listening","index":0,"frames":6},{"name":"talking","index":1,"frames":3},{"name":"sleeping","index":2,"frames":6}]}}
```

- Preserve `spritesheetPath` and desktop bytes. Optional `gadgetBasePath` selects a separate watch-friendly base if needed. Each delivered PNG ≤2MiB, max decoded pixels3,200,000, dimensions in cell multiples; explicit actual frame counts1..6. A regular desktop file over the delivery limit is retained, never silently degraded; make/approve an alternate Gadget base.
- Talking frames are amplitude selections **closed / half / open**, not an automatically looping animation. Body, clothing and props stay pixel-identical across the three poses. Define a tight mouth/anatomy box; changes must stay inside it. Nonhuman mouth tentacles move naturally inside the approved region. Optional full expression overlay uses `anchor_frame:0` plus `rect:[left,top,right,bottom]`, in registered192×208 coordinates, only when accepted by the target renderer. Inspect the composite, not just a rectangle metric.

## Generation and registration

Inspect returned PNG alpha and dimensions; a checkerboard drawn into opaque RGB is not transparency. Use an approved transparency-capable route or reviewed chroma extraction; never waive alpha or accept a halo. Do not trust requested model/size when a backend reports different returned values.

Generate one canonical full-body identity anchor, then grounded per-state visual jobs with that anchor attached every time. A six-frame job may use a clearly separated 3×2 pose grid if the image tool can't produce a very wide strip. Verify the actual layout/count; don't assume evenly divided slots where a silhouette crosses a boundary. Extract complete pose groups with one shared scale and registration. Register once against the approved idle body/root; don't independently resize each frame/state to fill its cell. Preserve a real airborne jump relative to grounded takeoff/landing; don't align airborne foot medians to the floor.

For updates, compare original and edited pixels side by side, at native raster as well as watch size. Keep fixed markings anchored to anatomy (for example hair-root streaks), not recalculated from a moving silhouette. Preserve body width/height and pixel density; no shorter/thicker redesign, thinner legs, soft interpolation or whole-body regeneration when only a local edit was requested. One palette AND exposure across every row, including error and all companions. No aliased rim-light cutout, halo, detached hand/prop, watermark, placeholder X or debug labels.

## Motion review

- Walks: clear alternating planted/swing phases and weight transfer, stable leg proportions, readable left/right orientation, no sliding feet/high-knee stomping unless requested. Mirror **individual frames in place**, never reverse the temporal strip; don't mirror asymmetric markings, handed props or directional lighting incorrectly. Keep wings/tentacles/hair following movement naturally without changing the approved gait/body.
- Teleport/glide can replace directional walking only if specified; show both directional loops and their transitions, with character-specific approved effect color and symbols.
- Signature: meaningful recognizable sequence, full action/return, correct props/colors/symbols. Working: purposeful in-place action. Review: an appropriate prop/activity; avoid generic device/glove gestures for anatomy that cannot use them.
- Listening: unmistakable attentive cue, hand/paw/tentacle at ear as approved, no speaking mouth. Sleeping: closed eyes and subtle breathing, no floating Zs or dramatic palette shift. Talking: playback-selected anatomy changes with closed-mouth silence; no fabricated human mouth for a creature.
- Show every occupied frame, last→first loop, light/dark, 1×/2×, normal/half speed and frame stepping. Talking autoplay must be labeled a **simulated amplitude demo**, not proof of live lip sync. Stage geometry/hash tests cannot approve anatomy or motion.

`factory.py validate` is a structural preflight, not the sole acceptance gate. Run actual SDK pack/stager and renderer checks where available. User preview approval, desktop profile switching, phone/watch delivery, physical lip sync/ambient/battery are separate results.
