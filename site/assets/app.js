/* ============================================================
   CARTROYALE.COM — site config. Edit the values in this first block.
   ============================================================ */
const SITE = {
  ip: "play.cartroyale.com",   // the address players type into Minecraft
  discord: "#",           // your Discord invite link
  edition: "Java Edition",
  liveStatus: true,       // live player count via api.mcsrvstat.us (visitors' browsers ask it for your server's status)
  demo: true              // true = show sample data so you can see the design. Set to false when you go live.
};

// The three things you sell.
//  - url:   your checkout link for that product, e.g. https://yourstore.craftingstore.net/package/123
//           Until you set one, the button shows "Coming soon" and nobody can pay.
//  - price: only what is DISPLAYED here. The real charge is whatever you set at checkout, so keep them identical.
//  - perks: only list what you can really deliver in-game. Keep them cosmetic or access-only (no pay-to-win).
const SHOP = [
  {
    art: "chat", name: "VIP", blurb: "Show your colors in chat.",
    price: "€9.99", per: "30 days", cta: "Buy VIP", url: "",
    perks: ["Much lower chance of being purged when the lobby is full", "Wind charges in the lobby", "[VIP] tag in chat and the tab list"]
  },
  {
    art: "immune", name: "VIP+", blurb: "Always get in.", featured: true, badge: "Most popular",
    price: "€19.99", per: "30 days", cta: "Buy VIP+", url: "",
    perks: ["Everything in VIP", "Can't be purged, so you always get in", "Elytra and wind charges in the lobby", "Gold [VIP+] tag"]
  },
  {
    art: "pass", name: "Game Ticket", blurb: "Host your own game.",
    price: "€4.99", per: "per game", cta: "Buy a game", url: "",
    perks: ["Host your own game: pick the mode, modifiers, squad size and player cap", "Set it up in-game right after buying, and Discord announces it", "Host rank for your game"]
  }
];

// Your real data. Fill these by hand, or from your server/plugin later.
const DATA = {
  wins:   [],  // { name: "Player", val: 12 }
  kills:  [],  // { name: "Player", val: 240 }
  played: [],  // { name: "Player", val: 31 }
  rounds: [],  // { id: 42, winner: "Player", players: "18/20", time: "07:42" }
  news:   [],  // { title: "Season 1 is live", date: "2026-10-01", body: "…", tag: "Update" }
  staff:  []   // { name: "Player", role: "owner" | "admin" | "mod" | "helper" }
};

