/* ============================================================
   D-SMP.VIP - site config. Edit the values in this first block.
   ============================================================ */
const SITE = {
  ip: "d-smp.vip",        // the address players type into Minecraft
  discord: "#",           // your Discord invite link
  edition: "Java 1.21.11",
  liveStatus: true,       // live player count via api.mcsrvstat.us (visitors' browsers ask it for your server's status)
  demo: true              // true = show sample data so you can see the design. Set to false when you go live.
};

// The three things you sell.
//  - url:   your checkout link for that product, e.g. https://yourstore.tebex.io/package/123
//           Until you set one, the button shows "Coming soon" and nobody can pay.
//  - price: only what is DISPLAYED here. The real charge is whatever you set at checkout, so keep them identical.
//  - perks: only list what you can really deliver in-game. Keep them cosmetic or access-only (no pay-to-win).
const SHOP = [
  {
    art: "chat", name: "VIP", blurb: "Get in more often.",
    price: "€9.99", per: "30 days", cta: "Buy VIP", url: "",
    perks: ["Much lower chance of being purged when the lobby is full", "Wind charges in the lobby", "[VIP] tag in chat and the tab list"]
  },
  {
    art: "immune", name: "VIP+", blurb: "Always get in.", featured: true, badge: "Most popular",
    price: "€19.99", per: "30 days", cta: "Buy VIP+", url: "",
    perks: ["Everything in VIP", "Never purged, so you always get in", "Elytra and wind charges in the lobby", "[VIP+] tag in chat and the tab list"]
  },
  {
    art: "pass", name: "Game Ticket", blurb: "Host your own game.",
    price: "€4.99", per: "per game", cta: "Buy a game", url: "",
    perks: ["Pick the squad size, player cap, modifiers and length", "Set it up in-game right after buying, and Discord announces it", "Host rank for your game"]
  }
];

// Your real data. Fill these by hand, or from your server/plugin later.
const DATA = {
  wins:   [],  // { name: "Player", val: 12 }
  kills:  [],  // { name: "Player", val: 240 }
  played: [],  // { name: "Player", val: 31 }
  rounds: [],  // { id: 42, winner: "Player", players: "18/20", time: "21:42" }
  news:   [],  // { title: "Season 1 is live", date: "2026-10-01", body: "...", tag: "Update" }
  staff:  []   // { name: "Player", role: "owner" | "admin" | "mod" | "helper" }
};

// Sample data, only shown while SITE.demo is true.
const DEMO = {
  wins:   [["Glintstone",48],["FrostByte",41],["Shardling",37],["IceVein",33],["DeepCut",29],["Brightcore",24],["Prismatic",21],["ColdSteel",18],["Facet",15],["LastLight",12]],
  kills:  [["DeepCut",412],["Shardling",377],["Glintstone",351],["IceVein",298],["FrostByte",266],["Prismatic",240],["Brightcore",213],["ColdSteel",190],["Facet",171],["LastLight",144]],
  played: [["Glintstone",120],["Brightcore",114],["FrostByte",109],["IceVein",97],["Shardling",92],["DeepCut",88],["Facet",74],["Prismatic",70],["ColdSteel",66],["LastLight",51]],
  rounds: [[128,"Shardling","64/80","22:14"],[127,"Glintstone","58/80","19:52"],[126,"DeepCut","80/80","24:31"],[125,"FrostByte","47/80","18:47"],[124,"Glintstone","72/80","23:05"],[123,"IceVein","69/80","21:20"],[122,"Brightcore","51/80","20:11"],[121,"Prismatic","80/80","24:58"]],
  news: [
    { title: "Season 1 is live", date: "Add a date", body: "Connect to d-smp.vip and jump into your first round.", tag: "Update" },
    { title: "Protection books", date: "Add a date", body: "Books and kills now stack Protection on your armor, up to IV. Kills also repair your gear.", tag: "Gameplay" },
    { title: "Store is open", date: "Add a date", body: "Ranks and hosted games only. Kill effects and suffixes are earned with coins in game.", tag: "Store" }
  ],
  staff: [{ name: "YourName", role: "owner" }, { name: "AdminName", role: "admin" }, { name: "ModName", role: "mod" }]
};

/* ============================================================
   Everything below is the site's behaviour. You shouldn't need to edit it.
   ============================================================ */
