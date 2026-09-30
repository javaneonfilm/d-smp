# Resource pack

Built by `py resourcepack/tools/build_pack.py` (needs Pillow). Output: `resourcepack/cartroyale-pack.zip`.

| | |
|---|---|
| SHA-1 | `40e15720a598c672e056b56aba8015efa3a50114` |
| Size | 26896 bytes |
| pack_format | 75 (1.21.11), accepted range 64-99 |

## What is in it
- **One Time Shield** model (`item_model` `cartroyale:one_time_shield`), with a separate blocking pose.
- **IP bar**: the pink boss bar sprites are transparent, and the bar's title draws a red rounded bar
  and a minecart icon from the `cartroyale:hud` font. Only the plugin's IP bar uses a pink boss bar.
- `pack.png` is the CartRoyale logo.

It changes no vanilla textures other than the two pink boss bar sprites.

## Hosting (mcpacks)
1. Upload `cartroyale-pack.zip` to mcpacks and copy the **direct download** link (it must download the zip itself, not a web page).
2. In `plugins/CartRoyale/config.yml`:
   ```yaml
   resource-pack:
     enabled: true
     url: '<the direct link>'
     sha1: '40e15720a598c672e056b56aba8015efa3a50114'
     required: true
   ```
3. `/cart reload`, then rejoin. `/cart diag` shows whether the pack is configured.

Rebuilding changes the SHA-1 only if the content changed. Always paste the new SHA-1 after a rebuild,
or clients keep the old cached pack.
