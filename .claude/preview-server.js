// Minimal static server for simbridge-preview.html (used by .claude/launch.json).
const http = require('http');
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const port = Number(process.env.PORT) || 8765;

http.createServer((req, res) => {
  const file = path.join(root, 'simbridge-preview.html');
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(500); res.end(String(err)); return; }
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(data);
  });
}).listen(port, () => console.log(`Preview on http://localhost:${port}`));
