# Config files

All in `plugins/CartZone/`. Every file has comments. `/cart reload` reloads everything except
worlds and the database. A file with a YAML syntax error is never overwritten: the plugin says so
in the console and ignores it (config.yml errors stop the plugin from starting).

| File | What | Edited |
|---|---|---|
| `config.yml` | Server IP, region, time zone, worlds, join window, purge weights, team sizes, host limits, ghast route speed/distance, elytra, match timings, chest counts, coins, lobby items, webhooks, stats export, resource pack, IP bar, `debug` | by hand |
| `secrets.yml` | `discord-webhook-url`, `stats-token`. Never share or commit. | by hand |
| `messages.yml` | Every player-facing text (MiniMessage) and the Discord message texts (Discord markdown) | by hand |
| `loot.yml` | Slots per chest (3-8), rarity weights, Luckier Loot multipliers, the three tiers' entries | `/cart loot` or by hand |
| `zone.yml` | Phases (wait, shrink, radius, damage, interval), orbit radius and speed, particle limits | by hand |
| `schedule.yml` | Recurring games | `/cart schedule` or by hand |
| `cosmetics.yml` | Kill effects, cart explosion effects and suffixes: id, name, rarity, price, icon, particle/sound steps or suffix text | by hand |
| `kits.yml` | The LT and HT kits (serialized) | `/cart kit set` |
| `maps/<name>.yml` | Per map: centre, border, spectator spawn, ghast route, zone start and final centre, chest zones | `/cart arena`, `/cart zone`, `/cart chests` |
| `maps/<name>.spots` | Cached chest spots (delete to rescan, or `/cart chests scan`) | automatic |
| `templates/<name>/` | Arena world templates | `/cart arena save` |
| `cartzone.db` | SQLite: players, coins, stats, games, tickets, cosmetics, daily shop | automatic, back it up daily |

Key numbers and defaults:

| Setting | Default |
|---|---|
| `join.window-minutes` | 5 |
| `join.min-players` | 2 |
| `purge.weights.vip` / `default` | 0.15 / 1.0 |
| `teams.phase-seconds` | 90 |
| `teams.sizes` | 1, 2, 4 |
| `host.min-lead-minutes` / `slot-minutes` | 10 / 30 |
| `host.limits.max-cap` (ticket holders) | 120 |
| `deploy.distance` / `speed` | 300 blocks / 0.6 per tick |
| `elytra.max-glide-seconds` | 60 |
| `match.end-kick-seconds` | 10 |
| `economy.coins-per-kill` | 5 |
| zone | 5 phases, about 17 minutes, then the 5-block circle orbits radius 100 at 4.3 blocks/s |
