import { readFile, readdir, lstat } from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { build } from 'esbuild';

export const limits = { bytes: 8 * 1024 * 1024, file: 2 * 1024 * 1024, backend: 128 * 1024, files: 200 };
export function validateManifest(m) {
  if (m.schemaVersion !== 1 || m.apiVersion !== 1 || !/^[a-z][a-z0-9-]{2,47}$/.test(m.id)) throw new Error('Unsupported manifest or app ID');
  if (typeof m.name !== 'string' || !m.name.trim() || m.name.length > 80) throw new Error('Invalid app name');
  if (m.frontend !== 'web/index.html' || m.backend !== 'backend/main.cjs') throw new Error('Expected web/index.html and backend/main.cjs');
  if (!Array.isArray(m.capabilities) || m.capabilities.length > 5) throw new Error('Invalid capabilities');
  const seen = new Set();
  for (const c of m.capabilities) {
    if (!['storage', 'haptics', 'motion', 'location', 'network'].includes(c.name) || seen.has(c.name) || typeof c.reason !== 'string' || !c.reason || c.reason.length > 300) throw new Error('Invalid capability declaration');
    seen.add(c.name);
    if (c.name === 'network' && (!Array.isArray(c.origins) || !c.origins.length || c.origins.length > 10 || c.origins.some(x => { try { const u = new URL(x); return u.protocol !== 'https:' || u.origin !== x || !!u.username || !!u.password; } catch { return true; } }))) throw new Error('Network origins must be exact HTTPS origins');
  }
  return m;
}
export function validPath(name) { return /^[a-zA-Z0-9_./-]+$/.test(name) && !name.startsWith('/') && !name.split('/').some(s => !s || s === '.' || s === '..') && !name.toLowerCase().startsWith('web/__substrate'); }
export async function bundle(directory) {
  const root = path.resolve(directory);
  const manifest = validateManifest(JSON.parse(await readFile(path.join(root, 'manifest.json'), 'utf8')));
  const files = {};
  async function walk(relative) {
    for (const entry of (await readdir(path.join(root, relative))).sort()) {
      const name = `${relative}/${entry}`;
      if (!validPath(name)) throw new Error(`Invalid path: ${name}`);
      const stat = await lstat(path.join(root, name));
      if (stat.isSymbolicLink()) throw new Error('Symlinks are not allowed');
      if (stat.isDirectory()) await walk(name);
      else {
        const data = await readFile(path.join(root, name));
        if (data.length > limits.file) throw new Error(`File too large: ${name}`);
        files[name] = data.toString('base64');
      }
    }
  }
  await walk('web');
  if (!files[manifest.frontend]) throw new Error('Missing frontend');
  const built = await build({ entryPoints: [path.join(root, manifest.backend)], bundle: true, platform: 'node', format: 'cjs', target: 'node18', write: false });
  const backend = built.outputFiles[0].contents;
  if (backend.length > limits.backend) throw new Error('Bundled backend exceeds 128 KiB');
  files[manifest.backend] = Buffer.from(backend).toString('base64');
  if (Object.keys(files).length > limits.files) throw new Error('Too many files');
  const payload = Buffer.from(JSON.stringify({ manifest, files }));
  if (payload.length > limits.bytes) throw new Error('Bundle exceeds 8 MiB');
  return { protocol: 1, type: 'publish', deliveryId: createHash('sha256').update(payload).digest('hex'), digest: createHash('sha256').update(payload).digest('hex'), payload: payload.toString('base64') };
}
