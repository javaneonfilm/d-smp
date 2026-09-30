# CartRoyale admin guide

Everything an owner does to set the server up and run games. Commands are typed in game unless
marked console. `/cart help` lists them; `/cart diag` shows everything that could be wrong on one
screen. `/cart selftest` checks the arena block rules in 3 seconds.

## 1. Install

1. Paper **1.21.11**, Java 21. Put `CartRoyale.jar` in `plugins/`. Recommended: **LuckPerms** and **CraftingStore**.
2. `server.properties`: `white-list=true`, `enforce-whitelist=true`, `online-mode=true`, `max-players` above your largest purge cap plus staff (e.g. 150), `spawn-protection=0`.
3. `level-name` is your **lobby** world (`worlds.lobby` in config.yml, default `lobby`). The plugin loads the **arena** world itself.
4. Purpify restarts the server when it stops. CartRoyale stops the server once after every game (`match.restart-after: true`) so the arena is restored on the next boot.
5. Start once. `plugins/CartRoyale/` is created with every config file.

## 2. Secrets (never share these)

Edit `plugins/CartRoyale/secrets.yml` (see `secrets.example.yml`):
- `discord-webhook-url`: Discord channel settings > Integrations > Webhooks > New Webhook > Copy Webhook URL.
- `stats-token`: the random token your stats Worker checks (only if you use the website stats).

In `config.yml` set `webhooks.events-role-id` (Developer Mode on, right-click the @Events role, Copy Role ID). Then `/cart reload`.

## 3. LuckPerms groups

```
lp creategroup vip
lp creategroup vipplus
lp creategroup host
lp creategroup admin
lp group vip permission set cartroyale.rank.vip true
lp group vipplus permission set cartroyale.rank.vipplus true
lp group vipplus parent add vip
lp group admin permission set cartroyale.admin true
```
`host` needs no permission: a Game Ticket is what lets someone host. The plugin grants `host`
temporarily (6 h, `host.ticket-hours`) so the rank shows.

Admins (`cartroyale.admin`) never play; they spectate. `/cart play` toggles that for you (for testing).

## 4. The arena map

1. Copy your map's **world folder** (the one with `level.dat`) to `plugins/CartRoyale/templates/<name>/`, e.g. `templates/canyon/`.
2. Restart, or with no game running: `/cart arena load canyon`. You are teleported there.
3. Stand at the middle: `/cart arena setcenter`. Set the map edge: `/cart arena setborder 300` (half the width, square).
4. Where dead players appear: `/cart arena setspawn`.
5. The ghast route: stand where the ghasts should start, **at the height they should fly**, **looking the way they should fly**: `/cart arena setdeploy`. They fly `deploy.distance` blocks (default 300) that way. Make sure the drop point is over the map.
6. The zone: stand at the first circle's centre, `/cart zone start 300`. Stand where the last circle should end: `/cart zone final`. `/cart zone preview` draws every phase circle around you; `/cart zone` lists the phases.
7. Chests: `/cart chests scan` finds every valid spot (full solid floor, 2 air above, no liquid next to it). Optional: `/cart chests include <radius>` / `exclude <radius>` at your position. `/cart chests test 40` places a test set; `/cart chests clear` removes it.
8. **Save**: `/cart arena save canyon`. The arena is reset to exactly this after every game. Save again whenever you change the map.
9. `/cart selftest` to confirm TNT carts break blocks, nothing drops but rails, and loot chests survive.

Until a template exists, a generated stand-in arena is used (good for testing only).

## 5. Kits (one per mode)

1. `/kitready`, then `/kitready` again within 10 s to confirm. Your inventory is cleared.
2. Put the kit in your inventory, armor slots and offhand.
3. `/cart kit set lt` (LT Cart) or `/cart kit set ht` (HT Cart).
4. `/cart kit preview lt` shows it. Armor in kits is made unbreakable automatically.

## 6. Loot

`/cart loot` > pick Bronze, Silver or Gold. Click any item in your own inventory to add it.
On an entry: left/right click raise min/max, shift lowers, **Q** cycles the rarity, **F** toggles
"counts as a pearl" (for the Pearls modifier), **Ctrl+Q** removes it. The shield button adds a One
Time Shield. Changes save to `loot.yml` at once. Rarity weights and the Luckier Loot multipliers are
in `loot.yml`.

## 7. Games

- **Schedule**: `/cart schedule`. Your three daily games (16:00 Duos, 18:00 Squads, 20:00 Solos, in
  `timezone`, default UTC) are already set. Click one to change time, days, mode, team size, cap,
  map or modifiers. Games are created and announced 60 minutes ahead.
- **Host now**: `/cart host` (admins can start from 1 minute ahead) or `/cart start` (opens the next
  game in 60 s; `/cart start now` in about 13 s).
- `/cart queue`, `/cart cancel <id>`, `/cart stop` (ends the running game, no result, restores the arena).
- **Daily post**: at `webhooks.daily-post.time` (12:00) Discord gets "Today's games".

What happens in a game: logins open 5 minutes before (`join.window-minutes`), everyone online is
counted at the start, extra players are purged (VIPs much less often, VIP+ and the hoster never),
team games get 90 s to team up (`/team invite`, `/team accept`, or the lead item), everyone boards
Happy Ghasts with the kit, sneaks to drop early or is dropped at the end, glides down on a locked
elytra (the chestplate comes back on landing, no fall damage), loots chests, and the zone closes in.
Last team alive wins. Results go to Discord, everyone is kicked 10 s later, and the server restarts.

## 8. Store (CraftingStore)

Package commands (use `{uuid_dashed}`):

| Package | Initial | Chargeback |
|---|---|---|
| VIP | `lp user {uuid_dashed} parent addtemp vip 30d accumulate` | `lp user {uuid_dashed} parent remove vip` |
| VIP+ | `lp user {uuid_dashed} parent addtemp vipplus 30d accumulate` | `lp user {uuid_dashed} parent remove vipplus` |
| Game Ticket | `cart ticket give {uuid_dashed} 1 {transaction_id}` | `cart ticket revoke {uuid_dashed} 1 {transaction_id}` |

A ticket buyer is whitelisted and gets the Host GUI when they join (or `/cart host`, or the star in
their hotbar). They pick a start at least 10 minutes ahead that doesn't clash with another game.
A chargeback removes the ticket, or cancels the game it paid for, and takes them off the whitelist.
If a bought game can't run (not enough players, server down), the ticket is given back.
`/cart ticket list <name|uuid>` shows every change.

## 9. Resource pack (mcpacks)

See `docs/RESOURCEPACK.md`: upload `cartroyale-pack.zip`, paste the direct link and the SHA-1 into
`resource-pack` in config.yml, set `enabled: true`, `/cart reload`.

## 10. Website stats

`stats.export.mode: WORKER` with `stats.export.url` (your Cloudflare Worker, https) and
`stats-token` in secrets.yml posts leaderboards every 60 s. `FILE` writes
`plugins/CartRoyale/stats.json` instead. `'OFF'` (with quotes) turns it off.

## 11. When something is wrong

`/cart diag` first. It shows the state, the next game, who would be selected, whether the arena,
template, chest spots and kits are set, Discord and stats status, the resource pack, LuckPerms,
the time zone and today's shop. Problems during a game are also sent to online admins in chat.
Set `debug: true` in config.yml for extra console lines (drops, landings, shield blocks).
