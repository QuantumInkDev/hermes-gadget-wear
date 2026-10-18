# Companion art staging

`tools/pet-companion.py` prepares a separate companion atlas and a reviewable staging
bundle. It never installs a pet, edits profile configuration or updates source art.
Use a new output directory outside the original pet directory. Existing outputs,
symlink destinations and metadata paths escaping the source directory are rejected.

Install Pillow in an isolated Python environment; pytest is needed for the tests.
All example names below are synthetic. Keep personal paths, source art and receipts
in ignored local storage rather than tracked documentation or screenshots.

A prepared companion is RGBA, 1152×624 pixels, with 192×208 cells:

| Row | State | Occupied columns |
| --- | --- | --- |
| 0 | listening | 0–5 |
| 1 | talking: closed / half / open | 0–2 |
| 2 | sleeping | 0–5 |

Talking columns 3–5 remain transparent. Generate new expressions from the selected
profile's actual active pet. Keep the base atlas immutable and use its idle row as
the identity/registration reference. Listening should look attentive; sleeping can be
upright with closed eyes. Talking should keep the body fixed and primarily change
mouth shape. Do not substitute existing running rows for these expressions.

If a generator returns a reviewed equal six-column/three-row grid at a different
resolution, prepare it with shared nearest-neighbour scaling and transparent cell
packing. This operation does not clean, repaint or repair the art. It assumes the
reviewer has checked the declared grid boundaries; cut poses fail later registration.
The preparation JSON contains exact input/output paths and hashes, so save it privately.

```sh
python tools/pet-companion.py \
  --prepare-grid .local/example/raw-generated.png \
  --prepared-output .local/example/prepared.png \
  > .local/example/preparation.json
```

Stage the prepared cells against the original metadata/base:

```sh
python tools/pet-companion.py \
  --metadata .local/example/original/pet.json \
  --companion .local/example/prepared.png \
  --output-dir .local/example/review-v1
```

The resulting directory contains a byte-identical copy of the original base,
`gadget-sheet.png`, proposed `pet.json`, a labelled `contact-sheet.png` and private
`manifest.json`. The receipt binds original metadata/base/prepared input and outputs
with SHA-256, records each occupied cell's alpha bounds, and records the actual
alpha-preserving PNG delivery hashes/byte sizes. Keep the raw generation and preparation
receipt alongside it to preserve the complete provenance chain. The copied metadata's
base path is local to staging and its `gadgetSheet` uses the existing SDK contract.
No source metadata changes. Outputs are staging artifacts, not approval to deploy.

Registration uses one transform per row: median idle height, center and baseline,
with nearest-neighbour scaling. Empty, clipped, drifting or extreme-scale poses fail.
The existing SDK delivery limits still apply: input art at most 8 MiB and 3,200,000
pixels; each delivery PNG at most 2 MiB. Standard 1536×1872 and legacy 1728×1664 bases
are supported. A 1536×2288 eleven-row base exceeds the pixel limit and is rejected
unchanged. Do not crop rows or increase limits to force acceptance.

For a reviewed mouth rectangle in **registered 192×208 cell coordinates**, add
`--mouth-box LEFT TOP RIGHT BOTTOM`. This strict optional gate requires all pixels
outside that rectangle to be identical across the three talking cells, and each mouth
to differ. For example, an original synthetic rectangle fixture with a mouth near the
center uses `--mouth-box 88 104 104 124`; choose the box by inspecting the actual art.
Without a supplied rectangle the receipt explicitly records talking-body validation
as **unchecked**. A passed gate does not establish that the expressions are good,
that the rectangle actually describes a mouth, or that playback drives the correct pose.

Review the base-to-companion transition, proportions, face, accessories, all loop seams,
mouth readability and reduced-size contact sheet. Reject a visually drifting or clipped
candidate even if file checks pass. Retain rejected generations and reasons privately.
Human approval, live authenticated delivery, offline profile isolation, playback-driven
mouth response, reduced-motion/ambient behavior and physical watch legibility remain
separate acceptance checks. This tool leaves those receipt fields pending/untested.

Run synthetic regression checks:

```sh
python -m pytest tools/tests/test_extend_pet.py tools/tests/test_pet_companion.py -q -W error
```

These exercise real Pillow geometry, path confinement, input preservation, exact
mouth/body gates and PNG payload hashing. They contain no personal artwork and do
not imply live server or device acceptance.
