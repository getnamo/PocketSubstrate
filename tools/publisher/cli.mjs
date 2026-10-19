#!/usr/bin/env node
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import QRCode from 'qrcode';
import { bundle } from './bundle.mjs';
import { createPublisher } from './server.mjs';

const [command, app, ...args] = process.argv.slice(2);
const option = (name, fallback) => args.includes(name) ? args[args.indexOf(name) + 1] : fallback;
if (!['bundle', 'serve'].includes(command) || !app) {
  console.error('Usage: npm run bundle -- examples/touch-game [--out dist/game.psub]\n       npm run publish -- examples/touch-game [--host LAN_IP] [--port 9443] [--identity .publisher]');
  process.exitCode = 1;
} else if (command === 'bundle') {
  const output = option('--out', 'dist/app.psub');
  await mkdir(path.dirname(output), { recursive: true });
  await writeFile(output, JSON.stringify(await bundle(app)));
  console.log(`Wrote ${output}`);
} else {
  const addresses = Object.values(os.networkInterfaces()).flat().filter(x => x && x.family === 'IPv4' && !x.internal).map(x => x.address);
  const host = option('--host', addresses[0] || 'localhost');
  const publisher = await createPublisher({ directory: option('--identity', path.join(os.homedir(), '.pocketsubstrate', 'publisher')), advertisedHost: host, port: Number(option('--port', '9443')) });
  console.log('Pair within five minutes. Keep this pairing secret private. Use Scan QR or paste the link in the phone host.');
  console.log(await QRCode.toString(publisher.pairing, { type: 'terminal', small: true }));
  console.log(publisher.pairing);
  let previous, busy = false;
  async function update() {
    if (busy) return; busy = true;
    try { const envelope = await bundle(app); if (envelope.digest !== previous) { publisher.publish(envelope); previous = envelope.digest; console.log(`Published revision ${previous.slice(0, 12)}`); } }
    catch (error) { console.error(`Build failed; keeping previous revision: ${error.message}`); }
    finally { busy = false; }
  }
  await update();
  const timer = setInterval(update, 1000);
  const stop = async () => { clearInterval(timer); await publisher.close(); process.exit(0); };
  process.on('SIGINT', stop); process.on('SIGTERM', stop);
}
