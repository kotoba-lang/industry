import https from 'https';
import http from 'http';
import { parse } from 'url';
import next from 'next';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const dev = process.env.NODE_ENV !== 'production';
const app = next({ dev });
const handle = app.getRequestHandler();

const httpsOptions = {
  key: fs.readFileSync(path.join(__dirname, 'certificates', 'localhost+2-key.pem')),
  cert: fs.readFileSync(path.join(__dirname, 'certificates', 'localhost+2.pem')),
};

app.prepare().then(() => {
  console.log('Next.js app prepared');
  
  // HTTPSサーバーの作成
  const httpsServer = https.createServer(httpsOptions, (req, res) => {
    const parsedUrl = parse(req.url, true);
    handle(req, res, parsedUrl);
  });
  
  httpsServer.listen(3001, (err) => {
    if (err) throw err;
    console.log('> HTTPS Ready on https://localhost:3001');
  });

  // HTTPサーバー（リダイレクト用）
  const httpServer = http.createServer((req, res) => {
    const host = req.headers.host || 'localhost:3000';
    const httpsUrl = `https://${host.replace(/:\d+/, ':3001')}${req.url}`;
    console.log(`Redirecting to: ${httpsUrl}`);
    res.writeHead(301, { Location: httpsUrl });
    res.end();
  });
  
  httpServer.listen(3000, (err) => {
    if (err) throw err;
    console.log('> HTTP Redirect ready on http://localhost:3000');
  });
}); 