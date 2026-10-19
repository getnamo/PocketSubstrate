const byId = id => document.getElementById(id);
let location = null;
const failure = error => { byId('status').textContent = `${error.code}: ${error.message}`; };
substrate.backend.call('load').then(note => { if (note) { byId('note').value = note.text; location = note.location; byId('location').textContent = location ? JSON.stringify(location) : 'No location attached'; } }).catch(failure);
byId('locate').onclick = () => { byId('status').textContent = 'Finding approximate location…'; substrate.backend.call('locate').then(value => { location = value; byId('location').textContent = `${value.latitude.toFixed(3)}, ${value.longitude.toFixed(3)} · ±${Math.round(value.accuracy)} m`; byId('status').textContent = ''; }).catch(failure); };
byId('save').onclick = () => substrate.backend.call('save', { text: byId('note').value, location }).then(() => { byId('status').textContent = 'Saved on this device'; }).catch(failure);
