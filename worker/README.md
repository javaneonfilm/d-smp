# Website stats Worker

The plugin sends the leaderboards, the last 8 rounds and the player count to this Worker every
minute. The website reads them from it. It runs on Cloudflare's free plan, and you set it up once
(about 10 minutes, all in the browser).

## 1. Create the Worker

1. Make a free account at https://dash.cloudflare.com (no domain or card needed).
2. **Compute (Workers)** > **Workers & Pages** > **Create** > **Start with Hello World**.
   Name it `dsmp-stats` and click **Deploy**.
3. Click **Edit code**, delete everything in the editor, paste all of `worker.js` from this
   folder, and click **Deploy**.

## 2. Give it storage (KV)

1. **Storage & Databases** > **KV** > **Create** (namespace). Name it `dsmp-stats`.
2. Open the `dsmp-stats` Worker > **Settings** > **Bindings** > **Add binding** > **KV namespace**.
   Variable name `STATS` (exactly that), namespace `dsmp-stats`. Save/Deploy.

## 3. Give it the token

1. On the server, open `plugins/CartZone/secrets.yml`. The plugin already put a long random
   `stats-token` there. Copy the value (without the quotes).
2. Worker > **Settings** > **Variables and Secrets** > **Add** > type **Secret**, name `TOKEN`
   (exactly that), value = the token. Save/Deploy.

## 4. Link the plugin and the website

Your Worker's address is shown on its page, like `https://dsmp-stats.yourname.workers.dev`.
Add `/stats` to the end:

- **Plugin**: in `plugins/CartZone/config.yml` set
  `stats.export.url: 'https://dsmp-stats.yourname.workers.dev/stats'`, then `/cart reload`.
  `/cart diag` should say `Website stats AUTO (WORKER), last: HTTP 200` within a minute.
- **Website**: in `site/assets/app.js` set
  `api: "https://dsmp-stats.yourname.workers.dev/stats"`, then upload the site again.
  The sample names disappear and real players show up.

Opening the URL in a browser shows the raw data the website reads.

## Notes

- Free plan limits: 1,000 KV writes and 100,000 reads a day. The Worker only saves when a game
  ended or, for the player count alone, at most every ~2 minutes, so it stays under the limit.
- Only `d-smp.vip`, `www.d-smp.vip`, `*.netlify.app` and localhost may read it from a browser.
  Edit `ORIGINS` at the top of `worker.js` if the site moves.
- Never put the token in the website. It belongs only in `secrets.yml` and the Worker.
- Local test: `TOKEN=... node worker/dev-server.mjs` runs it on `http://127.0.0.1:8787/stats`.
