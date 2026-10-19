let score = 0;
module.exports = {
  async state() {
    let best = 0;
    try { best = Number(await substrate.storage.get('best')) || 0; } catch {}
    return { score, best, pointsPerTap: 10, runtime: `Node ${process.versions.node}` };
  },
  async tap() {
    score += 10;
    let best = score;
    try { best = Math.max(score, Number(await substrate.storage.get('best')) || 0); await substrate.storage.set('best', best); } catch {}
    try { await substrate.haptics.pulse(25); } catch {}
    return { score, best };
  },
  reset() { score = 0; return { score }; }
};
