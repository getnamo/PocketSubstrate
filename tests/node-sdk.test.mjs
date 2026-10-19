import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';

test('Node bootstrap dispatches RPC and resolves native capability replies', async () => {
  const script = await readFile('packages/sdk/node-bootstrap.cjs', 'utf8') + '\n(function(module,exports,require){\n' + await readFile('examples/touch-game/backend/main.cjs', 'utf8') + '\n})(__substrateModule,__substrateModule.exports,require);';
  const child = spawn(process.execPath, ['-e', script], { stdio: ['pipe', 'pipe', 'pipe'] });
  try {
    const result = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Backend timed out')), 4000);
      child.on('error', reject);
      createInterface({ input: child.stdout }).on('line', line => {
        const message = JSON.parse(line);
        if (message.type === 'native') child.stdin.write(JSON.stringify({ type: 'reply', id: message.id, result: 42 }) + '\n');
        if (message.type === 'result') { clearTimeout(timeout); resolve(message); }
      });
      child.stdin.write(JSON.stringify({ type: 'call', id: 'test', method: 'state', params: {} }) + '\n');
    });
    assert.ok(result.result, JSON.stringify(result));
    assert.equal(result.result.best, 42);
  } finally { child.kill(); }
});
