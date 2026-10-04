# The d-smp.vip Worker: website stats and your own store

One small Cloudflare Worker (free) does two jobs:

- **Stats**: the plugin sends the leaderboards, the last 8 rounds and the player count every
  minute, and the website shows them.
- **Store**: players buy VIP, VIP+ and Game Tickets on the website and pay with **PayPal or any
  debit/credit card**. The money goes **straight into your PayPal account** (no CraftingStore, no
  middleman). The plugin picks the order up and delivers it in-game within a minute, even if
  the buyer is offline. A full refund or a chargeback takes it back automatically.

Setup is once, all in the browser. Do the parts in order.

---

## Part 1: the Worker (10 minutes)

1. Make a free account at https://dash.cloudflare.com (no domain or card needed).
2. **Workers & Pages** > **Create** > **Start with Hello World**. Name it `dsmp-stats`, click **Deploy**.
3. **Edit code**, delete everything, paste all of `worker.js` from this folder, click **Deploy**.
4. **Storage & Databases** > **KV** > **Create**, name it `dsmp-stats`.
5. **Storage & Databases** > **D1 SQL Database** > **Create**, name it `dsmp-store`.
6. Open the `dsmp-stats` Worker > **Settings** > **Bindings** > **Add binding**:
   - **KV namespace**: variable name `STATS`, namespace `dsmp-stats`.
   - **D1 database**: variable name `DB`, database `dsmp-store`.
7. Worker > **Settings** > **Variables and Secrets** > **Add**, type **Secret**, name `TOKEN`.
   The value is `stats-token` from `plugins/CartZone/secrets.yml` on your server (the plugin made
   it by itself; copy it without the quotes).

The Worker's address is on its page, like `https://dsmp-stats.yourname.workers.dev`.

- **Plugin**: `plugins/CartZone/config.yml`, set
  `stats.export.url: 'https://dsmp-stats.yourname.workers.dev/stats'`, then `/cart reload`.
- **Website**: `site/assets/app.js`, set `api: "https://dsmp-stats.yourname.workers.dev/stats"`,
  then upload the site again.

`/cart diag` should show `Website stats AUTO (WORKER), last: HTTP 200` and `Web store on`.
The leaderboards on the website are real now. The store buttons still say "Coming soon" until
Part 2 is done.

---

## Part 2: PayPal (15 minutes)

You need a **PayPal Business account** (free). A personal one can be upgraded in PayPal under
Settings > "Upgrade to a business account". PayPal only allows adults (18+) to hold one.

1. Go to https://developer.paypal.com and log in with that PayPal account.
2. **Apps & Credentials**, switch the toggle at the top to **Live**, then **Create App**
   (type Merchant, any name, e.g. `d-smp.vip store`).
3. Copy the app's **Client ID** and **Secret**.
4. In the same app, **Webhooks** > **Add Webhook**:
   - URL: `https://dsmp-stats.yourname.workers.dev/paypal/webhook`
   - Events: **Checkout order approved**, **Payment capture completed**, **Payment capture
     refunded**, **Payment capture reversed**, **Customer dispute created**.
   - Save, then copy the **Webhook ID** it shows.
5. Back in Cloudflare, Worker > **Settings** > **Variables and Secrets**, add:
   - `PAYPAL_CLIENT_ID` (type Text): the Client ID
   - `PAYPAL_SECRET` (type **Secret**): the Secret
   - `PAYPAL_WEBHOOK_ID` (type Text): the Webhook ID
6. For card payments without a PayPal account: on paypal.com, **Settings** > **Website
   payments** > **Website preferences**, make sure **PayPal account optional** is **On**
   (it usually is for business accounts).

Reload the website: the buttons say "Buy VIP" etc. and show the prices from the Worker.

### Test with fake money first (recommended)

On developer.paypal.com switch **Apps & Credentials** to **Sandbox**, use that app's Client ID
and Secret instead, add a webhook there too, and add the variable `PAYPAL_MODE` = `sandbox` to
the Worker. **Testing tools** > **Sandbox accounts** has a test buyer you can log in with on the
PayPal page. Buy something, check it arrives in-game, then refund it in the sandbox and check it
gets taken back. When it works, put the **Live** values in and delete `PAYPAL_MODE`.

---

## Changing things

- **Prices and names**: `PRODUCTS` at the top of `worker.js` (amounts in cents). The website
  reads them from there. Paste the file into the Worker again and Deploy.
- **What a purchase gives in-game**: `webstore.products` in the plugin's `config.yml` (console
  commands with `{uuid}`, `{name}`, `{order}`). VIP and VIP+ need the LuckPerms groups `vip` and
  `vipplus`.
- **Website address**: if the site isn't on d-smp.vip, add it to `ORIGINS` in `worker.js` and
  set a `SITE_URL` variable on the Worker (where buyers land after paying).

## Good to know

- **PayPal fees**: PayPal's normal fee is taken from each payment. There's no monthly cost:
  Cloudflare's free plan is plenty (stats stay under 1,000 writes a day on purpose).
- **You are the seller now.** CraftingStore never took that off you either, but with your own
  store it's clearer: in Germany a website that sells needs an **Impressum**, and income from
  sales counts for **tax** (as a small business you can usually use the Kleinunternehmer rule,
  but register it and check with a tax advisor or the Finanzamt).
- **Right of withdrawal (EU)**: the buyer has to tick that delivery starts right away and that they
  lose the right to cancel once it's delivered. The site does this, and the Worker records it.
- **Refunds**: refund in PayPal (Activity > the payment > Refund). A full refund takes the
  rank or ticket back in-game automatically. So does a chargeback.
- **Delivery problems**: `/cart diag` shows the last store check. Orders wait in the Worker until
  the plugin confirms them, so nothing is lost while the server is offline.
- **Never** put the token or the PayPal Secret in the website.
- Local test (Node 22+): `TOKEN=... MOCK_PAYPAL=1 node worker/dev-server.mjs` runs everything on
  `http://127.0.0.1:8787` with a fake PayPal.
