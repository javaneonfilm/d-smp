// Logs where the nearest loot chests are, so the console can read what is in them.
const mineflayer = require('mineflayer');
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25599, username: 'ChestBot', version: '1.21.11', auth: 'offline' });
bot.on('resourcePack', () => bot.acceptResourcePack && bot.acceptResourcePack());
bot.on('messagestr', m => {
  if (!m.includes('FINDCHEST')) return;
  const ids = ['chest'].map(n => bot.registry.blocksByName[n].id);
  const found = bot.findBlocks({ matching: ids, maxDistance: 128, count: 12 });
  console.log('CHESTS ' + found.map(p => `${p.x} ${p.y} ${p.z}`).join(' | '));
});
setTimeout(() => process.exit(0), 900000);
