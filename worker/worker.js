// d-smp.vip stats Worker (Cloudflare Workers, free plan).
//
// The plugin POSTs the leaderboards, recent rounds and live status here every minute
// (Authorization: Bearer <TOKEN>), and the website GETs them. Needs two bindings:
//   STATS  a KV namespace (Settings > Bindings > KV namespace)
//   TOKEN  a secret with the same value as stats-token in plugins/CartZone/secrets.yml
// Setup steps: see README.md next to this file.

// Websites allowed to read the stats (the site itself, Netlify previews, local testing).
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

    if (url.pathname !== "/" && url.pathname !== "/stats") return text("Not found", 404, cors);

    if (req.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: { ...cors, "Access-Control-Allow-Methods": "GET", "Access-Control-Max-Age": "86400" } });
    }

    if (req.method === "GET" || req.method === "HEAD") {
      if (!env.STATS) return text("KV binding STATS is missing", 500, cors);
      const body = (await env.STATS.get("stats", { cacheTtl: 30 })) || "{}";
      return new Response(req.method === "HEAD" ? null : body, {
        headers: { ...cors, "Content-Type": "application/json; charset=utf-8", "Cache-Control": "public, max-age=30" }
      });
    }

    if (req.method === "POST") {
      if (!env.STATS || !env.TOKEN) return text("STATS or TOKEN is not set up", 500, cors);
      if (!sameSecret(req.headers.get("Authorization") || "", "Bearer " + env.TOKEN)) return text("Unauthorized", 401, cors);
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
      const boards = JSON.stringify([data.wins, data.kills, data.played, data.rounds]);
      if (prev.value) {
        let same = false;
        try {
          const p = JSON.parse(prev.value);
          same = JSON.stringify([p.wins, p.kills, p.played, p.rounds]) === boards;
        } catch (e) { /* stored value unreadable: overwrite it */ }
        const age = Date.now() - ((prev.metadata && prev.metadata.at) || 0);
        if (same && (JSON.stringify(data.status) === JSON.stringify(JSON.parse(prev.value).status) || age < STATUS_WRITE_MS)) {
          return text("Unchanged", 202, cors);
        }
      }
      await env.STATS.put("stats", raw, { metadata: { at: Date.now() } });
      return text("Saved", 200, cors);
    }

    return text("Method not allowed", 405, cors);
  }
};

function text(msg, status, headers) {
  return new Response(msg, { status, headers: { ...headers, "Content-Type": "text/plain" } });
}

// Compares without leaking how many characters matched.
function sameSecret(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
