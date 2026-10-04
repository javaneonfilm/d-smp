// Runs worker.js locally for testing (Node 22+), with in-memory KV, a SQLite file as D1 and,
// with MOCK_PAYPAL=1, a fake PayPal (approving a payment just sends you straight back).
//   TOKEN=yourtoken MOCK_PAYPAL=1 node worker/dev-server.mjs   -> http://127.0.0.1:8787
// Point stats.export.url at http://127.0.0.1:8787/stats on a test server, and SITE.api at it too.
import http from "node:http";
import { DatabaseSync } from "node:sqlite";
import worker from "./worker.js";

const port = Number(process.env.PORT || 8787);
const self = "http://127.0.0.1:" + port;

// ---- KV
const store = new Map();
let writes = 0;
const STATS = {
  async get(k) { return store.has(k) ? store.get(k).value : null; },
  async getWithMetadata(k) { return store.has(k) ? store.get(k) : { value: null, metadata: null }; },
  async put(k, value, opts = {}) { store.set(k, { value, metadata: opts.metadata || null }); writes++; }
};

// ---- D1 (the parts worker.js uses)
const sqlite = new DatabaseSync(process.env.DB_FILE || ":memory:");
function stmt(sql, args = []) {
  return {
    bind: (...a) => stmt(sql, a),
    async run() { sqlite.prepare(sql).run(...args); return { success: true }; },
    async first() { return sqlite.prepare(sql).get(...args) || null; },
    async all() { return { results: sqlite.prepare(sql).all(...args) }; }
  };
}
const DB = { prepare: sql => stmt(sql), async batch(list) { for (const s of list) await s.run(); return []; } };

const env = {
  STATS, DB,
  TOKEN: process.env.TOKEN || "dev-token",
  SITE_URL: process.env.SITE_URL || "http://localhost:5173",
  ...(process.env.MOCK_PAYPAL ? { PAYPAL_CLIENT_ID: "mock", PAYPAL_SECRET: "mock", PAYPAL_WEBHOOK_ID: "mock", PAYPAL_API: self + "/__paypal" } : {}),
  ...(process.env.PAYPAL_CLIENT_ID ? { PAYPAL_CLIENT_ID: process.env.PAYPAL_CLIENT_ID, PAYPAL_SECRET: process.env.PAYPAL_SECRET, PAYPAL_WEBHOOK_ID: process.env.PAYPAL_WEBHOOK_ID, PAYPAL_MODE: "sandbox" } : {})
};

// ---- fake PayPal
const orders = new Map();
let seq = Date.now() % 100000000;
function fakePaypal(path, body) {
  if (path === "/v1/oauth2/token") return { access_token: "mock-token", expires_in: 3600 };
  if (path === "/v1/notifications/verify-webhook-signature") return { verification_status: "SUCCESS" };
  if (path === "/v2/checkout/orders") {
    const id = "MOCK" + (seq++);
    orders.set(id, body.purchase_units[0]);
    return { id, status: "PAYER_ACTION_REQUIRED", links: [{ rel: "payer-action", href: self + "/__approve?token=" + id }] };
  }
  const m = path.match(/^\/v2\/checkout\/orders\/([A-Z0-9]+)(\/capture)?$/);
  if (m && orders.has(m[1])) {
    const u = orders.get(m[1]);
    return { id: m[1], status: "COMPLETED", purchase_units: [{ custom_id: u.custom_id, payments: { captures: [{ id: "CAP" + m[1], status: "COMPLETED", amount: u.amount, custom_id: u.custom_id }] } }] };
  }
  return null;
}

http.createServer(async (req, res) => {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const body = chunks.length ? Buffer.concat(chunks) : undefined;
  if (req.url.startsWith("/__paypal/")) {
    let parsed = null;
    try { parsed = body ? JSON.parse(body.toString() || "{}") : null; } catch (e) { /* the token request is a form */ }
    const out = fakePaypal(req.url.slice("/__paypal".length), parsed);
    res.writeHead(out ? 200 : 404, { "Content-Type": "application/json" });
    return res.end(JSON.stringify(out || { name: "RESOURCE_NOT_FOUND" }));
  }
  if (req.url.startsWith("/__approve")) {
    res.writeHead(302, { Location: "/paypal/return" + req.url.slice("/__approve".length) });
    return res.end();
  }
  const r = await worker.fetch(new Request(self + req.url, {
    method: req.method, headers: req.headers, body: req.method === "GET" || req.method === "HEAD" ? undefined : body
  }), env);
  res.writeHead(r.status, Object.fromEntries(r.headers));
  res.end(Buffer.from(await r.arrayBuffer()));
  console.log(new Date().toISOString().slice(11, 19), req.method, req.url, r.status, "kv writes:", writes);
}).listen(port, "127.0.0.1", () => console.log("worker on " + self + (process.env.MOCK_PAYPAL ? " (fake PayPal)" : "")));