const $  = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
const esc = s => String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const fmt = n => new Intl.NumberFormat().format(n);
const D = SITE.demo ? DEMO : DATA;

/* ---------- wire up config ---------- */
$$("[data-ip]").forEach(el => (el.textContent = SITE.ip));
$$("[data-discord]").forEach(el => (el.href = SITE.discord));
$("#st-edition").textContent = SITE.edition;
$("#demo-note").hidden = !SITE.demo;

/* ---------- small helpers for rendering ---------- */
function head(name) {
  const h = [...name].reduce((a, c) => (a * 31 + c.charCodeAt(0)) % 360, 7);
  const real = SITE.demo ? "" : `<img src="https://mc-heads.net/avatar/${encodeURIComponent(name)}/64" alt="" loading="lazy" onerror="this.remove()">`;
  return `<span class="hd" style="--h:${h}">${esc(name[0] || "?")}${real}</span>`;
}
const empty = msg => `<div class="bempty">${msg}</div>`;
const asObj = (r, keys) => (Array.isArray(r) ? Object.fromEntries(keys.map((k, i) => [k, r[i]])) : r);

/* ---------- leaderboard ---------- */
const TABS = [["wins", "Wins"], ["kills", "Kills"], ["played", "Rounds"]];
let lbKey = "wins";
function renderLB() {
  const label = TABS.find(t => t[0] === lbKey)[1];
  const rows = (D[lbKey] || []).slice(0, 10).map(r => asObj(r, ["name", "val"]));
  $("#lb-body").innerHTML = rows.length
    ? `<div class="bhead"><span>#</span><span>Player</span><span>${label}</span></div>` +
      rows.map((r, i) => `<div class="brow ${i < 3 ? "brow--top" : ""}" style="--i:${i}"><span><span class="flap">${i + 1}</span></span><span class="name">${head(r.name)}<span>${esc(r.name)}</span></span><span class="num">${fmt(r.val)}</span></div>`).join("")
    : empty("Nobody on the board yet.<br>The first round is coming.");
  $$("#lb-tabs .tab").forEach(b => b.setAttribute("aria-pressed", b.dataset.k === lbKey));
}
$("#lb-tabs").innerHTML = TABS.map(([k, l]) => `<button class="tab" type="button" data-k="${k}" aria-pressed="${k === lbKey}">${l}</button>`).join("");
$("#lb-tabs").addEventListener("click", e => {
  const b = e.target.closest(".tab");
  if (b) { lbKey = b.dataset.k; renderLB(); }
});
renderLB();

/* ---------- recent rounds ---------- */
(function () {
  const rows = (D.rounds || []).slice(0, 8).map(r => asObj(r, ["id", "winner", "players", "time"]));
  $("#gm-body").innerHTML = rows.length
    ? `<div class="bhead g"><span>Round</span><span>Winner</span><span>Players</span><span>Time</span></div>` +
      rows.map((r, i) => `<div class="brow g" style="--i:${i}"><span><span class="flap">#${esc(r.id)}</span></span><span class="name">${head(r.winner)}<span>${esc(r.winner)}</span></span><span>${esc(r.players)}</span><span>${esc(r.time)}</span></div>`).join("")
    : empty("No rounds played yet.");
})();

/* ---------- news ---------- */
(function () {
  const list = D.news || [];
  $("#news-list").innerHTML = list.length
    ? list.map((n, i) => `<article class="post rv" style="--d:${i}"><div class="post__meta"><span class="chip">${esc(n.tag || "News")}</span><time>${esc(n.date || "")}</time></div><h3>${esc(n.title)}</h3><p>${esc(n.body)}</p></article>`).join("")
    : `<article class="post rv"><h3>Nothing yet</h3><p>Announcements will land here.</p></article>`;
})();

/* ---------- staff ---------- */
(function () {
  const list = D.staff || [];
  const roles = { owner: "Owner", admin: "Admin", mod: "Moderator", helper: "Helper" };
  $("#crew-list").innerHTML = list.length
    ? list.map((s, i) => `<div class="mem rv" style="--d:${i % 4}">${head(s.name)}<div><b>${esc(s.name)}</b><span class="role role--${esc(s.role)}">${roles[s.role] || esc(s.role)}</span></div></div>`).join("")
    : `<p class="sec__sub">Staff list coming soon.</p>`;
})();

