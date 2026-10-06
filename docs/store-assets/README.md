# Original store-art drafts

`icon-512.png` is a 512×512 RGB PNG and `feature-1024x500.png` is a 1024×500 RGB PNG.
Both use an original geometric amber robot, with no Hermes mascot, personal pet or server
content. They are drafts for publisher review, not uploaded Play assets or screenshots.

Reproduce with the pinned Pillow 12.3.0 environment:

```bash
.local/venv/bin/python tools/store-art.py --output docs/store-assets
```

The script draws the shapes directly and uses Pillow's bundled default font. Android vector
icons use the same original robot motif. Real app screenshots and their fixture notes belong
in `docs/demo/`, separate from this promotional artwork.
