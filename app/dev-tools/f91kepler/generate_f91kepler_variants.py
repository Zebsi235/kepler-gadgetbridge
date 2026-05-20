"""Generate alternative F91 Kepler device icons for Gadgetbridge.

For each variant we emit:
  * the Android vector drawable XML (themed colors via ?attr/...)
  * an SVG with resolved hex colors so qlmanage can preview it

The viewport is 30x30 to match the convention used by other watch icons
(ic_device_pinetime.xml, ic_device_banglejs.xml, etc.).

To switch which icon Gadgetbridge uses, point
F91KeplerCoordinator.getDefaultIconResource() at
R.drawable.ic_device_f91kepler_<variant>.
"""
import os

OUT_DIR = os.path.dirname(os.path.abspath(__file__))
PREVIEW_DIR = os.path.join(OUT_DIR, "_f91kepler_previews")
os.makedirs(PREVIEW_DIR, exist_ok=True)

# Theme attr -> hex (light theme, taken from values/themes.xml)
THEME = {
    "primary":      "#2196f3",
    "onPrimary":    "#ffffff",
    "light":        "#1f7fdb",
    "dark":         "#4dabf5",
}
ATTR_NAMES = {
    "primary":   "?attr/deviceIconPrimary",
    "onPrimary": "?attr/deviceIconOnPrimary",
    "light":     "?attr/deviceIconLight",
    "dark":      "?attr/deviceIconDark",
}


def xml_for(paths, comment):
    body = []
    for color, d, note in paths:
        if note:
            body.append(f"    <!-- {note} -->")
        body.append(
            f'    <path\n'
            f'        android:fillColor="{ATTR_NAMES[color]}"\n'
            f'        android:pathData="{d}"\n'
            f'        android:strokeWidth="3.4" />'
        )
    return (
        f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="45sp"\n'
        f'    android:height="45sp"\n'
        f'    android:viewportWidth="30"\n'
        f'    android:viewportHeight="30">\n'
        f'    <!-- {comment} -->\n'
        + "\n".join(body)
        + "\n</vector>\n"
    )


def svg_for(paths, bg="#ffffff"):
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 30 30" '
        f'width="360" height="360">'
        f'<rect width="30" height="30" fill="{bg}"/>'
    ]
    for color, d, _ in paths:
        parts.append(f'<path d="{d}" fill="{THEME[color]}"/>')
    parts.append("</svg>")
    return "".join(parts)


# ---------------------------------------------------------------------------
# Variant 1: kepler_orbit
#   F-91 silhouette but the LCD displays a Saturn-style planet with rings
#   to evoke "Kepler" planetary orbits.
# ---------------------------------------------------------------------------
kepler_orbit = [
    ("light",
     "M9.5 6.4h11a1.3 1.3 0 0 1 1.3 1.3v15.6a1.3 1.3 0 0 1-1.3 1.3h-11a1.3 1.3 0 0 1-1.3-1.3V7.7A1.3 1.3 0 0 1 9.5 6.4z",
     "Light underlay (offset down-right) — shadow stack"),
    ("dark",
     "M9.3 5.6h11a1.3 1.3 0 0 1 1.3 1.3v15.6a1.3 1.3 0 0 1-1.3 1.3h-11a1.3 1.3 0 0 1-1.3-1.3V6.9A1.3 1.3 0 0 1 9.3 5.6z",
     "Dark underlay (offset up-left)"),
    ("primary",
     "M9.4 6h11a1.3 1.3 0 0 1 1.3 1.3v15.6a1.3 1.3 0 0 1-1.3 1.3h-11a1.3 1.3 0 0 1-1.3-1.3V7.3A1.3 1.3 0 0 1 9.4 6z",
     "Primary case body"),
    ("primary",
     "M11.5 2.4h7v3.6h-7zm0 24h7v3.2h-7z",
     "Resin strap stubs (F-91W signature)"),
    ("onPrimary",
     "M10.4 8.4h9.2v8h-9.2z",
     "LCD area"),
    # Orbit ellipse (drawn as two overlapping ellipses to make a 1-px ring).
    ("primary",
     "M15 10.6 a3.8 1.4 0 1 0 0.01 0 z M15 11 a3.4 1.0 0 1 1 -0.01 0 z",
     "Orbital ellipse around the planet"),
    # Planet body (small filled circle at center).
    ("primary",
     "M15 12.4 a1 1 0 1 0 0.01 0 z",
     "Planet body"),
    # Branding band below LCD.
    ("onPrimary",
     "M11 18.4h8v0.9h-8zm0 2.2h8v0.9h-8z",
     "Brand strip"),
    ("dark",
     "M21.7 9.2h1.2v1.4h-1.2zm0 5h1.2v1.4h-1.2zm0 5h1.2v1.4h-1.2z",
     "Right buttons"),
    ("dark",
     "M7.1 14.2h1.2v1.4H7.1z",
     "Left LIGHT button"),
]

