# Decisions, assumptions and defaults

The plan (`03_PLAN_original-from-notion.md`) leaves gaps. For every gap I chose a **default** so the build never blocks.
**The build agent:** use the default unless the owner says otherwise. Ask about the items marked **ASK** in ONE message at the start.
**The owner:** skim this. If a default is wrong, tell Claude the number (e.g. "D6: do it the other way").

## Blocking questions (ask these first, in one message)

| # | Question | Default if unanswered |
|---|----------|----------------------|
| Q1 | Which Minecraft version and Paper build will the server run? It must include the Happy Ghast (Java 1.21.6 or newer). | The latest stable Paper. Verify the Happy Ghast API exists. |
| Q2 | Do you use LuckPerms, and what are the group names for VIP, VIP+ and hoster? | LuckPerms, groups `vip`, `vipplus`, `hoster`, `admin`. |
| Q3 | Are the arena map(s) built? Where are they (a folder, a world zip)? How many? | Assume 1 map. Build a stand-in flat test arena if none is provided. |
| Q4 | What time zone should schedules and the daily shop reset use? | `UTC`. |
| Q5 | Which team sizes at launch: solo, duos, trios, squads? | Solo, Duos, Trios, Squads (1–4). |
| Q6 | Is the server on a host with an automatic restart (panel/VPS)? | Provide a restart-loop script (see `06_INFRASTRUCTURE-checklist.md`). |
| Q7 | Where will the resource pack be hosted (URL)? | Publish it on the website host, e.g. `https://cartzone.vip/pack/cartzone-pack.zip`. |

### Owner's answers (2026-09-30)

| # | Answer |
|---|---|
| Q1 | **Paper 1.21.11** (build 132 was the latest stable that day). |
| Q2 | LuckPerms, groups `vip`, `vipplus`, **`host`** (not `hoster`), `admin`. |
| Q3 | One arena map exists (location still to be handed over). Until then, a flat stand-in arena is generated for testing. |
| Q4 | `UTC`. |
| Q5 | **Solos, Duos, Squads** (1, 2, 4). No trios. Default daily schedule, from the owner's example: **16:00 Duos, 18:00 Squads, 20:00 Solos**. |
| Q6 | The host (Purpify) restarts the server automatically. No restart script needed. |
| Q7 | The resource pack is hosted by the owner on **mcpacks**. The plugin takes the URL and SHA-1 from `config.yml`. |
| - | **No cart skins** anywhere (owner, 2026-09-30). Cosmetics are only kill effects, cart explosion effects and suffixes. The site's store cards were rewritten to the plan's perks the same day. |

The Discord webhook URL, the @Events role ID and every other secret: **never ask for them in chat and never write them into files**. Put a placeholder in `secrets.yml` and tell the owner to fill it in.

## Decisions

**D1. Server platform.** Paper, Java Edition only. No Bedrock, Folia or Spigot compatibility promised. Use the Paper API. Avoid NMS and reflection unless nothing else works.

**D2. What "purge" means.** The plan uses the word two ways. Chosen reading:
- The **purge cap** is the maximum number of players who enter the match.
- When the lobby countdown ends, the **purge** runs. If more players are online than the cap, the extra players are *purged* (removed from this game, and kicked with a friendly message). Everyone left is "selected into the game" (matches the webhook text "Players are being selected into the game").
- If the lobby has fewer players than the cap, nobody is purged.
- Purged players are **kicked** by default. `purge.action` can be `KICK` (default) or `SPECTATE`.

**D3. Purge weights.** Purge picks who to remove by weighted random, without replacement.
- Default players: weight `1.0`
- VIP: weight `0.15` ("a lot lower chance", configurable)
- VIP+: **immune**
- The **hoster** (the player who bought that game): immune and plays.
- Admins/staff never play. They are spectators.
- Edge case: if immune players alone exceed the cap, pick among them at random and log a warning.

**D4. Joining.** Players can't join normally.
- Keep the vanilla whitelist **on** for staff and hosts.
- Add a custom login gate: allow a login if the player is whitelisted/staff/hoster, **or** a game's join window is open. Otherwise deny with: "No game is being hosted right now. See our Discord for the next one."
- The join window opens **5 minutes before start** (matches the 5-minute warning) and closes when the purge starts. The lead time is configurable.
- `max-players` must exceed the largest purge cap plus staff plus a buffer.

