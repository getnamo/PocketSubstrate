import { mkdir, writeFile, readFile, copyFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { unzipSync } from 'fflate';
import { bundle } from './publisher/bundle.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const vendor = path.join(root, 'android/vendor/node');
const expected = 'bd7321eaa1a7602fbe0bb87302df2d79d87835cf4363fbdd17c350dbb485c2af';
let archive;
const cache = path.join(root, 'android/vendor/node-v18.20.4.zip');
try { archive = await readFile(cache); } catch (e) {
  if (e.code !== 'ENOENT') throw e;
  const response = await fetch('https://github.com/nodejs-mobile/nodejs-mobile/releases/download/v18.20.4/nodejs-mobile-v18.20.4-android.zip');
  if (!response.ok) throw new Error(`Runtime download failed: ${response.status}`);
  archive = Buffer.from(await response.arrayBuffer());
}
if (createHash('sha256').update(archive).digest('hex') !== expected) throw new Error('Node archive checksum mismatch');
await mkdir(path.dirname(cache), { recursive: true });
await writeFile(cache, archive);
for (const [name, bytes] of Object.entries(unzipSync(archive, { filter: file => file.name.startsWith('include/') || /^bin\/(arm64-v8a|x86_64)\/libnode\.so$/.test(file.name) }))) {
  if (name.endsWith('/')) continue;
  const destination = path.resolve(vendor, name);
  if (!destination.startsWith(vendor + path.sep)) throw new Error('Invalid archive path');
  await mkdir(path.dirname(destination), { recursive: true });
  await writeFile(destination, bytes);
}
const assets = path.join(root, 'android/app/src/main/assets');
await mkdir(path.join(assets, 'examples'), { recursive: true });
const licenseUrl = 'https://raw.githubusercontent.com/nodejs-mobile/nodejs-mobile/v18.20.4/LICENSE';
const licenseResponse = await fetch(licenseUrl);
if (!licenseResponse.ok) throw new Error('Could not retrieve Node license notices');
const license = Buffer.from(await licenseResponse.arrayBuffer());
if (createHash('sha256').update(license).digest('hex') !== '66233b87054d6bca9b0c2f23b027bb2caf1ebd961a6f981616d098dade33af34') throw new Error('Node license checksum mismatch');
await writeFile(path.join(assets, 'node-LICENSE.txt'), license);
await copyFile(path.join(root, 'packages/sdk/node-bootstrap.cjs'), path.join(assets, 'node-bootstrap.cjs'));
await copyFile(path.join(root, 'packages/sdk/web.js'), path.join(assets, 'web-sdk.js'));
for (const id of ['touch-game', 'field-marker']) {
  const envelope = await bundle(path.join(root, `examples/${id}`));
  const body = JSON.parse(Buffer.from(envelope.payload, 'base64'));
  body.manifest.id = `sample-${id}`;
  const payload = Buffer.from(JSON.stringify(body));
  const digest = createHash('sha256').update(payload).digest('hex');
  await writeFile(path.join(assets, `examples/${id}.psub`), JSON.stringify({ ...envelope, digest, deliveryId: digest, payload: payload.toString('base64') }));
}
console.log('Prepared verified experimental Node 18.20.4 binaries, SDK assets, and example bundles. Requires a 4 KB-page Android device.');
