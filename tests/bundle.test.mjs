import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { bundle, validPath, validateManifest } from '../tools/publisher/bundle.mjs';

test('example contains compiled real Node backend and reproducible digest', async () => {
  const a = await bundle('examples/touch-game'), b = await bundle('examples/touch-game');
  assert.equal(a.digest, b.digest);
  const bytes = Buffer.from(a.payload, 'base64');
  assert.equal(createHash('sha256').update(bytes).digest('hex'), a.digest);
  const body = JSON.parse(bytes);
  assert.ok(Buffer.from(body.files['backend/main.cjs'], 'base64').toString().includes('process.versions.node'));
  assert.ok(body.files['web/index.html']);
});
test('archive traversal, special paths, and SDK overrides are rejected', () => {
  for (const name of ['../secret', '/etc/passwd', 'web/../x', 'web//x', 'web\\x', 'web/__substrate.js', 'web/__SUBSTRATE.js', 'web/x:y']) assert.equal(validPath(name), false, name);
  assert.equal(validPath('web/game.js'), true);
});
test('network scope requires exact HTTPS origins and duplicate grants fail', async () => {
  const envelope = await bundle('examples/field-marker');
  const { manifest } = JSON.parse(Buffer.from(envelope.payload, 'base64'));
  for (const origin of ['http://example.com', 'https://example.com/path', 'https://u:p@example.com', '*']) assert.throws(() => validateManifest({ ...manifest, capabilities: [{ name: 'network', reason: 'API', origins: [origin] }] }));
  assert.throws(() => validateManifest({ ...manifest, capabilities: [manifest.capabilities[0], manifest.capabilities[0]] }));
});
