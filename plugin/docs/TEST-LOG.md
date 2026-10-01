# Test log

Local server: Paper 1.21.11 build 132, Java 21, offline mode, port 25599, on the owner's PC.
Clients: Mineflayer 4.39 bots (`tools/bots/bots.js`, `tools/bots/buyer.js`) on the same PC.
Discord: `tools/hookcatcher.js` (a local receiver that prints each embed).
Arena: the generated stand-in arena (no real map installed yet). Zone shortened to 3 phases on the dev server only.

## Unit tests (`mvn test`): 25 pass

| Suite | What |
|---|---|
| LogicTest (17) | Purge: 200 players, cap 120, exactly 80 purged, no VIP+/hoster purged; over 10,000 runs VIPs purged far less than default players. Immune overflow. Duos autofill with an odd count; squads keep partial teams. Loot: slots always 3-8, distinct, amounts within min/max over 5,000 chests; Luckier Loot raises rare drops; Pearls off removes pearls. 300 chests with 24-block spacing and an exact 180/90/30 tier split. Zone: every circle inside the previous, pause then shrink, final circle orbits radius 100 at 4.3 blocks/s. Daily shop: same all day, 2 per category, legendary rare over 10,000 rolls. Schedule parsing and next start. HUD spacing glyphs add up exactly. |
| StoreTest (5, real SQLite) | Many admin and bought games can be queued; a schedule occurrence is created once without using up game numbers; ticket give/revoke idempotent per transaction; 10 rapid buys charge once; coins never negative. |
| ResourcesTest (3) | Every message key used in code exists; every message parses as MiniMessage; no emoji in any shipped file. |

## On the server

| # | Test | Result |
|---|---|---|
| 1 | ACC: log in with no game | Kicked with "No game is being hosted right now. See our Discord for the next one." |
| 2 | Scheduler | Created today's 16:00 Duos game 60 min ahead by itself; posted HOOK-1 with the @Events ping. |
| 3 | HOOK-1/3/4 | Hosted (role ping, allowed_mentions limited to the role), "Purge is starting!" (no ping), "Game #N Results" (winners, kills, MVP). All red, no emoji. |
| 4 | Full match, 8 bots, Duos | Join window, countdown titles, purge, team-up, boarding, ghast ride, drops, landing, zone, results, kick after 10 s, server shut down once (END-5). |
| 5 | DEP with 8 bots | All boarded; one sneaked off early; all glided and landed with their kit chestplate back and no elytra; no fall damage. |
| 6 | Kill credit | Melee kills credited to the attacker; zone deaths credited to nobody; 5 coins per kill (stats.json shows kills). |
| 7 | Purge, 8 players, cap 5 | Exactly 3 kicked with the purge message; 5 selected. |
| 8 | Teams (`/team invite`, `/team accept`) | Team formed (2/2); rest auto-grouped; winning team's dead teammate counted as a winner; no team kills. |
| 9 | Resource pack | Offered on join, all 8 clients reported SUCCESSFULLY_LOADED (pack served locally). |
| 10 | One Time Shield | 5 shields in the offhand, blocking: 5 hits absorbed, one shield per hit (5, 4, 3, 2, 1, 0), next hit landed. A TNT cart exploding next to the blocker broke exactly one. |
| 11 | `/cart selftest` (WORLD-1/2, LOOT-7) | TNT cart breaks blocks: PASS. No block drops: PASS. Rails drop: PASS (after two fixes). Loot chest survives: PASS. |
| 12 | Tickets (HOST-4/5, 6.2) | `cart ticket give <uuid> 1 tx-100` gave 1, whitelisted the buyer; same tx again ignored. Buyer joined with no game open, Host GUI opened by itself, Finish queued the game 10+ minutes ahead and spent the ticket. `cart ticket revoke <uuid> 1 tx-100` cancelled that game and removed the whitelist entry. |
| 13 | Arena reset | Every boot wiped and regenerated the arena (stand-in) before the world loaded; no chests, holograms or ghasts left. |
| 14 | Stats (FILE mode) | `stats.json` matches the website shape: top wins/kills/played, last rounds, live status. |
| 15 | Broken config | A config.yml with an invalid character used to be replaced by defaults. Now the plugin refuses to start and leaves the file alone. |

## Load test (TECH-4): 30 bots, solo, full match

Samples from Paper's `/mspt` every 5 s (avg / min / max tick time in ms, last 5 s).

| Phase | Before fixes | After fixes (final build) |
|---|---|---|
| 30 players in the lobby | 6-8 avg, max 37 | 5-9 avg, max 35 |
| Boarding (30 players onto 8 ghasts) | one 3,997 ms tick | spread over 4 ticks, max 244 ms |
| Chest placement (80 chests) | one 2,014 ms tick | max 55 ms (chunks loaded async first) |
| 16 eliminations within 2 s | 614 ms tick | max 36 ms (no respawn) |
| Fight / zone | 4-20 avg | 3-13 avg |

TPS stayed at 19.8-20 in the final run. The bots and the server ran on the same PC, so real numbers on a dedicated host should be better.

## Bugs found by testing and fixed

1. Chest scan crashed: chunk snapshot needed its height map; a failing chunk also stalled the scan.
2. Landing trusted the client's on-ground flag; now a solid block under the feet is required, and fall protection lasts until the real landing.
3. Zone loop could crash when the last kill ended the game mid-loop.
4. A uniqueness rule on the game source blocked the second admin or bought game (migration v2). A database test covers it now.
5. Ignored duplicate schedule inserts used up game numbers.
6. One Time Shield: the same-tick guard read the arena's world clock, which is frozen, so after the first block every hit was blocked for free. Also fixed in the cosmetics particle budget and the wind-charge check.
7. Rails vanished when the block under them was blasted.
8. A chargeback left the buyer on the whitelist (cleanup ran before the game was cancelled).
9. A config.yml syntax error made the plugin overwrite the file with defaults.

## Not tested with a real client (needs the owner in game)

- Visuals: the IP bar layout, the One Time Shield model and blocking pose, zone particles, holograms.
- Grappling hook feel, VIP wind charges pushing only the thrower, the VIP+ lobby elytra.
- Clicking through the Cosmetics, Daily Shop, Locker, Loot editor, Schedule and Spectate menus (the purchase logic itself is covered by StoreTest).
- LuckPerms (not installed on the dev server): the `host` group grant and the `[VIP]` / `[VIP+]` tags from real groups.
- A real Discord channel and a real CraftingStore purchase.
- The real arena map.

## The Lost Island and the Cubrix lobby (2026-10-01)

- Island world (1.21.4) force-upgraded to 1.21.11 (41,655 chunks). Build measured from the height maps:
  x -921..-35, z -968..-9, top y 208, flat ocean around it at y 71. Template trimmed to 9 region files (79 MB).
- Lobby world trimmed from 974 MB to 28 MB (only the 4 region files that hold the build).
- Chest scan: 529,182 spots in 24 s; 140 chests for 60 players placed without lag. Self-test: all PASS.
- 8-bot match on the island: route at y 236, drops and landings on terrain between y 83 and 126, chestplates
  restored, average tick 2 ms. The first route ended near the east shore and two auto-dropped bots glided into
  the sea; the route now ends over the middle (x -800 to -280) and a rerun landed everyone on the island.
- A fresh server built only from `CartZone-server-files.zip` booted into the island and passed the self-test.
