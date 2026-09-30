// Local stand-in for a Discord webhook: prints each embed, answers 429 once to test retries.
// Usage: node hookcatcher.js [port]
const http = require('http');
const port = parseInt(process.argv[2] || '8787', 10);
let n = 0;
http.createServer((req, res) => {
  let body = '';
  req.on('data', c => (body += c));
  req.on('end', () => {
    n++;
    if (n === 2) {
      console.log(`--- #${n} -> 429 (testing retry_after)`);
      res.writeHead(429, { 'Content-Type': 'application/json' });
      return res.end('{"message":"You are being rate limited.","retry_after":1.5,"global":false}');
    }
    try {
      const j = JSON.parse(body);
      const e = (j.embeds || [])[0] || {};
      console.log(`--- #${n} ${new Date().toISOString().slice(11, 19)} content=${JSON.stringify(j.content || '')} mentions=${JSON.stringify(j.allowed_mentions)}`);
      console.log(`TITLE: ${e.title}\n${e.description}\nFOOTER: ${e.footer ? e.footer.text : ''} COLOR: ${e.color}`);
    } catch (err) {
      console.log('bad json', body.slice(0, 200));
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
  });
}).listen(port, () => console.log('hook catcher on ' + port));