// Sample data, only shown while SITE.demo is true.
const DEMO = {
  wins:   [["RailRider",48],["TNTTommy",41],["BoomCart",37],["CobbleKing",33],["FuseLit",29],["MineHauler",24],["SparkPlug",21],["GoldRush",18],["Detonator",15],["LastStop",12]],
  kills:  [["Detonator",412],["BoomCart",377],["RailRider",351],["FuseLit",298],["TNTTommy",266],["SparkPlug",240],["CobbleKing",213],["MineHauler",190],["GoldRush",171],["LastStop",144]],
  played: [["RailRider",120],["MineHauler",114],["TNTTommy",109],["CobbleKing",97],["BoomCart",92],["FuseLit",88],["GoldRush",74],["SparkPlug",70],["Detonator",66],["LastStop",51]],
  rounds: [[128,"BoomCart","20/20","08:14"],[127,"RailRider","18/20","06:52"],[126,"FuseLit","20/20","09:31"],[125,"TNTTommy","16/20","05:47"],[124,"RailRider","20/20","10:05"],[123,"Detonator","19/20","07:20"],[122,"CobbleKing","17/20","06:11"],[121,"SparkPlug","20/20","08:58"]],
  news: [
    { title: "Season 1 is live", date: "Add a date", body: "Connect to the server and jump into your first round.", tag: "Update" },
    { title: "Store is open", date: "Add a date", body: "Access and lobby perks only. Kill effects, cart explosion effects and suffixes are earned with coins in game.", tag: "Store" },
    { title: "Leaderboards", date: "Add a date", body: "Every win and kill counts. Climb the board and get your name up there.", tag: "Ranked" }
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
    ? `<div class="bhead"><span>#</span><span>Rider</span><span>${label}</span></div>` +
      rows.map((r, i) => `<div class="brow ${i < 3 ? "brow--top" : ""}" style="--i:${i}"><span><span class="flap">${i + 1}</span></span><span class="name">${head(r.name)}<span>${esc(r.name)}</span></span><span class="num">${fmt(r.val)}</span></div>`).join("")
    : empty("No riders on the board yet.<br>The first round is about to roll.");
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
  chat: `<div class="tier__art art-chat"><p class="mc"><b class="tg-vip">[VIP]</b> <i>RailRider</i>: gg</p><p class="mc"><i>Player</i>: how is he so fast</p><p class="mc"><b class="tg-vip">[VIP]</b> <i>BoomCart</i>: last cart standing</p></div>`,
  immune: `<div class="tier__art art-chat"><p class="mc"><i>Purge</i>: 80 players removed</p><p class="mc"><b class="tg-vipplus">[VIP+]</b> <i>FuseLit</i>: still here</p><p class="mc"><b class="tg-vipplus">[VIP+]</b> <i>BoomCart</i>: never purged</p></div>`,
  pass: `<div class="tier__art art-pass"><div class="pass"><div><small>Admit one</small><b>Your<br>game</b></div><span class="no">01</span></div></div>`
};
const safeUrl = u => (/^https?:\/\//i.test(u || "") ? u : "");
$("#tiers").innerHTML = SHOP.map((p, i) => {
  const url = safeUrl(p.url);
  const buy = url
    ? `<a class="btn ${p.featured ? "btn--tnt" : "btn--buy"} buy" href="${esc(url)}" target="_blank" rel="noopener noreferrer">${esc(p.cta)}</a>`
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
  const done = () => toast(`Copied ${SITE.ip}. Paste it in Multiplayer → Add Server.`);
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
  sparks(r.left + r.width / 2, r.top + r.height / 2, 26);
}
document.addEventListener("click", e => {
  const b = e.target.closest("[data-copy-ip]");
  if (b) copyIP(b);
});

/* ---------- live server status ---------- */
async function loadStatus() {
  const cell = $("#st-state"), label = $("#st-label"), pl = $("#st-players");
  const set = (cls, text, players) => { cell.className = "cell is-" + cls; label.textContent = text; pl.textContent = players; };
  if (!SITE.liveStatus) return set("unknown", "Not tracked", "—");
  try {
    const c = new AbortController();
    const t = setTimeout(() => c.abort(), 7000);
    const r = await fetch("https://api.mcsrvstat.us/3/" + encodeURIComponent(SITE.ip), { signal: c.signal });
    clearTimeout(t);
    const d = await r.json();
    if (d.online) set("online", "Online", (d.players && d.players.online != null ? d.players.online : 0) + " / " + (d.players && d.players.max != null ? d.players.max : "?"));
    else set("offline", "Offline", "—");
  } catch (e) { set("unknown", "Unavailable", "—"); }
}
loadStatus();
setInterval(() => { if (!document.hidden) loadStatus(); }, 60000);

/* ---------- mobile menu ---------- */
const nav = $("#nav"), burger = $("#burger");
const closeMenu = () => { nav.classList.remove("open"); burger.setAttribute("aria-expanded", "false"); };
burger.addEventListener("click", () => burger.setAttribute("aria-expanded", nav.classList.toggle("open")));
nav.addEventListener("click", e => { if (e.target.closest("a")) closeMenu(); });
document.addEventListener("keydown", e => { if (e.key === "Escape") closeMenu(); });

/* ---------- scroll reveal ---------- */
let pendingRv = $$(".rv");
function reveal() {
  const line = innerHeight * 0.94;
  pendingRv = pendingRv.filter(el => {
    if (el.getBoundingClientRect().top < line) { el.classList.add("in"); return false; }
    return true;
  });
}

/* ---------- rail progress + nav highlight ---------- */
const rail = $("#railbar"), links = $$("#nav a"), stations = $$("[data-station]");
const HEAD = 68;
let secTops = [];
function layout() {
  const max = document.documentElement.scrollHeight - innerHeight;
  $$(".st", rail).forEach(n => n.remove());
  stations.forEach(s => {
    const y = s.getBoundingClientRect().top + scrollY - HEAD;
    if (max > 0 && y > 40) {
      const i = document.createElement("i");
      i.className = "st"; i.style.left = Math.min(100, (y / max) * 100) + "%";
      rail.appendChild(i);
    }
  });
  secTops = links.map(a => ({ a, y: $(a.getAttribute("href")).getBoundingClientRect().top + scrollY }));
}
let ticking = false;
function onScroll() {
  if (ticking) return;
  ticking = true;
  requestAnimationFrame(() => {
    ticking = false;
    reveal();
    const max = document.documentElement.scrollHeight - innerHeight;
    rail.style.setProperty("--p", (max > 0 ? Math.min(100, (scrollY / max) * 100) : 0) + "%");
    let cur = secTops[0];
    secTops.forEach(s => { if (s.y <= scrollY + HEAD + 160) cur = s; });
    links.forEach(a => a.classList.toggle("on", !!cur && a === cur.a));
  });
}
addEventListener("scroll", onScroll, { passive: true });
addEventListener("resize", () => { layout(); onScroll(); });
addEventListener("load", () => { layout(); onScroll(); });
if ("ResizeObserver" in window) new ResizeObserver(() => { layout(); onScroll(); }).observe(document.body);
layout(); onScroll(); reveal();

/* ---------- effects: fuse embers + explosion ---------- */
const fx = $("#fx"), ctx = fx.getContext("2d");
const cartBtn = $("#cart"), stage = $("#stage");
let W, H;
function sizeFx() {
  const dpr = Math.min(devicePixelRatio || 1, 2);
  W = document.documentElement.clientWidth; H = innerHeight;
  fx.width = W * dpr; fx.height = H * dpr;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
}
addEventListener("resize", sizeFx); sizeFx();

const COLORS = ["#e8371c", "#ff8a1f", "#ffc93c", "#f3ecdf"];
const parts = [], rings = [];
let running = false, heroVisible = true, lastEmber = 0;

function addPart(o) { parts.push(Object.assign({ life: 0 }, o)); }
function sparks(x, y, n) {
  for (let i = 0; i < n; i++) {
    const a = Math.random() * 6.283, sp = 1.5 + Math.random() * 5;
    addPart({ x, y, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp - 1.5, g: 0.18, max: 30 + Math.random() * 25, s: 3 + Math.random() * 4, c: COLORS[(Math.random() * 3) | 0] });
  }
  run();
}
function boom(x, y) {
  for (let i = 0; i < 110; i++) {
    const a = Math.random() * 6.283, sp = 2 + Math.random() * 11;
    addPart({ x, y, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp - 2, g: 0.22, max: 40 + Math.random() * 45, s: 4 + Math.random() * 9, c: COLORS[(Math.random() * 4) | 0] });
  }
  for (let i = 0; i < 26; i++) {
    const a = Math.random() * 6.283, sp = 0.5 + Math.random() * 3;
    addPart({ x, y, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp - 1, g: -0.02, max: 55 + Math.random() * 40, s: 10 + Math.random() * 16, c: "#3a322c" });
  }
  rings.push({ x, y, r: 12, life: 0 });
  run();
}
function tick(t) {
  ctx.clearRect(0, 0, W, H);
  if (heroVisible && !reduce && t - lastEmber > 55) {
    const r = cartBtn.getBoundingClientRect();
    if (r.bottom > 0 && r.top < H)
      addPart({ x: r.left + r.width * 0.5 + (Math.random() - 0.5) * 12, y: r.top + r.height * 0.17, vx: (Math.random() - 0.5) * 0.7, vy: -(0.7 + Math.random() * 1.3), g: -0.004, max: 55 + Math.random() * 50, s: 2 + Math.random() * 3, c: COLORS[(Math.random() * 3) | 0] });
    lastEmber = t;
  }
  for (let i = parts.length - 1; i >= 0; i--) {
    const p = parts[i];
    p.life++; p.vy += p.g; p.x += p.vx; p.y += p.vy;
    const a = 1 - p.life / p.max;
    if (a <= 0) { parts.splice(i, 1); continue; }
    ctx.globalAlpha = p.c === "#3a322c" ? a * 0.55 : a;
    ctx.fillStyle = p.c;
    ctx.fillRect(Math.round(p.x / 2) * 2, Math.round(p.y / 2) * 2, p.s, p.s);
  }
  for (let i = rings.length - 1; i >= 0; i--) {
    const g = rings[i];
    g.life++; g.r += 14;
    const a = 1 - g.life / 22;
    if (a <= 0) { rings.splice(i, 1); continue; }
    ctx.globalAlpha = a; ctx.strokeStyle = "#ffc93c"; ctx.lineWidth = 4;
    ctx.beginPath(); ctx.arc(g.x, g.y, g.r, 0, 6.283); ctx.stroke();
  }
  ctx.globalAlpha = 1;
  if (parts.length || rings.length || (heroVisible && !reduce)) requestAnimationFrame(tick);
  else running = false;
}
function run() { if (!running) { running = true; requestAnimationFrame(tick); } }

new IntersectionObserver(es => { heroVisible = es[0].isIntersecting; if (heroVisible) run(); }).observe(stage);
run();

/* click the cart: boom */
let blasts = 0;
const flash = $("#flash"), hint = $("#hint");
cartBtn.addEventListener("click", () => {
  const r = cartBtn.getBoundingClientRect();
  const x = r.left + r.width / 2, y = r.top + r.height * 0.4;
  boom(x, y);
  flash.style.setProperty("--fx", x + "px"); flash.style.setProperty("--fy", y + "px");
  flash.classList.remove("go"); void flash.offsetWidth; flash.classList.add("go");
  cartBtn.classList.remove("boom"); void cartBtn.offsetWidth; cartBtn.classList.add("boom");
  if (!reduce) { document.body.classList.add("shake"); setTimeout(() => document.body.classList.remove("shake"), 450); }
  blasts++;
  hint.textContent = blasts === 1 ? "Boom. Again?" : `Carts blown up: ${blasts}`;
});

/* cart tilts toward the cursor */
if (!reduce && matchMedia("(hover:hover)").matches) {
  let raf = 0;
  stage.addEventListener("pointermove", e => {
    if (raf) return;
    raf = requestAnimationFrame(() => {
      raf = 0;
      const r = stage.getBoundingClientRect();
      const nx = (e.clientX - r.left) / r.width - 0.5, ny = (e.clientY - r.top) / r.height - 0.5;
      cartBtn.style.setProperty("--ry", nx * 16 + "deg");
      cartBtn.style.setProperty("--rx", -ny * 12 + "deg");
    });
  });
  stage.addEventListener("pointerleave", () => { cartBtn.style.setProperty("--ry", "0deg"); cartBtn.style.setProperty("--rx", "0deg"); });
}
