#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v magick >/dev/null
asset=docs/store-assets/courier
res=client/src/main/res
mkdir -p "$res/drawable-nodpi"
# Asset export only: keep the generated cutout unchanged and fit it to the safe region.
magick "$asset/courier-foreground-master.png" -resize 614x614 \
  -background none -gravity center -extent 1024x1024 \
  -strip "$res/drawable-nodpi/courier_foreground.png"
magick -size 512x512 canvas:'#06173D' \
  \( "$res/drawable-nodpi/courier_foreground.png" -resize 768x768 \) \
  -gravity center -compose over -composite -strip "$asset/courier-512.png"
for entry in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  density=${entry%:*}
  pixels=${entry#*:}
  mkdir -p "$asset/legacy/$density"
  magick "$asset/courier-512.png" -resize "${pixels}x${pixels}" \
    -strip "$asset/legacy/$density/ic_quantumink_relay.png"
  magick "$asset/legacy/$density/ic_quantumink_relay.png" \
    \( +clone -alpha extract -fill black -colorize 100 \
       -fill white -draw "circle $((pixels/2)),$((pixels/2)) $((pixels/2)),0" \) \
    -alpha off -compose CopyOpacity -composite \
    -strip "$asset/legacy/$density/ic_quantumink_relay_round.png"
done
