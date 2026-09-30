# MASTER PROMPT: build the CartRoyale Paper plugin

You are a senior Minecraft server-plugin engineer. Build **CartRoyale**, a single Paper plugin that runs an entire minecart-PvP battle royale server for **cartroyale.com**: access control, hosted games, purge, teams, deployment from Happy Ghasts, loot chests, a shrinking zone, cosmetics and a daily shop, Discord announcements, and stats for the website.

Read these files first, in order. They are all in this folder.
1. `00_START-HERE.md`: context and project status
2. `03_PLAN_original-from-notion.md`: the owner's plan, word for word. This is the requirements source.
3. `04_DECISIONS-and-defaults.md`: how every ambiguity is resolved (**D#** ids). Follow these defaults.
4. `05_STORE-and-ranks.md`: ranks, tickets and the CraftingStore command contract
5. `06_INFRASTRUCTURE-checklist.md`: the server environment
6. `08_BRAND.md`: colours, naming, tone
7. `reference/`: three example screenshots (webhook messages and the top-centre bar)

## 1. How to work

1. **Ask first, once.** Send the owner the Q1–Q7 questions from `04_DECISIONS-and-defaults.md` in a single message. If they don't answer, use the defaults and continue. Don't ask anything else unless you are truly blocked.
2. **Verify the platform.** Before writing code, check the current Paper docs (https://docs.papermc.io) for the target version. Confirm these exist and note any differences:
   - the Happy Ghast entity and its harness/riding API (Java 1.21.6+)
   - item data components (`max_stack_size`, `item_model`, `blocks_attacks`)
   - `TextDisplay` entities
   - Adventure `BossBar` and MiniMessage
   - `WorldBorder`, and the wind-charge and elytra events
   Where an API doesn't behave as this prompt assumes, adapt and record it in `docs/DEVIATIONS.md`.
3. **Work in milestones** (section 8). Finish, test and commit each one before starting the next. Small commits with clear messages. Keep a `CHANGELOG.md`.
4. **Test on a real local Paper server** with the plugin loaded (use the `run-paper` Gradle task or a `dev-server/` folder). After every milestone, run the manual test script for it and paste the results into `docs/TEST-LOG.md`.
5. **Secrets never go into code, git or chat.** Webhook URLs, API keys and the stats token live in a git-ignored `secrets.yml` in the plugin data folder. Ship `secrets.example.yml` with placeholders. Never log secrets.
6. **Don't copy another server's work.** The example images come from mace.vip. Use them for layout only. All names, text, art, sounds and effects are original.
7. **No pay-to-win.** Ranks may affect lobby extras and queue/purge priority only. Nothing sold changes combat, loot or damage.
8. **Don't guess about money.** The plugin never handles payments. CraftingStore runs console commands (section 6.2).

### Non-goals
Bedrock support, anti-cheat, an Elo/rating system (see D18), several arenas at once, Folia, a web admin panel.

## 2. Tech stack (requirements)

- **Language/build:** Java (use the Java version Paper requires; 21 for 1.21.x). Gradle (Kotlin DSL). `paper-plugin.yml` or `plugin.yml`. Package root `com.cartroyale`. Output jar: `CartRoyale.jar`.
- **APIs:** Paper API, Adventure/MiniMessage. **LuckPerms API** as a soft dependency. Keep NMS out unless there is no alternative, and isolate it if used.
- **Persistence:** SQLite via JDBC (with a small connection pool or a single-writer queue). All database and HTTP work happens **off the main thread**. Use transactions for anything that touches coins, tickets or purchases.
- **Config:** YAML files with comments, reloadable with `/cart reload`. All text lives in `messages.yml` (MiniMessage). Nothing user-visible is hardcoded.
- **Time:** wall-clock times use the configured time zone (default UTC). Countdowns are computed from timestamps, not tick counts, because the server restarts after every match.
- **Quality:** unit tests for the pure logic (weighted purge, loot rolls, zone math, orbit math, daily-shop seeding, schedule parsing). Log at sensible levels. No `printStackTrace`.

## 3. Glossary

- **Cart**: the minecart. TNT minecarts on rails are the signature weapon.
- **Game / match**: one full round from lobby to results. Numbered `#1, #2, …` globally.
- **Hoster**: the player who bought a Game Ticket and set up a specific game, or the admin who created it.
- **Purge cap**: the maximum number of players who enter a match.
- **Purge**: the random removal of surplus players when the lobby exceeds the cap (D2).
- **Modes**: **LT Cart** and **HT Cart**. Each has its own kit.
- **Modifiers**: **No Fall Damage**, **Luckier Loot**, **Pearls** (whether ender pearls are in the loot pool).
- **Squad size**: 1 (solo), 2, 3 or 4.
- **Zone**: the shrinking damage circle.
- **Coins**: earned at 5 per kill. Spent in the daily shop.

## 4. Game flow (state machine)

```
IDLE → ANNOUNCED → JOIN_OPEN → COUNTDOWN → PURGE → TEAMING → BOARDING → DEPLOY → PLAYING → ENDING → RESETTING → (server restarts) → IDLE
```
- One active game at a time (D23). Games are queued in the database with their settings and start time.
- On plugin enable, restore the queue from the database. If a game is due, resume the flow.
- Every transition is logged with the game id.

## 5. Requirements

Each requirement has an id and an **Acceptance** line: a test the result must pass.

### 5.1 Access control (ACC)
- **ACC-1** The vanilla whitelist stays **on** for staff and hosters. A custom login gate allows a login when the player is whitelisted/staff/hoster, or a game's join window is open (D4). Otherwise deny with a friendly configurable message.
- **ACC-2** The join window opens N minutes before start (default 5) and closes when the purge starts.
- **ACC-3** Admins can always join. Hosters are whitelisted by UUID when a ticket is granted.
- **Acceptance:** with no game, a normal player can't log in and sees the message. Once the window is open, they can. After the purge starts, new logins are denied. Staff can join at any time.

### 5.2 Hosting and scheduling (HOST)
- **HOST-1** Admin command `/cart host` opens the **Host GUI**. It sets: mode (LT/HT), modifiers (toggle each), squad size, **purge cap**, map (random or a named map), start time (now, +N minutes, or a chosen slot), and the hoster name shown in webhooks. Finish queues the game.
- **HOST-2** Admins can start a game immediately with `/cart start` and stop one with `/cart stop`. `/cart queue` lists upcoming games, and `/cart cancel <id>` removes one.
- **HOST-3** **Scheduler:** a GUI (`/cart schedule`) edits recurring entries. Each entry has days of week and a time, plus mode, modifiers, squad size, purge cap and map. Entries are saved to `schedule.yml`. A background task creates the next game from the schedule automatically and announces it (HOOK-1).
- **HOST-4** **Bought games** (D15). The command `cart ticket give <uuid> [n]` gives tickets, whitelists the UUID and grants the temporary `host` LuckPerms group. When a ticket holder joins, or runs `/cart host`, they get the Host GUI limited to their permissions (purge cap within `host.limits`, start time at least `host.min_lead_minutes` ahead, no overlap with queued games).
- **HOST-5** `cart ticket revoke <uuid> [n]` removes tickets and, if no ticket remains and no game is queued, the whitelist entry and `host` group. This is used for chargebacks.
- **Acceptance:** a ticket holder can create a game end to end and it appears in the queue and the webhook channel. Two games can't overlap. Revoking a ticket that already produced a queued game cancels that game (configurable).

### 5.3 Discord webhooks (HOOK)
Send with `java.net.http.HttpClient` **asynchronously**. Retry on HTTP 429 using `retry_after`. Queue messages so order is kept. Don't block the server. See `reference/` images 1 and 2 for layout, and use original wording.

- **HOOK-1 Game hosted.** When a game is created. Message content: `<@&EVENTS_ROLE_ID>` with `allowed_mentions.roles` set. One embed:
  - Title: `Game #{id} ({region})`
  - Description: `To be notified for games hosted, please go to <id:customize>` then a **Game Settings** list: Map, Team Size, Duration, Purge Cap, Mode, Modifiers (code-styled tags, or "None"), then an **Info** list: `Starting: <t:UNIX:R>` and `Server IP: \`play.cartroyale.com\``.
  - No unicode emoji anywhere in webhook text (owner's rule). Custom server emojis only if the owner uploads them.
  - Footer: `Hosted by {hoster}`. Colour `#FF0000`.
- **HOOK-2 5-minute warning.** 5 minutes before each game (including every queued game). Pings the role. Embed `5 minute warning!`, Server IP, "Hosted by".
- **HOOK-3 Purge starting.** No ping. Embed `Purge is starting!`: "Players are being selected into the game." plus `Players Online: **N**`.
- **HOOK-4 Results.** Embed `Game #{id} Results` with `Winner(s): \`a\` \`b\` (**K Kills**)` and `MVP: \`c\` (**K Kills**)`.
- **Acceptance:** each message renders in Discord as described. A failing webhook never crashes or stalls the game. The URL never appears in logs.

### 5.4 Purge (PURGE)
- **PURGE-1** When the lobby countdown ends, announce it with a title, a chat message and a sound. Count eligible players (not admins).
- **PURGE-2** If eligible players exceed the cap, remove `count − cap` players by weighted random **without replacement**: default weight 1.0, VIP 0.15, VIP+ and the hoster immune (D3). Use a weighted sampling method such as sorting by `-ln(u)/weight`.
- **PURGE-3** Purged players get a clear message and are kicked (D2). Selected players continue.
- **Acceptance:** with 200 fake entries and a cap of 120, exactly 80 are purged, no VIP+ or hoster is purged, and over 10,000 simulated runs VIPs are purged far less often than default players (unit test).

### 5.5 Teams (TEAM)
- **TEAM-1** For squad size above 1, run a team-up phase after the purge (D5): `/team invite|accept|leave|list`, a hotbar item opening a team GUI, and auto-grouping of leftovers at the end.
- **TEAM-2** Team members can't hurt each other. They have a shared nametag colour. A team wins when it is the last one alive.
- **Acceptance:** duos form and auto-fill correctly with an odd player count. Friendly fire is off.

### 5.6 Deployment (DEP)
- **DEP-1** Give the kit and spawn Happy Ghasts (D6), equipping any harness required to ride. Put players on them. Make the ghasts invulnerable, with no AI or gravity, and move them along a straight route of `deploy.distance` blocks at `deploy.speed` and `deploy.altitude`.
- **DEP-2** Every player gets a **temporary elytra** placed in the chestplate slot. The real chestplate is remembered. The elytra can't be removed, moved, swapped, dropped or hidden by any inventory action (cancel every relevant click, drag, drop, hand-swap and creative-inventory event).
- **DEP-3** Sneaking drops the player early. At the end of the route all remaining players are dropped. On dismount, start gliding automatically.
- **DEP-4** On landing (ground, water, lava, a climbable block or a vehicle), remove the elytra and restore the remembered chestplate. Cancel fall damage from that landing. Enforce `elytra.max_glide_seconds`.
- **DEP-5** Handle disconnects while boarding or gliding: restore state, and don't leave orphaned ghasts or elytras.
- **Acceptance:** 8 test clients board, ride, drop at different moments and land. Each ends with their original chestplate and no elytra. No ghast remains after the drop.

### 5.7 Kits, modes and armor (KIT)
- **KIT-1** Each mode (**LT Cart**, **HT Cart**) has an admin-editable kit: inventory, armor and offhand. Stored serialized, per mode.
- **KIT-2** `/kitready` clears the admin's inventory, armor and offhand (after a confirmation) so they can build a kit from scratch. `/cart kit set <lt|ht>` saves the current inventory as that mode's kit. `/cart kit preview <mode>` shows it.
- **KIT-3** The kit is given on boarding (DEP-1).
- **KIT-4** Kit armor can be taken off and dropped. All armor is **unbreakable** and **vanishes on death** (it isn't in the death drops). Other items drop normally.
- **Acceptance:** two admins set different kits for LT and HT. A match in each mode gives the right kit. Armor never loses durability. On death, armor doesn't drop and the other items do.

### 5.8 Loot chests (LOOT)
- **LOOT-1 Placement** (D9). On game start, generate candidate positions by scanning the arena off the main thread (use chunk snapshots): a full solid block below (check the collision shape is a full cube), 2 blocks of clear air above, not next to liquids. Pick positions with Poisson-disc spacing, split across three tiers (default 60/30/10), scale the count with players, and honour admin include/exclude zones. Place a chest block facing a random direction.
- **LOOT-2 Tiers.** 1★ Bronze, 2★ Silver, 3★ Gold. Each has its own loot table, editable in a GUI (`/cart loot`) and in `loot.yml`.
- **LOOT-3 Entries.** Each entry has an item (any ItemStack, including custom items), a **rarity** (Common/Rare/Epic/Legendary), a **min and max amount**, and optional flags (`pearl`). The editor GUI supports adding an item by dragging it in, cycling rarity, adjusting min/max and removing entries. Validate `max ≤ item max stack size`.
- **LOOT-4 Filling** (D8). Each chest gets a random slot count between **3 and 8**, in distinct random slots. Each slot picks an entry by rarity weight and rolls an amount uniformly between min and max. Modifiers: **Luckier Loot** multiplies rare+ weights (defaults in D8). **Pearls** OFF removes `pearl`-flagged entries.
- **LOOT-5 Holograms.** A centred `TextDisplay` above each chest shows the tier: `★`, `★★` or `★★★` in bronze/silver/gold. Track their UUIDs.
- **LOOT-6 Cleanup.** After **every** round and on plugin disable, remove **all** chests and holograms from the arena (also clean up after a crash on the next boot).
- **LOOT-7** Explosions never destroy chests.
- **Acceptance:** 300 chests generated on the test map, all on full solid blocks, none overlapping, tier ratio near 60/30/10. Slot counts are always 3–8. Amounts fall within min/max. Luckier Loot measurably raises rare drops (unit test with fixed seeds). Nothing remains after the round.

### 5.9 One Time Shield (SHIELD)
- **SHIELD-1** A custom item called **One Time Shield**, stackable to **16** (use the `max_stack_size` component on a shield-like base item), with a custom model from the resource pack. Identified by a PDC key. Lore explains it.
- **SHIELD-2** Worn in the **offhand** and blocking: the first hit it blocks, from **any source** including melee, projectiles and explosions such as a TNT-minecart blast, is fully blocked, then **one shield is consumed** from the stack and the shield-break sound plays.
- **SHIELD-3** It appears in loot tables through a normal entry (D10). It can be added to kits like any item.
- **If vanilla blocking doesn't work with stacked or custom shields on the target version,** implement the block-and-break logic yourself and record it in `docs/DEVIATIONS.md`.
- **Acceptance:** holding 5 shields and being hit 5 times while blocking absorbs all 5 hits, one shield gone per hit. A cart explosion breaks one. An unblocked hit breaks nothing.

### 5.10 Carts and blocks (WORLD)
- **WORLD-1** TNT-minecart explosions **can break blocks** in the arena.
- **WORLD-2** In the arena, blocks **never drop items**, with two exceptions: **rails** and **minecarts** (they drop as items). Cancel drop-item events and set `dropItems(false)` on breaks, and handle explosions.
- **WORLD-3** Players can break and place blocks in the arena. The lobby is fully protected.
- **WORLD-4** Track who placed/ignited a cart so kills from explosions are credited to that player (needed for ECON-1).
- **Acceptance:** break stone, wood and ores by hand and by explosion: no drops. Break a rail and pick up a cart: both drop.

### 5.11 The zone (ZONE)
Follow D11 exactly.
- **ZONE-1** Use a **custom circular zone** (centre + radius). Don't rely on the vanilla border to block movement. Use the **vanilla WorldBorder only as the map boundary**.
- **ZONE-2** Phases in `zone.yml`: for each phase, `wait_seconds`, `shrink_seconds`, `end_radius`, `damage_hp` and `damage_interval_ticks`. The zone pauses, then shrinks smoothly (interpolate every tick or two), like Fortnite. Each next circle lies inside the previous one, its centre drifting toward the final centre.
- **ZONE-3** Players can always walk through the edge. Standing outside deals damage on the configured interval (default 2 HP every 20 ticks). Damage is attributed to the zone (no coin reward).
- **ZONE-4** Admin setup: `/cart zone` edits the **starting circle** (centre + radius) and the **final centre**, and shows a preview. Values persist per map.
- **ZONE-5 Final stage.** At radius 5, the circle's centre orbits the **orbit centre** on a circle of **radius 100** at walking speed (about 4.3 blocks/s, so angular speed = speed / 100 rad/s). It keeps orbiting until the match ends.
- **ZONE-6 Visuals.** Draw a particle wall only within about 40 blocks of each player. Tint the screen or show an actionbar warning when outside. Keep it cheap.
- **Acceptance:** a full run of all phases on the test map. The circle shrinks and pauses on schedule. Outside players take damage and can still walk out and back in. The final circle orbits smoothly.

### 5.12 Death and spectating (DEATH)
- **DEATH-1** On death, put the player in spectator mode inside the arena bounds. Keep them there (cancel movement past the bounds and any teleport outside).
- **DEATH-2** A hotbar item opens a player picker to follow someone. Spectators can look around freely.
- **DEATH-3** Spectator chat is only seen by spectators.
- **Acceptance:** a dead player can fly around and follow players, can't leave the arena, and can't be seen by alive players in chat.

### 5.13 Match end (END)
Follow D13.
- **END-1** Detect the winning team (or the timeout). Save stats and record the game in the database.
- **END-2** Send HOOK-4 (results).
- **END-3** After a short delay, kick everyone except admins.
- **END-4** Regenerate the arena from its saved **template** (`/cart arena save <name>` snapshots the current state). The most robust method is to restore the world folder before the world loads on the next boot.
- **END-5** Restart the server **once**, after the reset is queued. Log clearly if no restart wrapper is running. See `06_INFRASTRUCTURE-checklist.md`.
- **Acceptance:** after a match with damage everywhere, the next boot shows the map exactly as saved, with no chests, holograms or ghasts. The queue survives, and the next game (if any) announces on time.

### 5.14 Lobby (LOBBY)
- **LOBBY-1** A dedicated lobby world or area. No damage, no hunger, no block changes, no item drops.
- **LOBBY-2 Hotbar** (D17): slot 1 grappling hook, slot 5 Cosmetics, slot 9 Stats (optional). The hoster/admin gets an extra Host item. **All features open from hotbar items**, as in the plan.
- **LOBBY-3 Grappling hook.** A fishing rod (unbreakable, custom name). When the hook lands on a block, pull the player toward it with a clamped velocity. **0.5 s cooldown**, shown with the item cooldown. Range limit configurable. Lobby only.
- **LOBBY-4** Sidebar/tab header show the IP and useful info (players, next game time).
- **Acceptance:** the hook works, can't be spammed faster than every 0.5 s, and doesn't work in the arena.

### 5.15 Rank perks (RANK)
Ranks come from LuckPerms groups/permissions (see `05_STORE-and-ranks.md`).
- **RANK-1** Purge weights (PURGE-2).
- **RANK-2 Lobby items.** VIP: **wind charges** in the offhand, refilled automatically. VIP+: an **elytra** and wind charges. Lobby only (D17).
- **RANK-3** A wind charge may push **only the player who threw it**. Cancel the push on everyone else (research the right event for the target version).
- **RANK-4** Show a rank tag in chat and the tab list: `[VIP]` in green, `[VIP+]` in gold (`#FFD700`).
- **Acceptance:** two VIPs can't push each other with wind charges but can each boost themselves.

### 5.16 Economy and cosmetics (ECON)
- **ECON-1 Coins.** **5 coins per kill**, credited to the credited killer (including cart kills, WORLD-4). No coins for team kills, self-kills or zone damage. Show `+5 coins` on the actionbar. Stored in the database.
- **ECON-2 Categories.** **Kill effects** (played at the victim's location), **cart explosion effects** (played on the owner's cart explosions, cosmetic only), **suffixes** (shown after the name in chat, tab and nametag).
- **ECON-3 Rarities.** Common, Rare, Epic, Legendary, with the odds and price bands from D16. Effects are data-driven in `cosmetics.yml`: a list of particle/sound steps with delays, plus id, name, category, rarity, price and description. Ship at least **6 per category** across all rarities.
- **ECON-4 Daily shop.** A **global** shop of 6 items (2/2/2), refreshed at 00:00 in the configured zone, seeded by the date so it's reproducible, and stored so restarts don't reshuffle (D16). Items already owned show as Owned. Buying deducts coins atomically and can't double-charge under rapid clicks.
- **ECON-5 Locker.** A paginated GUI per category: equip, unequip, preview. One equipped item per category.
- **ECON-6 Navigation.** Hotbar **slot 5** opens a small GUI with **Locker** and **Daily Shop**. Show the player's coin balance.
- **ECON-7** Cap cosmetic particles per tick per player so effects can't lag the server.
- **Acceptance:** killing a player gives 5 coins. The shop shows the same items to everyone until midnight. Buying works once per item and deducts correctly. Equipped effects fire. Legendary items appear rarely (check over 10,000 seeded rolls).

### 5.17 Stats and website data (STATS)
- **STATS-1** Track per player: kills, wins, games played. Per game: id, hoster, mode, squad size, purge cap, modifiers, map, start/end, winners, MVP and kills.
- **STATS-2** Export the data for the website (D20). Implement the **Cloudflare Worker push** mode first: an async HTTPS POST every 60 seconds and after each game to `stats.export.url` with a bearer token from `secrets.yml`. Keep `FILE` mode (JSON written to the data folder) as a fallback.
- **STATS-3** JSON shapes, matching the website config in `site/assets/app.js`:
  ```json
  {
    "updated": "2026-10-01T12:00:00Z",
    "wins":   [{ "name": "Player", "val": 12 }],
    "kills":  [{ "name": "Player", "val": 240 }],
    "played": [{ "name": "Player", "val": 31 }],
    "rounds": [{ "id": 42, "winner": "Player", "players": "18/20", "time": "07:42" }],
    "status": { "online": 12, "max": 120, "state": "lobby|playing|idle" }
  }
  ```
  Top 10 per leaderboard, the last 8 rounds.
- **Acceptance:** after a test match the JSON contains the right winner, kills and times. A failed upload retries and never affects gameplay.

### 5.18 UI and branding (UI)
- **UI-1** Primary colour **#FF0000**. MiniMessage everywhere. Prefix `<#FF0000><b>CartRoyale</b> <gray>»` (D22).
- **UI-2 Top-centre IP bar.** A permanent boss-bar-style element for every player showing the IP (`play.cartroyale.com`, configurable). Build it with a custom boss-bar sprite plus a font glyph icon from the resource pack (see `reference/` image 3). Make it original: red, with the minecart icon. Choose a boss-bar colour slot no other bar uses, because a sprite override applies to every bar of that colour.
- **UI-3 Resource pack** (D21). Generate `resourcepack/` and a zip with a stable SHA-1. Contents: One Time Shield item model (via `item_model`), the IP-bar sprites and font, and GUI icons if wanted. Set the correct `pack_format`, send the pack on join with a prompt, and handle declines with a clear message. The pack must not break vanilla visuals.
- **UI-4** Every GUI title, button and message uses the brand style, with clear lore text. GUIs are safe: cancel all clicks and drags, and guard against dupe exploits.
- **Acceptance:** with the pack applied, the shield has its model and the IP bar shows at the top centre. Without the pack, the game still works and shows a fallback boss bar with plain text.

### 5.19 Admin and player commands (ADMIN)

| Command | Who | What |
|---|---|---|
| `/cart host` | admin, ticket holder | Host GUI (HOST-1/4) |
| `/cart start [now]`, `/cart stop`, `/cart queue`, `/cart cancel <id>` | admin | Game control |
| `/cart schedule` | admin | Scheduler GUI |
| `/cart kit set <lt\|ht>`, `/cart kit preview <mode>`, `/kitready` | admin | Kit setup |
| `/cart arena save <name>`, `list`, `setspawn`, `setbounds` | admin | Arena templates |
| `/cart loot` | admin | Loot table editor |
| `/cart zone` | admin | Zone setup |
| `/cart ticket give\|revoke\|list <uuid\|name> [n]` | console/admin | Tickets (CraftingStore uses console) |
| `/cart coins give\|take\|set <player> <n>` | admin | Economy |
| `/cart reload` | admin | Reload configs |
| `/team invite\|accept\|leave\|list` | player | Teams |

Permissions: `cartroyale.admin`, `cartroyale.host`, `cartroyale.rank.vip`, `cartroyale.rank.vipplus`. Provide tab completion, usage messages and clear errors.

### 5.20 Safety and performance (TECH)
- **TECH-1** No blocking I/O on the main thread. Chest scanning uses chunk snapshots off-thread and places blocks in small batches.
- **TECH-2** Remove every entity and block the plugin creates (chests, holograms, ghasts, display entities) after each match and on disable. Sweep for orphans on enable.
- **TECH-3** Survive `/reload`-style problems by refusing to run without a clean start (warn loudly). Fail safe if the database is unavailable.
- **TECH-4** Load test with **at least 30 bot clients** (for example Mineflayer on an offline-mode local server): join window, purge, teams, deploy, a shortened zone, end and reset. Record TPS during the run in `docs/TEST-LOG.md`.

## 6. Integration contracts

### 6.1 LuckPerms
Groups: `vip`, `vipplus`, `host`, `admin` (D: Q2). Grant the permission nodes from 5.19 through those groups. Check ranks through the LuckPerms API with a cached lookup and a permission-node fallback. Temporary groups use LuckPerms' own expiry.

### 6.2 CraftingStore (store delivery)
CraftingStore runs these **console commands** when a purchase completes. Placeholders: `{uuid_dashed}` is the safest, and `{player}` strips non-alphanumeric characters, so it can break underscores.

| Package | Initial command | Chargeback command |
|---|---|---|
| VIP (30 days) | `lp user {uuid_dashed} parent addtemp vip 30d accumulate` | `lp user {uuid_dashed} parent remove vip` |
| VIP+ (30 days) | `lp user {uuid_dashed} parent addtemp vipplus 30d accumulate` | `lp user {uuid_dashed} parent remove vipplus` |
| Game Ticket | `cart ticket give {uuid_dashed} 1 {transaction_id}` | `cart ticket revoke {uuid_dashed} 1 {transaction_id}` |

The `cart ticket` commands must run from the console, must be **idempotent per transaction id** (the same id given twice grants once), and must work when the player is offline. Record every change in `ticket_log`.

### 6.3 Discord
Webhook URL and role id come from `secrets.yml` / `config.yml` respectively. See HOOK-1 to HOOK-4.

### 6.4 Website
See STATS-2/3 and `02_PROMPT_site.md`.

## 7. Data model (SQLite, adjust as needed)

`players(uuid, name, coins, kills, wins, played, first_seen)`, `cosmetics_owned(uuid, cosmetic_id, acquired_at)`, `cosmetics_equipped(uuid, kill_effect, explosion_effect, suffix)`, `games(id, hoster_uuid, hoster_name, mode, squad, purge_cap, modifiers, map, scheduled_at, started_at, ended_at, status, winners, mvp_uuid, mvp_kills)`, `game_players(game_id, uuid, team, kills, placement)`, `tickets(uuid, count)`, `ticket_log(uuid, delta, source, transaction_id, at)`, `daily_shop(date, items)`.
Use migrations (a `schema_version` table).

## 8. Milestones

Deliver in this order. Each milestone ends with tests and a commit.

| # | Scope | Done when |
|---|---|---|
| M0 | Project scaffold, dev server, config/messages framework, database layer, command framework, `secrets.yml` | The plugin loads and a test command works |
| M1 | State machine, access gate, lobby world/protections, manual host/start/stop | ACC acceptance and the section 4 state flow pass with 2 clients |
| M2 | Webhooks (HOOK-1..4), scheduler, Host GUI, ticket commands | A ticket buyer can host a game and Discord shows all messages |
| M3 | Purge, teams, kits/modes, `/kitready` | PURGE and TEAM and KIT acceptance pass |
| M4 | Deployment: ghasts, elytra, landing | DEP acceptance passes with 8 clients |
| M5 | Arena rules: loot chests, holograms, block drops, carts, One Time Shield | LOOT, SHIELD and WORLD acceptance pass |
| M6 | Zone, death/spectate, win detection, results, kick, arena reset, restart | ZONE, DEATH and END acceptance pass |
| M7 | Economy: coins, cosmetics, daily shop, locker, hotbar GUIs, grappling hook, rank perks | ECON, LOBBY and RANK acceptance pass |
| M8 | Resource pack, IP bar, shield model, GUI polish | UI acceptance passes |
| M9 | Stats export for the website, load test, docs, hardening | STATS and TECH acceptance pass, 30-bot test logged |

## 9. Deliverables

- `plugin/` source and Gradle project, and the built `CartRoyale.jar`
- `plugin/src/test/...` unit tests
- `resourcepack/` and `cartroyale-pack.zip` with its SHA-1 in `docs/RESOURCEPACK.md`
- Example configs with comments: `config.yml`, `messages.yml`, `loot.yml`, `cosmetics.yml`, `zone.yml`, `schedule.yml`, `secrets.example.yml`
- `docs/ADMIN-GUIDE.md`: how an owner sets up kits, maps, loot, zone and the schedule
- `docs/CONFIG.md`, `docs/TEST-LOG.md`, `docs/DEVIATIONS.md`, `CHANGELOG.md`
- A final report to the owner: what works, what deviates from the plan and why, the manual steps still needed, and known issues

## 10. Definition of done

Every acceptance line above passes on a real local Paper server, the 30-bot test is logged, the docs exist, there are no secrets in the repo, and the owner can run a complete game (host, purge, ride, fight, zone, results, reset, restart) by following `docs/ADMIN-GUIDE.md`.
