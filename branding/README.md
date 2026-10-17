# Better Intelligence identity

`source-icon.jpg` is the brain icon supplied by the app owner. `brain-foreground.png`
was prepared with the built-in imagegen tool, using this edit prompt:

> Extract only the colourful friendly brain/speech-bubble mark with its two curved closed eyes and the four little coloured rays above it. Remove the entire dark navy rounded square and outer white background, replacing these with genuine transparency. Preserve the exact shapes, cyan/blue/purple left half, yellow/orange/pink right half, glossy gradients and soft coloured glow of the original mark. No redesign, no new elements, no text. Centre the complete mark, including its rays and lower speech tail, within a square transparent canvas with roughly 10 percent clear padding on each side. Keep every part of the mark intact.

Run `python scripts/package_launcher_icon.py` (Pillow required) to package the
prepared art into adaptive and legacy launcher resources and `play-store-icon.png`.
Adaptive foreground content stays inside Android's safe region. Do not use the
colour bitmap as a monochrome mask: that would fill in the eyes.

The theme uses its navy background, violet primary, cyan secondary and rose
tertiary, with separate contrasting foreground colours for light and dark modes.
