# Akuma Companion Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking. The controller owns the final fresh review; no child agents in this workstream.

**Goal:** Prepare an Akuma-only companion proof of concept and reusable, source-preserving packaging and geometry evidence tooling.

**Architecture:** Keep the original profile metadata/base atlas immutable. Register separately authored listening/talking/sleeping cells with the existing median idle-row transform, then write a new staging bundle and source-bound receipt. Geometry gates and optional mouth-region body-difference measurements support human review; they do not prove semantic identity or physical lip sync.

**Tech Stack:** Python 3, Pillow, pytest; built-in image generation for Akuma art only.

## Global Constraints

- Approved spec: `docs/superpowers/specs/2026-10-18-relay-expansion-design.md`, O2 and recorded decision 5.
- Generate/build **Akuma only**; future custom pets can start from scratch.
- Preserve original base art, profile metadata/configuration, identities, and unrelated personal pets.
- Cells are 192×208. Companion is 1152×624 RGBA with listening 6 / talking 3 / sleeping 6 occupied cells; talking trailing cells stay transparent.
- Preserve 8-, 9-, and 11-row base sheets without appending rows or changing taxonomy.
- Personal art, exact source paths/hashes, manifests and receipts remain ignored under `.local/expansion/akuma/`; public tests/docs use synthetic art only.
- No live server/plugin edits, device operations, protocol/client changes or SDK patch exports. No push.

## File map

- Modify `tools/extend-pet.py`: enforce non-overwriting separation at the callable API boundary.
- Create `tools/pet-companion.py`: stage copied base metadata/art plus registered companion; emit source/output hashes, per-cell geometry, optional talking body metrics and a labelled contact sheet.
- Modify `tools/tests/test_extend_pet.py`: synthetic preservation/collision and supported-base tests.
- Create `tools/tests/test_pet_companion.py`: synthetic staging, confinement, blank-tail, registration, source-binding and optional body-region gates.
- Create `docs/pet-companion-tooling.md`: synthetic-only invocation and limits.
- Private `.local/expansion/akuma/report.md`: evidence, generation result, visual observations and unverified integration limits.

### Task 1: Protect registration inputs

**Interfaces:** `extend(base_path: Path, companion_path: Path, output: Path) -> dict` remains metadata-fragment compatible.

- [x] Add synthetic regression: call `extend(base, input, base)` and assert `ValueError` plus unchanged source bytes; also test symlink/hardlink destinations and an existing output.
- [x] Run `.local/extension-venv-m5/bin/python -m pytest tools/tests/test_extend_pet.py -q`; confirm collision regressions fail before implementation.
- [x] Reject any existing output (including dangling symlinks) at the start of `extend`; this protects direct callers as well as CLI users.
- [x] Run the same targeted suite; verify supported standard/legacy geometry, unchanged rejection of oversized 11-row geometry, and input hashes.

### Task 2: Source-bound staging and review evidence

**Interfaces:** `build(metadata_path: Path, companion_path: Path, output_dir: Path, mouth_box: tuple | None = None) -> dict` writes only a fresh directory. It consumes `extend` and emits `pet.json`, copied base asset, `gadget-sheet.png`, `manifest.json` and `contact-sheet.png`.

- [x] Add synthetic fixture tests that call `build`, assert exact 6/3/6 rows, base byte preservation, source/output SHA-256, transparent trailing cells, median registration and contact-sheet existence. Add invalid metadata traversal/symlink, existing-output and mouth-body drift rejections.
- [x] Run `.local/extension-venv-m5/bin/python -m pytest tools/tests/test_pet_companion.py -q`; confirm new API tests fail before implementation.
- [x] Implement confined metadata-relative base resolution, bounded metadata/art decoding, fresh staging directory with failure cleanup, source/output hashes and per-frame alpha boxes. Optional mouth-box mode requires all talking pixels outside the supplied cell-space rectangle to match exactly; retain an explicit unchecked state without that box.
- [x] Run both pet test modules. Separately call the existing SDK `plugin/pets.py` package function on a synthetic staged bundle to prove the packaging contract without a live plugin change.
- [x] Write the public guide with explicit human review, generation/provenance instructions and separate software/live/device evidence.

### Task 3: Private Akuma proof and handoff

**Interfaces:** private staging bundle/manifest/report consumed by the controller; no source installation.

- [x] Locate the profile-scoped Akuma metadata and source from controller-approved discovery; record private source hashes and view the base/reference before generation.
- [x] Request Akuma companion expressions through the built-in image tool, preserving identity, pixel-art style, transparent background, registration and still talking body. Preserve raw generation separately. Do not substitute existing running frames for generated expressions.
- [x] Inspect the generated result. Run the staging/geometry pipeline only if prepared cells satisfy its contract; rejected or visually poor generations remain explicitly rejected private candidates. No fabricated passing evidence.
- [x] Write `.local/expansion/akuma/report.md` with exact original/generated/output hashes, source binding, real versus synthetic evidence, geometry results, visual review and remaining mouth/physical/device limits. Rehash originals after work.
- [x] Run both targeted test modules; inspect scoped diff and `git check-ignore` for private output.
- [x] Commit only the six public tooling/test/guide/plan paths listed here. Send controller the commit and report path; leave live O2 acceptance open.


## Execution result — 2026-10-18

Tasks 1–2 and the bounded Task 3 generation/rejection handoff are complete for this
tooling increment. **Akuma artwork acceptance remains incomplete.** The two private
generations were rejected: v1 changes talking body pixels and widens proportions;
v2 clips sleeping poses at the declared grid boundary. The controller explicitly
instructed retaining those rejections and closing the reusable tooling increment,
without loosening gates or spending more generation calls. No visual, live or physical
O2 completion is claimed.

Ruling: 1536×2288 eleven-row bases exceed the existing 3,200,000-pixel limit. Preserve
and reject them unchanged rather than cropping rows or increasing decoder limits.
Ruling: equal-grid extraction/scaling/packing is tooling; no programmatic mouth painting
or compositing was introduced. One focused image-tool refinement attempted the correction.

Final targeted checks: 22 passed with `-W error`; a separately executed synthetic staged
bundle exactly matches the existing SDK package function's PNG sheet metadata. Private
source hashes, raw generations, preparation chain, failed checks, contact sheet and detailed
report remain under ignored `.local/expansion/akuma/`. Original metadata/base/config hashes
are unchanged. Final fresh review belongs to the controller.
