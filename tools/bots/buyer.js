// A buyer bot: joins, waits for the Host GUI, logs its buttons, clicks Finish (slot 49).
const mineflayer = require('mineflayer');
const name = process.argv[2] || 'Buyer0';
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25599, username: name, version: '1.21.11', auth: 'offline' });
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 19)}] ${name}:`, ...a);
bot.on('kicked', r => log('KICKED', JSON.stringify(r).slice(0, 200)));
bot.on('spawn', () => log('spawned'));
bot.on('messagestr', (m, pos) => { if (pos !== 'game_info') log('chat>', m); });
bot.on('windowOpen', w => {
  log('window opened:', JSON.stringify(w.title).slice(0, 120), 'slots', w.slots.length);
  for (const s of [10, 12, 13, 14, 16, 28, 30, 32, 49]) {
    const it = w.slots[s];
    if (it) log(`  slot ${s}: ${it.name}`, it.customName ? JSON.stringify(it.customName).slice(0, 80) : '');
  }
  setTimeout(() => { log('clicking Finish'); bot.clickWindow(49, 0, 0).catch(e => log('click error', e.message)); }, 1500);
});
setTimeout(() => process.exit(0), 40000);
