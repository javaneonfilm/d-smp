// d-smp.vip Worker (Cloudflare Workers, free plan): website stats and the web store.
//
// Stats:  the plugin POSTs leaderboards, recent rounds and the player count to /stats every
//         minute; the website GETs them.
// Store:  the website asks /checkout for a PayPal payment page, the money goes straight to your
//         PayPal account, and the plugin collects the order from /orders and delivers it in-game.
//
// Bindings (setup steps in README.md next to this file):
//   STATS          KV namespace (stats)
//   DB             D1 database (store orders)
//   TOKEN          secret, same as stats-token in plugins/CartZone/secrets.yml
//   PAYPAL_CLIENT_ID  variable, from your PayPal app (developer.paypal.com > Apps & Credentials)
//   PAYPAL_SECRET     secret, the same app's secret
//   PAYPAL_WEBHOOK_ID variable, the id of the webhook you add to that app (refunds, disputes)
//   PAYPAL_MODE       optional variable: "sandbox" for test money, anything else = live
//   SITE_URL       optional variable, default https://d-smp.vip (where buyers return to)

// What the store sells. The price here is what PayPal charges; the website shows it from here
// too, so this is the only place to change a price. Amounts are in cents.
const PRODUCTS = {
  vip:     { name: "VIP (30 days)",     amount: 999 },
  vipplus: { name: "VIP+ (30 days)",    amount: 1999 },
  ticket:  { name: "Game Ticket (1 game)", amount: 499 }
};
const CURRENCY = "eur";

// Websites allowed to call this from a browser (the site itself, Netlify previews, local testing).
const ORIGINS = ["https://d-smp.vip", "https://www.d-smp.vip"];
const EXTRA_ORIGIN = /^(https:\/\/[a-z0-9-]+\.netlify\.app|http:\/\/localhost(:\d+)?|http:\/\/127\.0\.0\.1(:\d+)?)$/;

// KV allows 1,000 writes a day for free. Leaderboard changes (a game ended) are saved right away;
// a change in the player count alone is saved at most this often.
const STATUS_WRITE_MS = 110_000;
const MAX_BODY = 64 * 1024;

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const origin = req.headers.get("Origin") || "";
    const cors = {
      "Access-Control-Allow-Origin": ORIGINS.includes(origin) || EXTRA_ORIGIN.test(origin) ? origin : ORIGINS[0],
      "Vary": "Origin"
    };
    const path = url.pathname.replace(/\/+$/, "") || "/";

    if (req.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: { ...cors, "Access-Control-Allow-Methods": "GET, POST", "Access-Control-Allow-Headers": "Content-Type", "Access-Control-Max-Age": "86400" }
      });
    }

    try {
      if (path === "/" || path === "/stats") return await stats(req, env, cors);
      if (path === "/products" && req.method === "GET") return products(cors);
      if (path === "/checkout" && req.method === "POST") return await checkout(req, env, cors);
      if (path === "/paypal/return" && req.method === "GET") return await paypalReturn(req, env);
      if (path === "/paypal/webhook" && req.method === "POST") return await paypalWebhook(req, env);
      if (path === "/orders" && req.method === "GET") return await pendingOrders(req, env);
      if (path === "/orders/ack" && req.method === "POST") return await ackOrders(req, env);
    } catch (e) {
      return json({ error: "Something went wrong on our side. Try again in a minute." }, 500, cors);
    }
    return text("Not found", 404, cors);
  }
};

// ------------------------------------------------------------------ stats

async function stats(req, env, cors) {
  if (req.method === "GET" || req.method === "HEAD") {
    if (!env.STATS) return text("KV binding STATS is missing", 500, cors);
    const body = (await env.STATS.get("stats", { cacheTtl: 30 })) || "{}";
    return new Response(req.method === "HEAD" ? null : body, {
      headers: { ...cors, "Content-Type": "application/json; charset=utf-8", "Cache-Control": "public, max-age=30" }
    });
  }
  if (req.method !== "POST") return text("Method not allowed", 405, cors);
  if (!env.STATS || !env.TOKEN) return text("STATS or TOKEN is not set up", 500, cors);
  if (!authorized(req, env)) return text("Unauthorized", 401, cors);
  const raw = await req.text();
  if (raw.length > MAX_BODY) return text("Too large", 413, cors);
  let data;
  try {
    data = JSON.parse(raw);
  } catch (e) {
    return text("Bad JSON", 400, cors);
  }
  for (const k of ["wins", "kills", "played", "rounds"]) {
    if (!Array.isArray(data[k])) return text("Missing " + k, 400, cors);
  }
  const prev = await env.STATS.getWithMetadata("stats");
  if (prev.value) {
    let same = false, sameStatus = false;
    try {
      const p = JSON.parse(prev.value);
      same = JSON.stringify([p.wins, p.kills, p.played, p.rounds]) === JSON.stringify([data.wins, data.kills, data.played, data.rounds]);
      sameStatus = JSON.stringify(p.status) === JSON.stringify(data.status);
    } catch (e) { /* stored value unreadable: overwrite it */ }
    const age = Date.now() - ((prev.metadata && prev.metadata.at) || 0);
    if (same && (sameStatus || age < STATUS_WRITE_MS)) return text("Unchanged", 202, cors);
  }
  await env.STATS.put("stats", raw, { metadata: { at: Date.now() } });
  return text("Saved", 200, cors);
}

