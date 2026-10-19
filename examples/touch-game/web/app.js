const byId = id => document.getElementById(id);
const status = text => { byId('status').textContent = text; };
const update = state => { byId('score').textContent = state.score; if ('best' in state) byId('best').textContent = state.best; };
substrate.backend.call('state').then(state => { update(state); status(`${state.runtime} · ${state.pointsPerTap} points per tap`); }).catch(error => status(error.message));
byId('planet').onclick = async () => {
  try { update(await substrate.backend.call('tap')); byId('planet').style.left = `${10 + Math.random() * 65}%`; byId('planet').style.top = `${8 + Math.random() * 52}%`; }
  catch(error) { status(error.message); }
};
byId('reset').onclick = () => substrate.backend.call('reset').then(update).catch(error => status(error.message));
byId('tilt').onclick = () => substrate.motion.start().then(() => status('Tilt controls enabled')).catch(error => status(`${error.code}: ${error.message}`));
substrate.on('motion', motion => { byId('planet').style.left = `${Math.max(5, Math.min(70, 38 - motion.x * 5))}%`; });
