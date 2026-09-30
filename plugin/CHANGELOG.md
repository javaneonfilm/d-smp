# Changelog

## 1.0.0 (2026-09-30)

First build for Paper 1.21.11.

- M0: Maven project, config/messages framework (MiniMessage), SQLite on one background thread with migrations, command framework, secrets.yml.
- M1: state machine (join window, countdown, purge, team-up, boarding, deploy, playing, ending, reset), login gate, lobby protection, `/cart start|stop`.
- M2: Discord webhooks (hosted, 5-minute warning, purge, results, daily post) with 429 retry, scheduler with GUI, Host GUI, tickets (idempotent per transaction, chargebacks).
- M3: weighted purge (VIP 0.15, VIP+ and hoster immune), teams with `/team` and a GUI, kits per mode, `/kitready`.
- M4: Happy Ghast deployment, locked temporary elytra, chestplate restored on landing, no landing fall damage.
- M5: chest spot scan (cached per map), Poisson-spaced tiered chests with star holograms, loot editor, One Time Shield, block rules (no drops except rails, TNT carts break blocks, loot chests survive), cart kill credit.
- M6: custom circular zone with phases and the final orbit, spectators confined to the arena, results, kick, arena restore on boot, restart.
- M7: coins, cosmetics (kill effects, cart explosion effects, suffixes), global daily shop, locker, lobby hotbar, grappling hook, VIP wind charges and VIP+ elytra, rank tags.
- M8: resource pack (IP bar, One Time Shield model), IP bar.
- M9: website stats export (Worker or file), `/cart diag`, `/cart selftest`, 30-bot load test and fixes (see docs/TEST-LOG.md).