// ------------------------------------------------------------------ store: website side

function products(cors) {
  const out = {};
  for (const [id, p] of Object.entries(PRODUCTS)) out[id] = { name: p.name, amount: p.amount, currency: CURRENCY };
  return json(out, 200, { ...cors, "Cache-Control": "public, max-age=300" });
}

// The website sends { product, name, waiver, method: "paypal" | "card" }; the buyer gets a PayPal
// page to pay on.
async function checkout(req, env, cors) {
  if (!env.PAYPAL_CLIENT_ID || !env.PAYPAL_SECRET || !env.DB) return json({ error: "The store isn't open yet." }, 503, cors);
  let body;
  try {
    body = JSON.parse(await req.text());
  } catch (e) {
    return json({ error: "Bad request." }, 400, cors);
  }
  const product = PRODUCTS[body.product];
  if (!product) return json({ error: "That item doesn't exist." }, 400, cors);
  if (body.waiver !== true) return json({ error: "Please tick the box first." }, 400, cors);
  const name = String(body.name || "").trim();
  if (!/^[A-Za-z0-9_]{3,16}$/.test(name)) return json({ error: "That isn't a Minecraft username (3 to 16 letters, numbers or _)." }, 400, cors);

  const profile = await mojang(name);
  if (profile === null) return json({ error: `There's no Minecraft account called ${name}. Check the spelling.` }, 400, cors);
  if (profile === undefined) return json({ error: "Couldn't check that username right now. Try again in a minute." }, 502, cors);

  const self = new URL(req.url).origin;
  const price = { currency_code: CURRENCY.toUpperCase(), value: money(product.amount) };
  const r = await paypal(env, "/v2/checkout/orders", {
    intent: "CAPTURE",
    purchase_units: [{
      description: `${product.name} for ${profile.name} on d-smp.vip`.slice(0, 127),
      custom_id: `${body.product}|${profile.uuid}|${profile.name}`,
      amount: { ...price, breakdown: { item_total: price } },
      items: [{ name: product.name, quantity: "1", category: "DIGITAL_GOODS", unit_amount: price }]
    }],
    payment_source: {
      paypal: {
        experience_context: {
          brand_name: "d-smp.vip",
          user_action: "PAY_NOW",
          shipping_preference: "NO_SHIPPING",
          // "card" opens PayPal's card form right away (no PayPal account needed); the money
          // still goes to your PayPal account.
          landing_page: body.method === "card" ? "GUEST_CHECKOUT" : "LOGIN",
          return_url: self + "/paypal/return",
          cancel_url: site(env) + "/#store"
        }
      }
    }
  });
  const pay = r.ok && (r.data.links || []).find(l => l.rel === "payer-action" || l.rel === "approve");
  if (!pay) return json({ error: "The payment page couldn't be opened. Try again in a minute." }, 502, cors);
  // Remember who the waiver was accepted for (EU right of withdrawal ends once it's delivered).
  await schema(env);
  await env.DB.prepare("INSERT OR IGNORE INTO checkouts (id, product, uuid, name, waiver, created) VALUES (?1, ?2, ?3, ?4, ?5, ?6)")
    .bind(r.data.id, body.product, profile.uuid, profile.name, new Date().toISOString(), Date.now()).run();
  return json({ url: pay.href }, 200, cors);
}

// PayPal sends the buyer back here after they approved: take the money, then back to the site.
async function paypalReturn(req, env) {
  const id = new URL(req.url).searchParams.get("token") || "";
  if (!/^[A-Z0-9]{5,40}$/.test(id)) return Response.redirect(site(env) + "/?paid=0#store", 302);
  const ok = await capture(env, id);
  return Response.redirect(site(env) + (ok ? "/?paid=1#store" : "/?paid=0#store"), 302);
}