**D5. Teams.** After the purge there is a team-up phase, only for team modes (squad size above 1).
- Length: 90 seconds (configurable).
- Tools: `/team invite|accept|leave|list` plus a hotbar item that opens a team GUI.
- Friendly fire is off inside a team, and team members get a colour-coded nametag.
- When the phase ends, leftover players are auto-grouped into teams of the chosen size (the last team may be smaller).
- Solo mode skips the phase.

**D6. Deployment (Happy Ghast + elytra).** The plan's sentence is cut off after "gives you the". Chosen completion: the game remembers what the player was wearing (mainly the chestplate) and **gives it back when they land**.
- A Happy Ghast carries up to **4 riders** in vanilla (verify). Spawn one ghast per team of up to 4, and fill spare seats with solo players. All ghasts fly the same route side by side.
- The route is a straight line of a configurable distance at a configurable altitude and speed. At the end, everyone is dropped.
- Players can drop early by sneaking. Each player gets a **temporary elytra** that can't be removed, moved or swapped. Auto-start gliding on dismount.
- Landing (ground, water, lava, climbable block, vehicle) removes the elytra and restores the stored chestplate. No fall damage from that landing.
- Fireworks don't boost the elytra unless loot allows it. `elytra.max_glide_seconds` is a safety cap.

**D7. Kits and armor.**
- The kit is given when players **board the ghast**, so they land with gear.
- Kit armor can be taken off and dropped by the player.
- **All armor is unbreakable** and **vanishes on death**.
- Everything else in a dead player's inventory drops at the death location, so the fight can continue over loot.

**D8. Loot chests.**
- Three tiers: 1★ Bronze, 2★ Silver, 3★ Gold.
- Each chest fills between **3 and 8** random slots.
- Each loot entry has an item, a **rarity** (Common/Rare/Epic/Legendary, which sets its weight) and a **min–max amount** (so a stack can be 12 one time and 46 another).
- Default rarity weights: Common 60, Rare 25, Epic 12, Legendary 3.
- **Luckier Loot** multiplies the weights: Rare ×1.5, Epic ×2, Legendary ×3.
- **Pearls** ON keeps ender-pearl entries in the pool. OFF removes them.
- Chests fill when they spawn.

**D9. Chest placement.**
- Scan the arena for spots whose floor is a full solid block with 2 blocks of air above.
- Space chests apart with Poisson-disc sampling (default minimum 24 blocks).
- Tier split about 60% / 30% / 10% for 1★ / 2★ / 3★. Total chest count scales with the number of players.
- Admins can mark include/exclude zones.
- Explosions never destroy chests.
- Every chest gets a floating TextDisplay hologram with its stars. All chests and holograms are removed at the end of every round.

**D10. One Time Shield.**
- A custom shield item, stackable to 16, worn in the offhand.
- It absorbs **one** blocked hit from anything (melee, projectile, cart explosion). That hit is fully blocked, then one shield is consumed and the break sound plays.
- It's not in the kit by default. It appears in loot tables, and the entry is configurable.

