This folder is an example ItemsAdder content pack for the WalkieTalkiePlugin.

How to use

1) Copy the folder `contents/radio` into your server:
   `plugins/ItemsAdder/contents/radio/`

2) Put your PNG textures into:
   `plugins/ItemsAdder/contents/radio/resourcepack/assets/radio/textures/item/`

   Required files (per channel):
   - `radio_<channel>_0.png` (OFF)
   - `radio_<channel>_1.png` (TRANSMIT)
   - `radio_<channel>_2.png` (LISTEN)

   Example channels used here:
   - czerwoni, niebiescy, handlarze, piraci, tohandlarze, piraci_random

3) (Optional) If you want animated textures, keep the provided `.png.mcmeta` files.
   They are harmless even without animation.

4) Run ItemsAdder:
   - `/iazip`
   - `/iareload`

Notes

- The plugin expects staged IDs in the `_0/_1/_2` convention.
  In the plugin config, set each radio id to the OFF variant (`..._0`) or to the base (`...`).
  The plugin will swap to `_1/_2` automatically.
