import test from 'node:test';
import assert from 'node:assert/strict';
import https from 'node:https';
import os from 'node:os';
import path from 'node:path';
import { mkdtemp, rm } from 'node:fs/promises';
import { WebSocket } from 'ws';
import { createPublisher } from '../tools/publisher/server.mjs';
import { bundle } from '../tools/publisher/bundle.mjs';

test('one-time pairing, authenticated delivery, and catch-up', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'substrate-test-'));
  const server = await createPublisher({ directory, port: 0, host: '127.0.0.1' });
  const secret = new URL(server.pairing).searchParams.get('secret');
  const request = token => new Promise((resolve, reject) => {
    const req = https.request({ host: '127.0.0.1', port: server.port, path: '/pair', method: 'POST', ca: server.cert, checkServerIdentity: () => undefined, headers: { authorization: `Bearer ${token}` } }, res => { let text = ''; res.on('data', data => text += data); res.on('end', () => resolve({ status: res.statusCode, text })); });
    req.on('error', reject); req.end();
  });
  try {
    assert.equal((await request('wrong')).status, 401);
    const paired = await request(secret); assert.equal(paired.status, 200);
    const token = JSON.parse(paired.text).token;
    assert.equal((await request(secret)).status, 401);
    const envelope = await bundle('examples/touch-game'); server.publish(envelope);
    await new Promise((resolve, reject) => {
      const ws = new WebSocket(`wss://127.0.0.1:${server.port}/session`, { ca: server.cert, checkServerIdentity: () => undefined, headers: { authorization: `Bearer ${token}` } });
      const timer = setTimeout(() => { ws.terminate(); reject(new Error('Delivery timeout')); }, 5000);
      ws.on('error', reject);
      ws.on('message', bytes => { const msg = JSON.parse(bytes); if (msg.type === 'publish') { assert.equal(msg.digest, envelope.digest); clearTimeout(timer); ws.close(); resolve(); } });
    });
    await new Promise((resolve, reject) => {
      const ws = new WebSocket(`wss://127.0.0.1:${server.port}/session`, { ca: server.cert, checkServerIdentity: () => undefined });
      ws.on('unexpected-response', (_req, res) => { assert.equal(res.statusCode, 401); res.resume(); resolve(); });
      ws.on('open', () => { ws.close(); reject(new Error('Unauthenticated connection accepted')); });
      ws.on('error', () => {});
    });
  } finally { await server.close(); await rm(directory, { recursive: true, force: true }); }
});