# ---------------------------------------------------------------------------
# Variant 2: round
#   Round-faced smartwatch (totally different silhouette from the Casio).
# ---------------------------------------------------------------------------
round_watch = [
    ("light",
     "M15 6.4 a8.4 8.4 0 1 0 0.01 0 z",
     "Light underlay"),
    ("dark",
     "M15 5.6 a8.4 8.4 0 1 0 0.01 0 z",
     "Dark underlay"),
    ("primary",
     "M15 6 a8.4 8.4 0 1 0 0.01 0 z",
     "Round case body"),
    ("primary",
     "M11.5 1.6h7v3h-7zm0 23.8h7v3h-7z",
     "Strap stubs"),
    ("onPrimary",
     "M15 8 a6.6 6.6 0 1 0 0.01 0 z",
     "Display face"),
    # Two big digits "91" suggested by simple rectangles
    ("primary",
     "M12.4 11.6h1v6.8h-1zm3 0h2.2v6.8h-2.2zm0 0h2.2v1h-2.2zm0 2.8h2.2v1h-2.2z",
     "'91' on display"),
    ("dark",
     "M23 13.4h1.2v3.2H23z",
     "Crown"),
]

# ---------------------------------------------------------------------------
# Variant 3: minimal_flat
#   Single-tone flat silhouette — no shadow stack, modern minimal feel.
# ---------------------------------------------------------------------------
minimal_flat = [
    ("primary",
     "M9.4 6h11a1.3 1.3 0 0 1 1.3 1.3v15.6a1.3 1.3 0 0 1-1.3 1.3h-11a1.3 1.3 0 0 1-1.3-1.3V7.3A1.3 1.3 0 0 1 9.4 6z",
     "Case body — single tone"),
    ("primary",
     "M11.5 2.4h7v3.6h-7zm0 24h7v3.2h-7z",
     "Strap stubs"),
    ("onPrimary",
     "M10.6 8.4h8.8v13.2h-8.8z",
     "Tall LCD area, full height of case"),
    ("primary",
     "M11.4 9.6h7.2v2.2h-7.2zm0 3.2h7.2v2.2h-7.2zm0 3.2h7.2v2.2h-7.2zm0 3.2h7.2v1.8h-7.2z",
     "Four-digit readout suggestion"),
]

# ---------------------------------------------------------------------------
# Variant 4: digital_91
#   F-91 case where the LCD prominently shows "91" digits.
# ---------------------------------------------------------------------------
digital_91 = [
    ("light",
     "M9.5 6.4h11a1.3 1.3 0 0 1 1.3 1.3v15.6a1.3 1.3 0 0 1-1.3 1.3h-11a1.3 1.3 0 0 1-1.3-1.3V7.7A1.3 1.3 0 0 1 9.5 6.4z",
     "Light underlay"),
    ("dark",
     "M9.3 5.6h11a1.3 1.3 0 0 1 1.3 1.3v15.6a1.3 1.3 0 0 1-1.3 1.3h-11a1.3 1.3 0 0 1-1.3-1.3V6.9A1.3 1.3 0 0 1 9.3 5.6z",
     "Dark underlay"),
    ("primary",
     "M9.4 6h11a1.3 1.3 0 0 1 1.3 1.3v15.6a1.3 1.3 0 0 1-1.3 1.3h-11a1.3 1.3 0 0 1-1.3-1.3V7.3A1.3 1.3 0 0 1 9.4 6z",
     "Case body"),
    ("primary",
     "M11.5 2.4h7v3.6h-7zm0 24h7v3.2h-7z",
     "Strap stubs"),
    ("onPrimary",
     "M10.6 8h8.8v14h-8.8z",
     "LCD (large, fills most of face)"),
    # The "9": rectangle with a notch out of the bottom-left.
    ("primary",
     "M11.4 9.4h3.4v5.6h-3.4zm0 1h2.4v3.6h-2.4z M11.4 14.4h0.8v1.8h-0.8z",
     "Digit 9 (left half of readout)"),
    # The "1": just a vertical bar.
    ("primary",
     "M17.0 9.4h1.4v5.6h-1.4z M15.6 9.4h1.4v0.9h-1.4z",
     "Digit 1 (right half of readout)"),
    # Small "F-91W" label below digits.
    ("primary",
     "M11.6 17h6.8v0.6h-6.8zm0 1.4h6.8v0.6h-6.8zm0 1.4h6.8v0.6h-6.8z",
     "Label lines below digits"),
    ("dark",
     "M21.7 9.2h1.2v1.4h-1.2zm0 5h1.2v1.4h-1.2zm0 5h1.2v1.4h-1.2z",
     "Right buttons"),
    ("dark",
     "M7.1 14.2h1.2v1.4H7.1z",
     "Left LIGHT button"),
]


variants = [
    ("kepler_orbit", kepler_orbit, "F-91 case with orbital planet on LCD — ties to the Kepler name"),
    ("round",        round_watch,  "Round smartwatch silhouette, totally different from the Casio look"),
    ("minimal_flat", minimal_flat, "Flat single-tone F-91 silhouette, no shadow stack"),
    ("digital_91",   digital_91,   "F-91 case with prominent '91' digits on the LCD"),
]

for name, paths, comment in variants:
    xml_path = os.path.join(OUT_DIR, f"ic_device_f91kepler_{name}.xml")
    svg_path = os.path.join(PREVIEW_DIR, f"ic_device_f91kepler_{name}.svg")
    with open(xml_path, "w") as f:
        f.write(xml_for(paths, comment))
    with open(svg_path, "w") as f:
        f.write(svg_for(paths))
    print(f"  wrote {xml_path}")
    print(f"        {svg_path}")
print("\nDone. Render previews with:")
print(f"  for f in {PREVIEW_DIR}/*.svg; do qlmanage -t -s 600 -o {PREVIEW_DIR} \"$f\"; done")
