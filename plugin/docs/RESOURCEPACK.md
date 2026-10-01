# Resource pack

Built by `py resourcepack/tools/build_pack.py` (needs Pillow). Output: `resourcepack/cartzone-pack.zip`.

| | |
|---|---|
| SHA-1 | `7485fff3a39d9be19bf34f1bc75580e1a78ec375` |
| Size | 115722 bytes |
| pack_format | 75 (1.21.11), accepted range 64-99 |

## What is in it
- **One Time Shield** model (`item_model` `cartzone:one_time_shield`), with a separate blocking pose.
- **IP bar**: the pink boss bar sprites are transparent, and the bar's title draws a red rounded bar
  and a minecart icon from the `cartzone:hud` font. Only the plugin's IP bar uses a pink boss bar.
- `pack.png` is the CartZone logo.

It changes no vanilla textures other than the two pink boss bar sprites.

## Hosting (mcpacks)
1. Upload `cartzone-pack.zip` to mcpacks and copy the **direct download** link (it must download the zip itself, not a web page).
2. In `plugins/CartZone/config.yml`:
   ```yaml
   resource-pack:
     enabled: true
     url: '<the direct link>'
     sha1: '7485fff3a39d9be19bf34f1bc75580e1a78ec375'
     required: true
   ```
3. `/cart reload`, then rejoin. `/cart diag` shows whether the pack is configured.

Rebuilding changes the SHA-1 only if the content changed. Always paste the new SHA-1 after a rebuild,
or clients keep the old cached pack.
