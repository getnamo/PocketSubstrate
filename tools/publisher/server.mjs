import https from 'node:https';
import { randomBytes, createHash, timingSafeEqual } from 'node:crypto';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import selfsigned from 'selfsigned';
import { WebSocketServer } from 'ws';

export async function createPublisher({ directory, port = 9443, host = '0.0.0.0', advertisedHost = 'localhost', onStatus = () => {} }) {
  await mkdir(directory, { recursive: true, mode: 0o700 });
  let identity;
  try { identity = JSON.parse(await readFile(path.join(directory, 'identity.json'), 'utf8')); }
  catch (e) {
    if (e.code !== 'ENOENT') throw e;
    const pair = selfsigned.generate([{ name: 'commonName', value: 'PocketSubstrate publisher' }], { days: 365, keySize: 2048, algorithm: 'sha256' });
    identity = { key: pair.private, cert: pair.cert };
    await writeFile(path.join(directory, 'identity.json'), JSON.stringify(identity), { mode: 0o600, flag: 'wx' });
  }
  let clients;
  try { clients = JSON.parse(await readFile(path.join(directory, 'clients.json'), 'utf8')); } catch (e) { if (e.code !== 'ENOENT') throw e; clients = []; }
  const tokenHash = token => createHash('sha256').update(token).digest('hex');
  const valid = token => typeof token === 'string' && clients.includes(tokenHash(token));
  const secret = randomBytes(32).toString('hex'), expires = Date.now() + 5 * 60_000;
  let paired = false, latest;
  const sockets = new WebSocketServer({ noServer: true, maxPayload: 65536 });
  const server = https.createServer(identity, async (req, res) => {
    if (req.method !== 'POST' || req.url !== '/pair') { res.writeHead(404).end(); return; }
    const auth = req.headers.authorization?.replace(/^Bearer /, '') || '';
    if (paired || Date.now() > expires || auth.length !== secret.length || !timingSafeEqual(Buffer.from(auth), Buffer.from(secret))) { res.writeHead(401).end(); return; }
    paired = true;
    try {
      const token = randomBytes(32).toString('hex');
      clients.push(tokenHash(token));
      await writeFile(path.join(directory, 'clients.json'), JSON.stringify(clients), { mode: 0o600 });
      res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ protocol: 1, token }));
    } catch { res.writeHead(500).end(); }
  });
  server.on('upgrade', (req, socket, head) => {
    if (req.url !== '/session' || !valid(req.headers.authorization?.replace(/^Bearer /, ''))) { socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n'); socket.destroy(); return; }
    sockets.handleUpgrade(req, socket, head, ws => sockets.emit('connection', ws, req));
  });
  sockets.on('connection', ws => {
    ws.send(JSON.stringify({ protocol: 1, type: 'hello' }));
    if (latest) ws.send(JSON.stringify(latest));
    ws.on('message', data => {
      try { const msg = JSON.parse(data.toString()); if (['status', 'diagnostic'].includes(msg.type)) { console.log(JSON.stringify(msg)); onStatus(msg); } } catch { ws.close(1008); }
    });
    ws.on('error', () => {});
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, host, resolve); });
  const { X509Certificate } = await import('node:crypto');
  const fingerprint = createHash('sha256').update(new X509Certificate(identity.cert).raw).digest('hex');
  const actualPort = server.address().port;
  const pairing = `pocketsubstrate://pair?endpoint=${encodeURIComponent(`https://${advertisedHost}:${actualPort}`)}&fingerprint=${fingerprint}&secret=${secret}`;
  return {
    pairing, port: actualPort, fingerprint, cert: identity.cert,
    publish(envelope) { latest = envelope; for (const ws of sockets.clients) if (ws.readyState === 1) ws.send(JSON.stringify(envelope)); },
    async close() { for (const ws of sockets.clients) ws.terminate(); sockets.close(); await new Promise(resolve => server.close(resolve)); }
  };
}
