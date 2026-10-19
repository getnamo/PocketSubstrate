module.exports = {
  async load() { return await substrate.storage.get('note'); },
  async save({ text, location }) { const note = { text: String(text).slice(0, 2000), location, time: new Date().toISOString() }; await substrate.storage.set('note', note); return note; },
  async locate() { return await substrate.location.current(); }
};
