# PROMPT: finish, connect and launch the CartRoyale website

You are a front-end engineer. A first version of the website already exists in `site/` (this folder). Your job: connect it to the store and the plugin, fix what's out of date, and help launch it.

Read first: `00_START-HERE.md`, `05_STORE-and-ranks.md`, `08_BRAND.md`, `04_DECISIONS-and-defaults.md`, and `01_PROMPT_plugin.md` sections 5.17 (stats JSON) and 6.

## 1. What exists now

A static, dependency-free site: `site/index.html`, `site/assets/style.css`, `site/assets/app.js`, `site/assets/logo.png`. No build step.

- **Look:** warm industrial "rail yard" theme (charcoal, TNT red, amber). Headings in Barlow Condensed, body in Barlow, data in JetBrains Mono (Google Fonts). The header's bottom edge is a rail with a cart that rides as you scroll. The hero has a clickable TNT cart with embers and an explosion effect.
- **Sections:** Play (hero + status strip), How it works (ticket cards), News, Leaderboards (departure-board style: top riders + recent rounds), Store (VIP, VIP+, Game Ticket), Rules (accordions), Staff, Join.
- **Config:** the first block of `assets/app.js` (`SITE`, `SHOP`, `DATA`, `DEMO`).
  - `SITE.ip` = `play.cartroyale.com`, `SITE.discord` = `#`, `SITE.demo` = `true` (shows sample data).
  - `SHOP` holds the three products. Each has a `url` that is empty, so buttons say "Coming soon".
- **Live status:** the browser asks `api.mcsrvstat.us` for the server's online state and player count (`SITE.liveStatus`).
- Works from `file://` or any static host. To preview: `python -m http.server 5173` inside `site/`.
- Verified on desktop and phone widths. No console errors. Keyboard focus styles exist and `prefers-reduced-motion` is respected.

## 2. Tasks (in order)

1. **Store links.** Get the three CraftingStore package URLs from the owner (public links) and set each `url` in `SHOP`. Only `http(s)` URLs are accepted by the code. Rebuild nothing else.
2. **Fix the perks and Game Ticket text.** Use the "Suggested replacement perk text" in `05_STORE-and-ranks.md`. Keep the "No pay-to-win" banner true. Remove cart skins/trails from all copy and from `ART` if they no longer apply. Rename the Game Ticket description to self-serve.
3. **Real values.** Set `SITE.ip` (confirm the final address with the owner), `SITE.discord`, staff (`DATA.staff`), news (`DATA.news`), and the edition/version wording.
4. **Live data.** Replace the demo data with the plugin's stats (JSON shape in `01_PROMPT_plugin.md` 5.17).
   - Add a `SITE.api` URL. On load, `fetch` it (with a timeout, no-store, and a graceful fallback to empty states).
   - Fill `wins/kills/played/rounds` from the response and render exactly like today.
   - Recommended backend: a tiny **Cloudflare Worker** with a KV namespace. The plugin POSTs to it with a bearer token (secret stays out of the site), and the site GETs from it with CORS limited to the site's origin. Put the Worker in `worker/` and document deploy steps.
   - Set `SITE.demo = false` once real data flows.
5. **Rules page.** Extend the rules in `index.html` from `07_RULES.md` with game-specific rules once the plugin mechanics are final (purge, teaming, disconnecting, exploits).
6. **Legal and trust.** Add footer links to the store's Terms and Privacy pages and the site's own privacy note (the status check and the fonts call third-party services). Add an imprint/contact if the owner's country requires it.
7. **Polish.** Add an Open Graph image and correct absolute `og:` URLs after the domain is known. Add a favicon set. Add `robots.txt` and `sitemap.xml` if wanted.
8. **Deploy.** Use a free static host (Netlify Drop or Cloudflare Pages). Upload the **contents of `site/`**, never the whole handoff folder (it contains planning documents). Add the custom domain and follow the host's DNS steps. The game address (`play.` subdomain) is a separate record pointing at the Minecraft server (`06_INFRASTRUCTURE-checklist.md`).
9. **Resource pack hosting (optional).** The site host can serve `pack/cartroyale-pack.zip` at a stable URL for the plugin (needs its SHA-1).
10. **QA.** Test at 360, 390, 768, 1024, 1440 px. Keyboard only. `prefers-reduced-motion`. Slow network. Fonts blocked. JavaScript disabled (core content should still be readable). Run Lighthouse and fix accessibility and performance findings.

## 3. Constraints

- Keep the design language (`08_BRAND.md`). Improve it, don't replace it.
- **Do not copy mace.vip** text, layout details or art.
- No secrets in the site. Any token belongs in the plugin or the Worker.
- Keep it dependency-free and fast. If a build step is truly needed, ask the owner first.
- The "Not affiliated with Mojang Studios or Microsoft" line stays in the footer.
- Prices shown must equal the store's. Currency is euro.

## 4. Definition of done

The store buttons open the right CraftingStore package pages, the perks text matches the plugin, leaderboards and recent rounds show real data (or graceful empty states), the site is live on the owner's domain with HTTPS, and Lighthouse accessibility is 90+ on mobile.
