(() => {
  const pending = new Map(), listeners = new Map();
  let sequence = 0;
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = String(++sequence);
    const timer = setTimeout(() => { pending.delete(id); reject(Object.assign(new Error('Host request timed out'), { code: 'TIMEOUT' })); }, 20000);
    pending.set(id, { resolve, reject, timer });
    window.substrateHost.postMessage(JSON.stringify({ id, method, params }));
  });
  window.substrateHost.onmessage = ({ data }) => {
    const message = JSON.parse(data);
    if (message.event) { for (const callback of listeners.get(message.event) || []) callback(message.value); return; }
    const p = pending.get(message.id);
    if (!p) return;
    pending.delete(message.id); clearTimeout(p.timer);
    if (message.error) p.reject(Object.assign(new Error(message.error.message), { code: message.error.code }));
    else p.resolve(message.result);
  };
  window.substrate = {
    call, backend: { call: (method, params = {}) => call('backend.call', { method, params }) },
    storage: { get: key => call('storage.get', { key }), set: (key, value) => call('storage.set', { key, value }) },
    haptics: { pulse: (milliseconds = 30) => call('haptics.pulse', { milliseconds }) },
    location: { current: () => call('location.current') },
    motion: { start: () => call('motion.start'), stop: () => call('motion.stop') },
    network: { fetch: url => call('network.fetch', { url }) },
    capabilities: () => call('capabilities'),
    on: (event, callback) => { if (!listeners.has(event)) listeners.set(event, new Set()); listeners.get(event).add(callback); return () => listeners.get(event).delete(callback); }
  };
  let loaded = false, failed = false;
  const report = message => {
    if (!loaded) failed = true;
    call(loaded ? 'diagnostic' : 'frontend.failed', { message: String(message).slice(0, 2000) }).catch(() => {});
  };
  window.addEventListener('error', e => report(e.message || 'Frontend resource failed'), true);
  window.addEventListener('unhandledrejection', e => report(e.reason));
  window.addEventListener('load', () => { loaded = true; if (!failed) call('frontend.ready').catch(() => {}); });
})();
