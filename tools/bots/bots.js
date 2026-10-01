// CartZone test bots. Usage: node bots.js <count> [prefix] [port] [--fight] [--stay]
// Each bot logs one line per interesting event, so a whole match can be read from stdout.
const mineflayer = require('mineflayer');

const count = parseInt(process.argv[2] || '4', 10);
const prefix = process.argv[3] || 'Bot';
const port = parseInt(process.argv[4] || '25599', 10);
const fight = process.argv.includes('--fight');
const joinDelay = 1200;

function log(name, ...a) {
  console.log(`[${new Date().toISOString().slice(11, 19)}] ${name}: ${a.join(' ')}`);
}

function start(i) {
  const name = `${prefix}${i}`;
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: name, version: '1.21.11', auth: 'offline' });
  let mountedAt = 0, dropped = false, landedLogged = false, wasGliding = false, blockTicks = 0;

  bot.once('spawn', () => log(name, 'spawned in', bot.game.dimension, 'at', fmt(bot.entity.position)));
  bot.on('kicked', r => log(name, 'KICKED', typeof r === 'string' ? r : JSON.stringify(r).slice(0, 300)));
  bot.on('end', r => log(name, 'disconnected', r));
  bot.on('error', e => log(name, 'error', e.message));
  bot.on('messagestr', (m, pos) => { if (pos !== 'game_info' && i === 0) log(name, 'chat>', m); });
  bot.on('title', (t, type) => { if (i === 0) log(name, 'title', type, flat(t)); });
  bot.on('death', () => log(name, 'DIED at', fmt(bot.entity.position)));
  bot.on('mount', () => { mountedAt = Date.now(); log(name, 'mounted', bot.vehicle && bot.vehicle.name, 'at', fmt(bot.entity.position)); });
  bot.on('dismount', () => { dropped = true; log(name, 'dismounted at', fmt(bot.entity.position)); });
  bot.on('resourcePack', () => { log(name, 'resource pack offered, accepting'); bot.acceptResourcePack && bot.acceptResourcePack(); });

  // --teams: pairs (0,1), (2,3) team up by command; everyone accepts any invite.
  bot.on('messagestr', m => {
    if (process.argv.includes('--teams') && m.includes('Team up!') && i % 2 === 0 && i < 4) {
      setTimeout(() => bot.chat(`/team invite ${prefix}${i + 1}`), 1500);
    }
    if (m.includes('invited you')) { log(name, 'got invite:', m); setTimeout(() => bot.chat('/team accept'), 800); }
    if (m.includes('joined the team')) log(name, m);
  });

  // Staggered early drops: bot i sneaks 3+3i seconds after mounting (even bots wait for the auto drop).
  setInterval(() => {
    if (!bot.entity) return;
    const chest = bot.inventory.slots[6];
    const gliding = !!bot.entity.elytraFlying;
    if (mountedAt && !dropped && !process.argv.includes('--shield') && i % 2 === 1 && Date.now() - mountedAt > 3000 + 3000 * i) {
      bot.setControlState('sneak', true);
      setTimeout(() => bot.setControlState('sneak', false), 600);
    }
    if (gliding && !wasGliding) log(name, 'gliding, chest slot =', chest ? chest.name : 'empty');
    // A real player steers down to land; bots dive so they don't glide into the map edge.
    if (gliding) bot.look(bot.entity.yaw, -1.1, true);
    wasGliding = gliding || wasGliding;
    if (wasGliding && !gliding) dropped = true;
    if (dropped && bot.entity.onGround && !landedLogged) {
      landedLogged = true;
      setTimeout(() => {
        const c = bot.inventory.slots[6];
        const elytras = bot.inventory.items().filter(it => it.name === 'elytra').length;
        log(name, 'LANDED at', fmt(bot.entity.position), 'health', bot.health, 'chest slot =', c ? c.name : 'empty', 'elytras in inv =', elytras);
      }, 400);
    }
  }, 250);

  // --shield: bot 0 blocks with its offhand, bot 1 keeps hitting it. Everyone else stays still.
  if (process.argv.includes('--shield')) {
    setInterval(() => {
      if (!bot.entity || !landedLogged) return;
      if (i === 0) {
        const off = bot.inventory.slots[45];
        if (!off || off.name !== 'shield') return;
        // Re-raise now and then: a use that started before the shield arrived does nothing.
        blockTicks = (blockTicks || 0) + 1;
        if (blockTicks % 6 === 1) { bot.deactivateItem(); bot.activateItem(true); log(name, 'raising shield, stack', off.count); }
        return;
      }
      if (i !== 1) return;
      const t = bot.players[`${prefix}0`] && bot.players[`${prefix}0`].entity;
      if (!t) return;
      bot.lookAt(t.position.offset(0, 1.5, 0), true);
      const d = t.position.distanceTo(bot.entity.position);
      bot.setControlState('forward', d > 2.5);
      if (d < 3.4) bot.attack(t);
    }, 700);
  }

  if (fight) {
    setInterval(() => {
      if (!bot.entity || !landedLogged || bot.game.gameMode === 'spectator') return;
      const near = bot.nearestEntity(e => e.type === 'player' && e.username !== name && e.position.distanceTo(bot.entity.position) < 40);
      // No one close: head for the final zone centre (stand-in arena default 40,30), like a real player would.
      const ctr = (process.argv.find(a => a.startsWith('--centre=')) || '--centre=40,30').slice(9).split(',').map(Number);
      const target = near || { position: bot.entity.position.clone().set(ctr[0], bot.entity.position.y, ctr[1]) };
      bot.lookAt(target.position.offset(0, 1.5, 0), true);
      const d = target.position.distanceTo(bot.entity.position);
      if (!near && d < 3) { bot.clearControlStates(); return; }
      bot.setControlState('forward', d > 2.5);
      bot.setControlState('sprint', d > 2.5);
      bot.setControlState('jump', bot.entity.isCollidedHorizontally);
      if (near && d < 3.4) bot.attack(near);
    }, 300);
  }
  return bot;
}

function fmt(p) { return p ? `${p.x.toFixed(1)},${p.y.toFixed(1)},${p.z.toFixed(1)}` : '?'; }
function flat(t) {
  try { const o = typeof t === 'string' ? JSON.parse(t) : t; return JSON.stringify(o).replace(/"(color|bold|italic)":[^,}]+,?/g, '').slice(0, 120); } catch { return String(t).slice(0, 120); }
}

for (let i = 0; i < count; i++) setTimeout(() => start(i), i * joinDelay);