**D11. The zone.** The vanilla WorldBorder **acts as a wall** (players can't walk out of it — verify on the target version), so it can't satisfy "you can always pass through it".
- Build a **custom circular zone**: a tracked centre and radius, damage while outside, particle-wall visuals drawn only near each player.
- Use the **vanilla WorldBorder as the map boundary** only (a hard wall for players and spectators).
- Phases live in config: wait time, shrink time, end radius, damage per phase. It pauses and shrinks like Fortnite. Each next circle sits inside the previous one and drifts toward the final centre.
- Damage outside the zone: **1 heart (2 HP) every 1 second** by default, configurable per phase.
- Starting circle and final centre are admin-settable.
- Final stage: radius **5**. The final circle's centre then orbits a point at **radius 100** at walking speed (about 4.3 blocks/s, angular speed about 0.043 rad/s).

**D12. Spectators.** A dead player switches to spectator, confined to the map bounds.
- A hotbar item lets them pick a player to follow.
- Spectators can't leave the arena and can't teleport out.
- Spectator chat goes only to other spectators.

**D13. Match end.**
1. Last team alive wins. Hard timeout: `duration + 5 min`, then the survivors are co-winners.
2. Send the results webhook and save stats.
3. Kick everyone except admins after 10 seconds.
4. Regenerate the map from the saved template.
5. Restart the server **once**. Restoring the map on boot is the most reliable approach.

**D14. Blocks and carts.**
- Blocks **never drop items** in the arena, **except** rails and minecarts (which do drop).
- TNT-minecart explosions break blocks. The blocks drop nothing.
- Players may break blocks in the arena (no drops). The lobby is fully protected.

**D15. Buying a game.**
- The CraftingStore command is `cart ticket give <uuid> 1`.
- It adds a ticket and whitelists the player by UUID. It grants the temporary `host` group (default 6 hours or until the game ends).
- When the player next joins, the **Host GUI** opens. It also opens with `/cart host`.
- GUI options: mode (LT/HT), modifiers, squad size, purge cap (within admin limits), map (random or specific), start time (a free slot at least 10 minutes ahead).
- **Finish** queues the game, posts the "hosted" webhook and consumes the ticket.
- If the player was offline at purchase, the ticket waits in the database.

**D16. Cosmetics and shop.**
- Three categories: **kill effects**, **cart explosion effects**, **suffixes**. All are purely visual. They never change explosion power or damage.
- Rarities: Common, Rare, Epic, Legendary. Rarer means rarer in the shop and more expensive.
- Default price bands: Common 50–100, Rare 200–400, Epic 600–1000, Legendary 1500–3000 coins.
- The daily shop is **global** (same for everyone) and shows 6 items: 2 kill effects, 2 explosion effects, 2 suffixes. Rarity odds: Common 60%, Rare 28%, Epic 10%, Legendary 2%.
- The shop resets at 00:00 in the configured time zone. The selection is stored, so restarts don't reshuffle.
- Coins: **5 per kill**. Nothing else earns coins by default. Coins can't be bought with real money.

**D17. Lobby.**
- Slot 1: grappling hook (fishing rod, 0.5 s cooldown).
- Slot 5: Cosmetics (opens a menu with **Locker** and **Daily Shop**).
- Slot 9: Stats/leaderboard link (optional).
- Only slot 5 comes from the plan. The others are suggestions.
- **VIP** gets wind charges in the offhand. **VIP+** gets an elytra and wind charges in the offhand.
- These are **lobby only** (in-match this would be pay-to-win, which the website promises never to sell).
- Wind charges only push the user, not other players.

**D18. Ranked.** The example webhook shows "Ranked: ON". The plan has no rating system, so it's omitted. Track wins, kills and games played. A rating system can be added later.

**D19. Region label.** Webhook title: `Game #N (EU)`. "EU" is the server's region (configurable), not the domain. No flag emoji.

**D20. Website data.** Recommended path: the plugin POSTs stats to a small Cloudflare Worker (no open port on the server). The website reads from that. Alternatives: an embedded HTTPS endpoint behind a proxy, or a JSON file. Details in `02_PROMPT_site.md`.

**D21. Resource pack.** One pack containing: the One Time Shield model (via the `item_model` component), the top-centre IP bar (custom boss-bar sprite plus font glyph), and optional GUI icons. It's forced, with a clear message if declined. `pack_format` must match the server version.

**D22. Style.** Plugin primary colour **#FF0000**. Use MiniMessage. Chat prefix: `<#FF0000><b>CartZone</b> <gray>»`. Tier colours: bronze `#CD7F32`, silver `#C0C0C0`, gold `#FFD700`. Rarity colours: Common grey, Rare blue, Epic purple, Legendary gold.

**D23. One game at a time.** One arena, one active game. Upcoming games are queued in the database and survive restarts.

**D24. Game duration.** The "Duration" shown in the webhook is the planned length (the sum of the zone phases plus deployment). It's informational, but the hard timeout in D13 applies.

**D25. Event ping.** Hosted and 5-minute-warning messages ping a role (`@Events`). Purge and results messages don't.

## Where the plan is unclear (summary)

- The cut-off sentence in the deployment paragraph (D6).
- "Purged" used for both "removed" and "selected" (D2).
- Whether VIP wind charges/elytra work in-match or only in the lobby (D17, defaulted to lobby).
- Whether LT/HT modes also differ in loot (kits differ per the plan; loot is shared by default, with an optional per-mode override).
- The website currently promises different perks than the plan says. See `05_STORE-and-ranks.md`.
