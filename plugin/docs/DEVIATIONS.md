# Deviations from the plan

Where the build differs from `handoff/01_PROMPT_plugin.md` and `04_DECISIONS-and-defaults.md`, and why.

| Area | Plan | What was built | Why |
|---|---|---|---|
| Build tool | Gradle (Kotlin DSL) | Maven (`pom.xml`), offline build with the Maven in `MC Developing/maven-install` | Every other plugin on this PC builds with that Maven and its cached Paper 1.21.11 API. No new toolchain needed. |
| Package | `eu.cartmc.royale` | `com.cartroyale` | Rename to cartroyale.com. |
| LuckPerms | API as a soft dependency | No LuckPerms API at all. Ranks are read from permission nodes; the temporary `host` group is granted by running LuckPerms' own console command (`lp user <uuid> parent addtemp host 6h accumulate`). | Linking against a soft-depend API is what crashed CorruptFFA on a server without LuckPerms. This way the jar starts with or without LuckPerms. |
| Happy Ghast movement | Move with velocity, no AI, no gravity | Teleported along the route every tick (`deploy.move-mode: TELEPORT`) | Tested on 1.21.11: a no-AI Happy Ghast ignores velocity. The plugin switches to teleport mode on its own if velocity is chosen and the ghasts don't move. |
| Ghast harness | "Equip any harness required" | No harness by default (`deploy.harness: false`) | Riders mount through the API without one, and without a harness no rider can steer the ghast. |
| Spectator player picker | A hotbar item | `/spectate` opens the picker (heads of alive players) | Players in spectator mode can't use hotbar items. Vanilla spectator left-click-to-follow also works. |
| Deaths in a match | Vanilla death, then spectator | A lethal hit is intercepted: items drop on the spot (armor still vanishes), kill credited, player switches to spectator without respawning. A player holding a Totem of Undying still gets a vanilla death so the totem works. | A vanilla respawn resends every chunk in view. In the 30-bot test a burst of 16 deaths cost a 614 ms tick; after the change the same burst peaked at 36 ms. It is also smoother for players (no death screen). |
| One Time Shield blocking | Vanilla blocking with a stacked shield | The shield keeps vanilla's `blocks_attacks` (so it raises like a shield), and the plugin cancels the whole hit and removes one shield | Vanilla blocking only reduces some damage types and only from the front. The plan says "any source, fully blocked". |
| One Time Shield direction | - | Blocks hits from any direction while raised | "From any source" in the plan. Change `blockable()` in `OneTimeShield.java` to add a facing check if wanted. |
| Rails in explosions | Rails drop | Rails drop, including rails that lose the block under them | Vanilla TNT carts never blast rails directly; the rail pops when its support goes, and with block drops off it would vanish. |
| Game numbers | `#1, #2, ...` | Database id; test games on the dev server used some numbers | A fresh database on the real server starts at #1. |
| Webhook emoji | Flag and icons in titles | Plain text, no unicode emoji | Owner's standing rule. Custom server emoji can be set per team size for the daily post (`webhooks.daily-post.emoji`). |
| Daily post | Not in the plan | Added: a "Today's games" list at `webhooks.daily-post.time` | From the owner's example image (Game 1: 16:00 Duos...). |
| Solo boarding | Immediately after the purge | 5 s pause (`deploy.solo-board-delay-seconds`) | Lets the route chunks finish loading off the main thread. |
| Resource pack IP bar | Custom boss-bar sprite plus glyph | Pink boss bar sprites made transparent; the title draws a red bar glyph, the minecart icon and the IP, centred with negative-space glyphs | Exact layout control. Only the plugin's IP bar is pink. |
| Test IP for webhooks | https only | `http://127.0.0.1:` and `http://localhost:` also accepted | For the local test receiver (`tools/hookcatcher.js`). Real Discord URLs are https. |
