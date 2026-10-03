// Arena block rule check: digs a map block (must stay) and a block it placed (must break).
const mineflayer = require('mineflayer');
const { Vec3 } = require('vec3');
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25599, username: 'DigBot', version: '1.21.11', auth: 'offline' });
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 19)}]`, ...a);
bot.on('resourcePack', () => bot.acceptResourcePack && bot.acceptResourcePack());
bot.on('kicked', r => log('kicked', JSON.stringify(r).slice(0, 200)));
let done = false;
bot.on('messagestr', m => { if (m.includes('DIGTEST')) run(); });
async function run() {
  if (done) return; done = true; setTimeout(() => { done = false; }, 20000);
  const below = bot.blockAt(bot.entity.position.offset(0, -1, 0));
  log('map block below:', below && below.name);
  try { await bot.dig(below, true); } catch (e) { log('dig error', e.message); }
  await bot.waitForTicks(10);
  const after = bot.blockAt(below.position);
  log('MAP BLOCK after dig:', after.name, after.name === below.name ? '(kept, good)' : '(BROKEN, bad)');
  const stone = bot.inventory.items().find(i => i.name === 'stone');
  if (!stone) { log('no stone to place'); return; }
  await bot.equip(stone, 'hand');
  const side = bot.blockAt(bot.entity.position.offset(1, -1, 0));
  try { await bot.placeBlock(side, new Vec3(0, 1, 0)); } catch (e) { log('place error', e.message); }
  await bot.waitForTicks(10);
  const placed = bot.blockAt(side.position.offset(0, 1, 0));
  log('placed:', placed.name);
  try { await bot.dig(placed, true); } catch (e) { log('dig2 error', e.message); }
  await bot.waitForTicks(10);
  log('PLACED BLOCK after dig:', bot.blockAt(placed.position).name);
}
setTimeout(() => process.exit(0), 600000);
