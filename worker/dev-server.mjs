// Runs worker.js locally for testing (Node 18+), with an in-memory KV.
//   TOKEN=yourtoken node worker/dev-server.mjs        -> http://127.0.0.1:8787/stats
// Point stats.export.url at it on a test server, and SITE.api in the site at it.
import http from "node:http";
import worker from "./worker.js";

const store = new Map();
const STATS = {
  async get(k) { return store.has(k) ? store.get(k).value : null; },
  async getWithMetadata(k) { return store.has(k) ? store.get(k) : { value: null, metadata: null }; },
  async put(k, value, opts = {}) { store.set(k, { value, metadata: opts.metadata || null }); writes++; }
};
let writes = 0;
const env = { STATS, TOKEN: process.env.TOKEN || "dev-token" };
const port = Number(process.env.PORT || 8787);

http.createServer(async (req, res) => {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const body = chunks.length ? Buffer.concat(chunks) : undefined;
  const r = await worker.fetch(new Request("http://127.0.0.1:" + port + req.url, {
    method: req.method, headers: req.headers, body: req.method === "GET" || req.method === "HEAD" ? undefined : body
  }), env);
  res.writeHead(r.status, Object.fromEntries(r.headers));
  res.end(Buffer.from(await r.arrayBuffer()));
  console.log(new Date().toISOString().slice(11, 19), req.method, req.url, r.status, "kv writes:", writes);
}).listen(port, "127.0.0.1", () => console.log("stats worker on http://127.0.0.1:" + port + "/stats"));
