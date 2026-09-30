# CartRoyale: hand-off folder

Everything needed to build the **CartRoyale game server plugin**, finish the **website**, and launch. Give this whole folder to Claude (Claude Code) on the main PC.

Created 2026-09-30.

## What's in here

| File / folder | What it is |
|---|---|
| `00_START-HERE.md` | This page: how to use the folder, project status, what to prepare |
| `01_PROMPT_plugin.md` | **The master prompt for the plugin.** Every system in the plan, with acceptance tests and milestones |
| `02_PROMPT_site.md` | The prompt for finishing and launching the website |
| `03_PLAN_original-from-notion.md` | Your Notion plan, word for word, plus a description of its three images |
| `04_DECISIONS-and-defaults.md` | Every gap in the plan and the default chosen (D1–D25), plus 7 questions Claude will ask first |
| `05_STORE-and-ranks.md` | Products, prices, CraftingStore setup, the exact store commands, and the perk text that must be fixed on the site |
| `06_INFRASTRUCTURE-checklist.md` | Domain, server, Discord, Cloudflare, security and launch-day checklists |
| `07_RULES.md` | The rules text (community + battle royale) |
| `08_BRAND.md` | Name, colours, fonts, motifs, what not to copy |
| `../site/` | The website (`index.html` + `assets/`), next to this folder |
| `reference/` | The logo and screenshots of the three example images from the plan |

## How to use it (on the main PC)

1. The project lives in `C:\Users\lucas\Desktop\MC Developing\cartroyale\`: this folder is `handoff\`, the website is `site\`.
2. **Plugin.** Open Claude Code in `cartroyale\` (the plugin goes in `cartroyale\plugin\`). Paste:

   > Read `handoff/00_START-HERE.md`, then `handoff/01_PROMPT_plugin.md` and every file it lists. Follow the prompt exactly. Start by sending me the seven questions from `handoff/04_DECISIONS-and-defaults.md` in ONE message, then build milestone by milestone, testing on a real local Paper server after each one. Don't put any secrets in files.

3. Answer the questions. "Use the defaults" is fine.
4. **Website.** In a second session (or after the plugin is done), paste:

   > Read `handoff/00_START-HERE.md` and `handoff/02_PROMPT_site.md`, then finish the website in `site/`. Ask me for the three CraftingStore package links first.

5. Work through `06_INFRASTRUCTURE-checklist.md` alongside (server, Discord, DNS, store).

## What's already done (as of 2026-09-30)

- Done: Website v1: a dark "rail yard" design with a clickable TNT-cart hero, leaderboards, store, rules, staff and join sections. Tested on desktop and phone widths.
- Done: Products and prices decided: **VIP €9.99 / 30 days, VIP+ €19.99 / 30 days, Game Ticket €4.99 per game**.
- Done: Store platform chosen: **CraftingStore** (Tebex can't be used). Its docs were read and the delivery commands are written down.
- Done: Rules written.
- Done: The game plan turned into detailed requirements.

## Not done yet

- To do: The plugin (the big job), the resource pack and the IP bar.
- To do: The domain. The project uses **cartroyale.com** (renamed from cartmc.eu on 2026-09-30). Checked the same day: `cartroyale.com` is **already registered** (since 2012, GoDaddy, parked on Afternic nameservers, so it is likely listed for sale). It has to be bought from its owner, or swap to a free name. Unregistered on 2026-09-30: `cartroyale.net`, `playcartroyale.com`, `cartroyalemc.com`.
- To do: The CraftingStore account, payment provider connection and three packages. The site's buy buttons say "Coming soon" until their links are pasted in.
- To do: The game server host, LuckPerms, the Discord server and webhook, and the Cloudflare Worker for stats.
- To do: Real staff, news and leaderboard data on the site (it shows sample data).

## Fix these before launch

- Done 2026-09-30: the website's perk text now matches the plan (no cart skins, no trails, self-serve Game Ticket).
- Your plan's **cut-off sentence** about the elytra was completed by assumption (D6).
- Check the VIP/VIP+ **lobby-only** assumption (D17). If they should work in matches, that is pay-to-win, which the site promises never to sell.

## Have these ready on the main PC

- This folder (and the zip).
- A Minecraft server to test on, or Claude will use a local dev server.
- The CraftingStore package links (once the store exists).
- The Discord webhook URL and the @Events role ID. **Give them to the plugin only through its `secrets.yml`, never in chat.**
- The arena map(s), and the Minecraft/Paper version you'll run.

## Warnings

- **Your Notion plan page is publicly editable** ("Anyone with a Notion account can edit this page"). Restrict its sharing. This folder has its own copy.
- **Never upload this whole folder to a website host.** Only the contents of `site/` go online. This folder contains planning documents.
- **Keep secrets out of everything:** webhook URLs, plugin keys, tokens, passwords, RCON.
- `..\site\` is the only working copy of the website (the old `cartmc-site` copy was identical and is retired).
- Payment accounts (PayPal, Stripe, Mollie) need an adult account holder.
