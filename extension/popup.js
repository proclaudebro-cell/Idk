const D = { enabled: true, threshold: -40, minSilence: 0.35, silenceSpeed: 6 };
const unit = { threshold: ' dB', minSilence: ' s', silenceSpeed: '×' };
chrome.storage.sync.get(D, v => {
  for (const k in D) {
    const el = document.getElementById(k);
    const show = () => { const o = document.getElementById('o_' + k); if (o) o.textContent = el.value + unit[k]; };
    if (k === 'enabled') el.checked = v[k]; else { el.value = v[k]; show(); }
    el.addEventListener('input', () => {
      const val = k === 'enabled' ? el.checked : +el.value;
      chrome.storage.sync.set({ [k]: val }); if (k !== 'enabled') show();
    });
  }
});