/* ---------- store ---------- */
const ART = {
  chat: `<div class="tier__art"><p class="mc"><b class="tg-vip">[VIP]</b> <i>Glintstone</i>: gg</p><p class="mc"><span class="dim">Purge</span>: 60 players removed</p><p class="mc"><b class="tg-vip">[VIP]</b> <i>IceVein</i>: made it in again</p></div>`,
  immune: `<div class="tier__art"><p class="mc"><span class="dim">Purge</span>: 80 players removed</p><p class="mc"><b class="tg-vipplus">[VIP+]</b> <i>DeepCut</i>: still here</p><p class="mc"><b class="tg-vipplus">[VIP+]</b> <i>Shardling</i>: never purged</p></div>`,
  pass: `<div class="tier__art art-pass"><div class="pass"><div><small>Admit one</small><b>Your<br>game</b></div><span class="no">01</span></div></div>`
};
const safeUrl = u => (/^https?:\/\//i.test(u || "") ? u : "");
$("#tiers").innerHTML = SHOP.map((p, i) => {
  const url = safeUrl(p.url);
  const buy = url
    ? `<a class="btn ${p.featured ? "btn--gem" : "btn--buy"} buy" href="${esc(url)}" target="_blank" rel="noopener noreferrer">${esc(p.cta)}</a>`
    : `<button class="btn btn--off buy" type="button" disabled>Coming soon</button>`;
  return `<article class="tier${p.featured ? " tier--feat" : ""} rv" style="--d:${i}">
    ${p.badge ? `<span class="tier__badge">${esc(p.badge)}</span>` : ""}
    ${ART[p.art] || ""}
    <div class="tier__body">
      <h3>${esc(p.name)}</h3>
      <p class="tier__sub">${esc(p.blurb)}</p>
      <div class="tier__price"><b>${esc(p.price)}</b><small>${esc(p.per)}</small></div>
      <ul class="perks">${p.perks.map(x => `<li><svg class="ico"><use href="#i-check"/></svg>${esc(x)}</li>`).join("")}</ul>
      ${buy}
    </div>
  </article>`;
}).join("");

/* ---------- copy IP + toast ---------- */
const toastEl = $("#toast");
let toastT;
function toast(msg) {
  toastEl.textContent = msg;
  toastEl.classList.add("show");
  clearTimeout(toastT);
  toastT = setTimeout(() => toastEl.classList.remove("show"), 2600);
}
function copyIP(from) {
  const done = () => toast(`Copied ${SITE.ip}. Paste it in Multiplayer, Add Server.`);
  const fallback = () => {
    const ta = document.createElement("textarea");
    ta.value = SITE.ip; ta.style.cssText = "position:fixed;opacity:0";
    document.body.appendChild(ta); ta.select();
    try { document.execCommand("copy"); done(); } catch (e) { toast(SITE.ip); }
    ta.remove();
  };
  if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(SITE.ip).then(done, fallback);
  else fallback();
  const r = from.getBoundingClientRect();
  sparkle(r.left + r.width / 2, r.top + r.height / 2, 28);
}
document.addEventListener("click", e => {
  const b = e.target.closest("[data-copy-ip]");
  if (b) copyIP(b);
});

/* ---------- live server status ---------- */
async function loadStatus() {
  const cell = $("#st-state"), label = $("#st-label"), pl = $("#st-players");
  const set = (cls, text, players) => { cell.className = "status__cell is-" + cls; label.textContent = text; pl.textContent = players; };
  if (!SITE.liveStatus) return set("unknown", "Not tracked", "-");
  try {
    const c = new AbortController();
    const t = setTimeout(() => c.abort(), 7000);
    const r = await fetch("https://api.mcsrvstat.us/3/" + encodeURIComponent(SITE.ip), { signal: c.signal });
    clearTimeout(t);
    const d = await r.json();
    if (d.online) set("online", "Online", (d.players && d.players.online != null ? d.players.online : 0) + " / " + (d.players && d.players.max != null ? d.players.max : "?"));
    else set("offline", "Offline", "-");
  } catch (e) { set("unknown", "Unavailable", "-"); }
}
loadStatus();
setInterval(() => { if (!document.hidden) loadStatus(); }, 60000);

/* ---------- mobile menu ---------- */
const nav = $("#nav"), burger = $("#burger");
const closeMenu = () => { nav.classList.remove("open"); burger.setAttribute("aria-expanded", "false"); };
burger.addEventListener("click", () => burger.setAttribute("aria-expanded", nav.classList.toggle("open")));
nav.addEventListener("click", e => { if (e.target.closest("a")) closeMenu(); });
document.addEventListener("keydown", e => { if (e.key === "Escape") closeMenu(); });

/* ---------- scroll reveal + nav highlight ---------- */
const header = $("#top"), links = $$("#nav a");
let pendingRv = $$(".rv");
function reveal() {
  const line = innerHeight * 0.94;
  pendingRv = pendingRv.filter(el => {
    if (el.getBoundingClientRect().top < line) { el.classList.add("in"); return false; }
    return true;
  });
}
let ticking = false;
function onScroll() {
  if (ticking) return;
  ticking = true;
  requestAnimationFrame(() => {
    ticking = false;
    reveal();
    header.classList.toggle("scrolled", scrollY > 10);
    let cur = null;
    links.forEach(a => {
      const s = $(a.getAttribute("href"));
      if (s && s.getBoundingClientRect().top <= 220) cur = a;
    });
    links.forEach(a => a.classList.toggle("on", a === cur));
  });
}
addEventListener("scroll", onScroll, { passive: true });
addEventListener("resize", onScroll);
addEventListener("load", onScroll);
// Rows added after load (news, staff, store) get picked up too.
pendingRv = $$(".rv");
onScroll(); reveal();

/* ---------- sparkles: light blue diamond glints ---------- */
const fx = $("#fx"), ctx = fx.getContext("2d");
let W, H;
function sizeFx() {
  const dpr = Math.min(devicePixelRatio || 1, 2);
  W = document.documentElement.clientWidth; H = innerHeight;
  fx.width = W * dpr; fx.height = H * dpr;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
}
addEventListener("resize", sizeFx); sizeFx();

const COLORS = ["#eff6f8", "#c4dfe4", "#8cc8dc", "#80adc0"];
const parts = [];
let running = false;
function sparkle(x, y, n) {
  if (reduce) return;
  for (let i = 0; i < n; i++) {
    const a = Math.random() * 6.283, sp = 1.5 + Math.random() * 5;
    parts.push({ x, y, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp - 1.2, g: 0.12, life: 0, max: 32 + Math.random() * 26, s: 3 + Math.random() * 4, c: COLORS[(Math.random() * 4) | 0] });
  }
  if (!running) { running = true; requestAnimationFrame(tick); }
}
function tick() {
  ctx.clearRect(0, 0, W, H);
  for (let i = parts.length - 1; i >= 0; i--) {
    const p = parts[i];
    p.life++; p.vy += p.g; p.vx *= 0.98; p.x += p.vx; p.y += p.vy;
    const a = 1 - p.life / p.max;
    if (a <= 0) { parts.splice(i, 1); continue; }
    ctx.globalAlpha = a;
    ctx.fillStyle = p.c;
    // a tiny rotated square, like a diamond glint
    ctx.save(); ctx.translate(p.x, p.y); ctx.rotate(0.785);
    ctx.fillRect(-p.s / 2, -p.s / 2, p.s, p.s);
    ctx.restore();
  }
  ctx.globalAlpha = 1;
  if (parts.length) requestAnimationFrame(tick);
  else running = false;
}

/* ---------- the big diamond: tilts toward the cursor, sparkles on click ---------- */
const gem = $("#gem");
gem.addEventListener("click", () => {
  const r = gem.getBoundingClientRect();
  sparkle(r.left + r.width / 2, r.top + r.height / 2, 40);
});
if (!reduce && matchMedia("(hover:hover)").matches) {
  let raf = 0;
  addEventListener("pointermove", e => {
    if (raf) return;
    raf = requestAnimationFrame(() => {
      raf = 0;
      const r = gem.getBoundingClientRect();
      if (r.bottom < 0) return;
      const nx = Math.max(-1, Math.min(1, (e.clientX - (r.left + r.width / 2)) / innerWidth * 2));
      const ny = Math.max(-1, Math.min(1, (e.clientY - (r.top + r.height / 2)) / innerHeight * 2));
      gem.style.setProperty("--ry", nx * 12 + "deg");
      gem.style.setProperty("--rx", -ny * 10 + "deg");
    });
  });
}
