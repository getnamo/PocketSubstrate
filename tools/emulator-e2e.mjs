import { mkdtemp, rm } from 'node:fs/promises';
import { spawn, execFileSync } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { bundle } from './publisher/bundle.mjs';
import { createPublisher } from './publisher/server.mjs';

const directory = await mkdtemp(path.join(os.tmpdir(), 'substrate-e2e-'));
const base = JSON.parse(Buffer.from((await bundle('examples/touch-game')).payload, 'base64'));
base.manifest.id = 'e2e-game';
// A unique run prevents old accepted revisions from influencing the test.
base.manifest.testRun = Date.now();
function envelope(body) {
  const bytes = Buffer.from(JSON.stringify(body)), digest = createHash('sha256').update(bytes).digest('hex');
  return { protocol: 1, type: 'publish', deliveryId: digest, digest, payload: bytes.toString('base64') };
}
const first = envelope(base);
const changed = structuredClone(base);
changed.files['backend/main.cjs'] = Buffer.from(Buffer.from(changed.files['backend/main.cjs'], 'base64').toString().replaceAll('score += 10', 'score += 20').replaceAll('pointsPerTap: 10', 'pointsPerTap: 20')).toString('base64');
const second = envelope(changed);
const expanded = structuredClone(changed);
expanded.manifest.capabilities.push({ name: 'location', reason: 'Test capability expansion review.' });
const third = envelope(expanded);
let phase = 0, started, duration;
const server = await createPublisher({ directory, port: 0, advertisedHost: '10.0.2.2', onStatus: msg => {
  if (msg.type !== 'status' || msg.appId !== 'e2e-game' || msg.state !== 'running') return;
  if (phase === 0 && msg.revision === first.digest) { phase = 1; started = Date.now(); server.publish(second); }
  else if (phase === 1 && msg.revision === second.digest) { phase = 2; duration = Date.now() - started; server.publish(third); }
} });
server.publish(first);
try {
  const adb = process.env.ADB || (process.env.ANDROID_HOME ? path.join(process.env.ANDROID_HOME, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb') : 'adb');
  for (const apk of ['android/network/build/outputs/apk/debug/network-debug.apk', 'android/app/build/outputs/apk/debug/app-debug.apk', 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']) execFileSync(adb, ['-e', 'install', '-r', apk], { stdio: 'inherit' });
  // adb shell forwards a shell command; quote each user-independent generated argument for the device shell.
  const quote = value => "'" + value.replaceAll("'", "'\\''") + "'";
  const command = ['am','instrument','-w','-e','class','dev.pocketsubstrate.PublisherE2eTest','-e','pairingLink',server.pairing,'-e','firstRevision',first.digest,'-e','secondRevision',second.digest,'-e','thirdRevision',third.digest,'dev.pocketsubstrate.test/androidx.test.runner.AndroidJUnitRunner'].map(quote).join(' ');
  const output = await new Promise((resolve, reject) => {
    const child = spawn(adb, ['-e', 'shell', command], { stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    child.stdout.on('data', data => { if (output.length < 6000) process.stdout.write(data.toString().slice(0, 6000 - output.length)); output += data; });
    child.stderr.on('data', data => process.stderr.write(data));
    const timer = setTimeout(() => { child.kill(); reject(new Error('E2E test timed out')); }, 120000);
    child.on('error', reject); child.on('close', code => { clearTimeout(timer); if (code) reject(new Error(`adb exited ${code}`)); else resolve(output); });
  });
  if (!/OK \(1 test\)/.test(output) || phase !== 2) throw new Error('Publisher E2E assertions failed');
  console.log(`Validated TLS pairing, live edit, capability expansion review, rollback, and lifecycle. Observed live-update interval: ${duration} ms.`);
} finally { await server.close(); await rm(directory, { recursive: true, force: true }); }