// Captures an approved order and saves it for the plugin. True when the money is ours.
async function capture(env, id) {
  let r = await paypal(env, `/v2/checkout/orders/${id}/capture`, {}, id);
  if (!r.ok && JSON.stringify(r.data).includes("ORDER_ALREADY_CAPTURED")) r = await paypal(env, `/v2/checkout/orders/${id}`, null);
  if (!r.ok) return false;
  const unit = (r.data.purchase_units || [])[0] || {};
  const cap = ((unit.payments || {}).captures || [])[0];
  if (!cap) return false;
  if (cap.status === "COMPLETED") return await saveOrder(env, id, unit.custom_id || cap.custom_id, cap);
  // PENDING (PayPal is checking the payment): the PAYMENT.CAPTURE.COMPLETED webhook saves it later.
  return cap.status === "PENDING";
}

async function saveOrder(env, orderId, customId, cap) {
  const [product, uuid, name] = String(customId || "").split("|");
  if (!PRODUCTS[product] || !uuid) return false;
  await schema(env);
  await env.DB.prepare(
    "INSERT OR IGNORE INTO orders (id, kind, ref, product, uuid, name, amount, currency, payment_ref, created) VALUES (?1, 'buy', ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)"
  ).bind(orderId, product, uuid, name || "", cents(cap.amount && cap.amount.value), CURRENCY, cap.id, Date.now()).run();
  return true;
}

// Mojang profile: { uuid, name } (dashed uuid, correct capitals), null if no such account,
// undefined if Mojang couldn't be reached.
async function mojang(name) {
  const urls = [
    "https://api.mojang.com/users/profiles/minecraft/" + encodeURIComponent(name),
    "https://api.minecraftservices.com/minecraft/profile/lookup/name/" + encodeURIComponent(name)
  ];
  let reached = false;
  for (const u of urls) {
    try {
      const r = await fetch(u, { headers: { "Accept": "application/json" } });
      if (r.status === 204 || r.status === 404) { reached = true; continue; }
      if (!r.ok) continue;
      const p = await r.json();
      if (p && /^[0-9a-f]{32}$/i.test(p.id || "")) {
        const id = p.id.toLowerCase();
        return { uuid: `${id.slice(0, 8)}-${id.slice(8, 12)}-${id.slice(12, 16)}-${id.slice(16, 20)}-${id.slice(20)}`, name: p.name || name };
      }
    } catch (e) { /* try the next one */ }
  }
  return reached ? null : undefined;
}

// ------------------------------------------------------------------ store: PayPal webhooks

async function paypalWebhook(req, env) {
  if (!env.PAYPAL_WEBHOOK_ID || !env.DB) return text("Store not set up", 500, {});
  const raw = await req.text();
  let ev;
  try {
    ev = JSON.parse(raw);
  } catch (e) {
    return text("Bad JSON", 400, {});
  }
  const v = await paypal(env, "/v1/notifications/verify-webhook-signature", {
    auth_algo: req.headers.get("PAYPAL-AUTH-ALGO"),
    cert_url: req.headers.get("PAYPAL-CERT-URL"),
    transmission_id: req.headers.get("PAYPAL-TRANSMISSION-ID"),
    transmission_sig: req.headers.get("PAYPAL-TRANSMISSION-SIG"),
    transmission_time: req.headers.get("PAYPAL-TRANSMISSION-TIME"),
    webhook_id: env.PAYPAL_WEBHOOK_ID,
    webhook_event: ev
  });
  if (!v.ok || v.data.verification_status !== "SUCCESS") return text("Bad signature", 400, {});
  const o = ev.resource || {};
  await schema(env);

  switch (ev.event_type) {
    case "CHECKOUT.ORDER.APPROVED":
      // The buyer approved but never came back to the site (closed the tab): capture it here.
      await capture(env, o.id);
      break;
    case "PAYMENT.CAPTURE.COMPLETED": {
      // A capture that was PENDING went through.
      const orderId = o.supplementary_data && o.supplementary_data.related_ids && o.supplementary_data.related_ids.order_id;
      if (orderId) await saveOrder(env, orderId, o.custom_id, o);
      break;
    }
    case "PAYMENT.CAPTURE.REFUNDED": {
      // The resource is the refund; "up" points at the capture. Only a full refund takes it back.
      const up = (o.links || []).find(l => l.rel === "up");
      const capId = up && up.href.split("/").pop();
      if (capId) await revoke(env, capId, cents(o.amount && o.amount.value));
      break;
    }
    case "PAYMENT.CAPTURE.REVERSED":
      await revoke(env, o.id, Infinity);
      break;
    case "CUSTOMER.DISPUTE.CREATED":
      for (const t of o.disputed_transactions || []) if (t.seller_transaction_id) await revoke(env, t.seller_transaction_id, Infinity);
      break;
  }
  return text("OK", 200, {});
}

