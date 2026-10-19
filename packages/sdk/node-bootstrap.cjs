// Trusted bootstrap is prepended by the host. App code follows in an isolated process.
const __substrateSend = value => process.stdout.write(JSON.stringify(value) + '\n');
const __substrateModule = { exports: {} };
const __substratePending = new Map();
const __substrateEvents = new Map();
let __substrateSequence = 0;
const __substrateCall = (method, params = {}) => new Promise((resolve, reject) => {
  const id = 'n' + (++__substrateSequence);
  const timer = setTimeout(() => { __substratePending.delete(id); reject(Object.assign(new Error('Host timeout'), { code: 'TIMEOUT' })); }, 20000);
  __substratePending.set(id, { resolve, reject, timer });
  __substrateSend({ type: 'native', id, method, params });
});
globalThis.substrate = {
  call: __substrateCall,
  storage: { get: key => __substrateCall('storage.get', { key }), set: (key, value) => __substrateCall('storage.set', { key, value }) },
  haptics: { pulse: (milliseconds = 30) => __substrateCall('haptics.pulse', { milliseconds }) },
  location: { current: () => __substrateCall('location.current') },
  network: { fetch: url => __substrateCall('network.fetch', { url }) },
  motion: { start: () => __substrateCall('motion.start'), stop: () => __substrateCall('motion.stop') },
  capabilities: () => __substrateCall('capabilities'),
  on: (event, callback) => { if (!__substrateEvents.has(event)) __substrateEvents.set(event, new Set()); __substrateEvents.get(event).add(callback); return () => __substrateEvents.get(event).delete(callback); }
};
for (const level of ['log', 'info', 'warn', 'error', 'debug']) console[level] = (...args) => __substrateSend({ type: 'log', level, message: args.map(String).join(' ').slice(0, 2000) });
require('readline').createInterface({ input: process.stdin, crlfDelay: Infinity }).on('line', async line => {
  try {
    const message = JSON.parse(line);
    if (message.type === 'heartbeat') { __substrateSend({ type: 'heartbeat' }); return; }
    if (message.type === 'event') { for (const callback of __substrateEvents.get(message.event) || []) callback(message.value); return; }
    if (message.type === 'reply') {
      const pending = __substratePending.get(message.id); if (!pending) return;
      __substratePending.delete(message.id); clearTimeout(pending.timer);
      if (message.error) pending.reject(Object.assign(new Error(message.error.message), { code: message.error.code }));
      else pending.resolve(message.result);
    } else if (message.type === 'call') {
      try {
        if (!Object.hasOwn(__substrateModule.exports, message.method) || typeof __substrateModule.exports[message.method] !== 'function') throw Object.assign(new Error('Unknown backend method'), { code: 'UNKNOWN_METHOD' });
        const result = await __substrateModule.exports[message.method](message.params || {});
        __substrateSend({ type: 'result', id: message.id, result: result ?? null });
      } catch (error) { __substrateSend({ type: 'result', id: message.id, error: { code: error.code || 'BACKEND_ERROR', message: String(error.message).slice(0, 2000) } }); }
    }
  } catch (error) { console.error(error.message); }
});
process.on('uncaughtException', error => { __substrateSend({ type: 'fatal', message: String(error.stack).slice(0, 2000) }); process.exit(1); });
process.on('unhandledRejection', error => console.error(error));
