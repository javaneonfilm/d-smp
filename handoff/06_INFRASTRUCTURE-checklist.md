# Infrastructure checklist

Everything needed around the plugin and site. Tick these off in roughly this order.

## 1. Domain and DNS
- [ ] Buy **cartzone.vip** (unregistered when checked on 2026-09-30). Turn on auto-renew and registrar 2FA.
- [ ] Website: point the domain at the static host (follow the host's instructions).
- [ ] Game address: an **A record** `play.cartzone.vip` → the Minecraft server's IP. If the server isn't on port 25565, also add an **SRV record** `_minecraft._tcp.play` → target `play.cartzone.vip`, port = your port.
- [ ] Optional: the store on its own subdomain (`store.cartzone.vip`) via CraftingStore's custom-domain guide. HTTPS on a custom domain needs their Silver plan.

## 2. Minecraft server
- [ ] A host with enough resources. Rough starting point for around 100 players: 4+ fast CPU cores, 8–12 GB RAM, SSD. Use a reputable host with DDoS protection.
- [ ] **Paper** for the chosen version (must include the Happy Ghast, Java 1.21.6+). Java version as Paper requires. Accept the EULA.
- [ ] `server.properties`: `white-list=true`, `enforce-whitelist=true`, `online-mode=true`, `max-players` above the largest purge cap plus staff plus buffer, sensible `view-distance` and `simulation-distance`, `spawn-protection=0`. Keep RCON **off** unless a plugin needs it, and firewall it if on.
- [ ] Plugins: **LuckPerms**, **CraftingStore**, **CartZone**. Avoid extras.
- [ ] LuckPerms groups: `default`, `vip`, `vipplus`, `host`, `admin` and their permission nodes (see `01_PROMPT_plugin.md` 5.19).
- [ ] **Restart loop.** The plugin shuts the server down after every match, so something must start it again. Linux/macOS:
  ```bash
  #!/bin/bash
  while true; do
    java -Xms8G -Xmx8G -jar paper.jar nogui
    echo "Server stopped. Restarting in 5 seconds (Ctrl+C to cancel)..."
    sleep 5
  done
  ```
  Windows (`start.bat`):
  ```bat
  @echo off
  :loop
  java -Xms8G -Xmx8G -jar paper.jar nogui
  echo Server stopped. Restarting in 5 seconds...
  timeout /t 5
  goto loop
  ```
  Managed panels (Pterodactyl and similar) usually restart automatically. Confirm.
- [ ] **Worlds:** a static `lobby` world and an `arena` world. Arena templates live in `plugins/CartZone/templates/<name>/`. `/cart arena save <name>` snapshots the arena.
- [ ] **Backups:** daily backup of `plugins/CartZone/` (database, configs, templates) and the LuckPerms data, stored off the server.
- [ ] **Resource pack:** host `cartzone-pack.zip` at a stable HTTPS URL, and record its SHA-1.

## 3. Discord
- [ ] A community server with channels such as `#announcements`, `#events` (webhook posts here), `#rules`, `#support`.
- [ ] A role **@Events** (members opt in through *Channels & Roles*). Copy its **role ID** (Developer Mode → right-click role → Copy Role ID).
- [ ] A **webhook** in `#events`: Channel settings → Integrations → Webhooks → New Webhook → Copy URL. **This URL is a secret.** It goes only in the plugin's `secrets.yml`. Anyone with it can post as your bot.
- [ ] Optional: a VIP channel and the CraftingStore role integration.

## 4. Store
See `05_STORE-and-ranks.md`.

## 5. Stats for the website (recommended path)
- [ ] Create a Cloudflare account and a **Worker + KV namespace** (free tier).
- [ ] Set a random token as the Worker's secret. Put the same token in the plugin's `secrets.yml`.
- [ ] Give the site the Worker's public GET URL (`SITE.api`). Restrict CORS to the site's origin.

## 6. Security hygiene
- [ ] 2FA on every account: hosting, domain registrar, Cloudflare/Netlify, CraftingStore, PayPal/Stripe, Discord, GitHub, email.
- [ ] Unique strong passwords in a password manager.
- [ ] Never share the webhook URL, plugin keys, tokens or RCON passwords in chat, screenshots or git.
- [ ] Minimal staff permissions, and remove staff who leave.
- [ ] The Notion plan page is publicly editable: restrict its sharing.

## 7. Legal and community
- [ ] Rules published on the site and in Discord (`07_RULES.md`).
- [ ] Terms, privacy and refund policy on the store and site.
- [ ] Follow Mojang's EULA and Commercial Usage Guidelines: cosmetics and access perks only, nothing that affects combat.
- [ ] Age: Discord requires 13+. Payment providers require an adult account holder for payouts.

## 8. Launch day
- [ ] Full dress rehearsal: host a free game, join with 2+ accounts, run the whole flow.
- [ ] Test purchases with CraftingStore's manual payment (VIP, VIP+, Game Ticket), then a small real purchase from a second account.
- [ ] Post the first game in Discord and watch the console and the TPS.
