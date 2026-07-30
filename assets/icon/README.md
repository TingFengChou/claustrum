# App icon

`claustrum.svg` is the master app icon (1024×1024, self-contained).

**Concept.** Three modality strands — vision (amber), audio (rose), language
(teal) — sweep in and are bound, through the thin curved *claustrum sheet*, into
a single luminous node and one unified output stream. It is the project's thesis
as a mark: separate senses fused into one coherent, observed stream. The deep
ground reads as ambient, always-on sensing.

**Palette.**
- background `#1c1436 → #070610`
- vision `#ffb054` · audio `#ff5c8a` · language `#43e0d0`
- fusion / unified stream `#ffffff`

## Producing app assets

The SVG is the source of truth. For the actual Pixel app, export from it:

- **Android adaptive icon** — foreground = the motif on transparent, background =
  the gradient `#1c1436→#070610`. Keep the motif within the center safe zone
  (~66%); the current mark already sits inside it. Export `mipmap` densities
  (mdpi→xxxhdpi) or supply the vector as `ic_launcher_foreground`.
- **Legacy/notification** — monochrome white-on-transparent variant of the mark
  for the status-bar/notification icon (Android tints it).
- **Store / favicon** — flatten to PNG at 512 and 1024.

Rasterise with any SVG tool, e.g. `rsvg-convert -w 1024 -h 1024 claustrum.svg -o claustrum-1024.png`
or `resvg`. Do not hand-edit exported PNGs — change the SVG and re-export.