async function revoke(env, captureId, refunded) {
  const buy = await env.DB.prepare("SELECT * FROM orders WHERE kind = 'buy' AND payment_ref = ?1").bind(captureId).first();
  if (!buy || refunded < buy.amount) return;
  await env.DB.prepare(
    "INSERT OR IGNORE INTO orders (id, kind, ref, product, uuid, name, amount, currency, payment_ref, created) VALUES (?1, 'revoke', ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)"
  ).bind("revoke:" + buy.id, buy.id, buy.product, buy.uuid, buy.name, buy.amount, buy.currency, buy.payment_ref, Date.now()).run();
}

// PayPal REST call with a cached access token. body null = GET.
let ppToken = null, ppTokenUntil = 0;
async function paypal(env, path, body, requestId) {
  const base = env.PAYPAL_API || (env.PAYPAL_MODE === "sandbox" ? "https://api-m.sandbox.paypal.com" : "https://api-m.paypal.com");
  if (!ppToken || Date.now() > ppTokenUntil) {
    const t = await fetch(base + "/v1/oauth2/token", {
      method: "POST",
      headers: { "Authorization": "Basic " + btoa(env.PAYPAL_CLIENT_ID + ":" + env.PAYPAL_SECRET), "Content-Type": "application/x-www-form-urlencoded" },
      body: "grant_type=client_credentials"
    });
    if (!t.ok) return { ok: false, data: { error: "PayPal login failed (check PAYPAL_CLIENT_ID / PAYPAL_SECRET / PAYPAL_MODE)" } };
    const d = await t.json();
    ppToken = d.access_token;
    ppTokenUntil = Date.now() + Math.max(60, (d.expires_in || 3000) - 120) * 1000;
  }
  const headers = { "Authorization": "Bearer " + ppToken, "Content-Type": "application/json" };
  if (requestId) headers["PayPal-Request-Id"] = "cap-" + requestId;
  const r = await fetch(base + path, body === null ? { headers } : { method: "POST", headers, body: JSON.stringify(body) });
  let data = {};
  try { data = await r.json(); } catch (e) { /* empty body */ }
  return { ok: r.ok, data };
}

const money = c => (c / 100).toFixed(2);
const cents = v => Math.round(parseFloat(v || "0") * 100);
const site = env => (env.SITE_URL || "https://d-smp.vip").replace(/\/+$/, "");

// ------------------------------------------------------------------ store: plugin side

async function pendingOrders(req, env) {
  if (!authorized(req, env)) return text("Unauthorized", 401, {});
  if (!env.DB) return json({ orders: [] }, 200, {});
  await schema(env);
  const { results } = await env.DB.prepare(
    "SELECT id, kind, ref, product, uuid, name, amount, currency, created FROM orders WHERE delivered IS NULL ORDER BY created LIMIT 50"
  ).all();
  return json({ orders: results || [] }, 200, { "Cache-Control": "no-store" });
}

async function ackOrders(req, env) {
  if (!authorized(req, env)) return text("Unauthorized", 401, {});
  if (!env.DB) return text("No DB", 500, {});
  let ids;
  try {
    ids = JSON.parse(await req.text()).ids;
  } catch (e) {
    return text("Bad JSON", 400, {});
  }
  if (!Array.isArray(ids)) return text("Missing ids", 400, {});
  const now = Date.now();
  const stmts = ids.slice(0, 50).map(id => env.DB.prepare("UPDATE orders SET delivered = ?1 WHERE id = ?2 AND delivered IS NULL").bind(now, String(id)));
  if (stmts.length) await env.DB.batch(stmts);
  return text("OK", 200, {});
}

let schemaReady = false;
async function schema(env) {
  if (schemaReady) return;
  await env.DB.batch([
    env.DB.prepare("CREATE TABLE IF NOT EXISTS orders (id TEXT PRIMARY KEY, kind TEXT NOT NULL, ref TEXT NOT NULL, product TEXT NOT NULL, uuid TEXT NOT NULL, name TEXT NOT NULL, amount INTEGER, currency TEXT, payment_ref TEXT, created INTEGER NOT NULL, delivered INTEGER)"),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS checkouts (id TEXT PRIMARY KEY, product TEXT, uuid TEXT, name TEXT, waiver TEXT, created INTEGER)")
  ]);
  schemaReady = true;
}

// ------------------------------------------------------------------ helpers

function authorized(req, env) {
  return !!env.TOKEN && sameSecret(req.headers.get("Authorization") || "", "Bearer " + env.TOKEN);
}

function text(msg, status, headers) {
  return new Response(msg, { status, headers: { ...headers, "Content-Type": "text/plain" } });
}

function json(obj, status, headers) {
  return new Response(JSON.stringify(obj), { status, headers: { ...headers, "Content-Type": "application/json; charset=utf-8" } });
}

// Compares without leaking how many characters matched.
function sameSecret(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
