'use strict';
const $ = id => document.getElementById(id);
const video = $('video'), wave = $('wave'), msg = $('msg');
const WIN = 0.02; // analysis window, seconds

let levels = [];      // dB per window
let duration = 0;
let keep = [];        // [{s,e}] kept segments, seconds
let audioCtx, srcNode, exporting = false;

if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});

/* ---------- loading ---------- */
const drop = $('drop');
$('file').addEventListener('change', e => e.target.files[0] && load(e.target.files[0]));
['dragover', 'dragenter'].forEach(t => drop.addEventListener(t, e => { e.preventDefault(); drop.classList.add('over'); }));
['dragleave', 'drop'].forEach(t => drop.addEventListener(t, e => { e.preventDefault(); drop.classList.remove('over'); }));
drop.addEventListener('drop', e => e.dataTransfer.files[0] && load(e.dataTransfer.files[0]));

async function load(file) {
  setMsg('Analyzing audio…');
  $('download').hidden = true;
  video.src = URL.createObjectURL(file);
  $('editor').hidden = false;
  try {
    const buf = await file.arrayBuffer();
    audioCtx = audioCtx || new AudioContext();
    const audio = await audioCtx.decodeAudioData(buf);
    levels = measure(audio);
    duration = audio.duration;
    setMsg('');
    recompute();
  } catch (err) {
    setMsg('Could not read audio from this file (' + err.message + '). Try an MP4/WebM with an audio track.');
  }
}

function measure(audio) {
  const n = audio.numberOfChannels, size = Math.max(1, Math.round(audio.sampleRate * WIN));
  const chans = Array.from({ length: n }, (_, i) => audio.getChannelData(i));
  const out = [];
  for (let i = 0; i + size <= audio.length; i += size) {
    let sum = 0;
    for (const c of chans) for (let j = i; j < i + size; j++) sum += c[j] * c[j];
    const rms = Math.sqrt(sum / (size * n));
    out.push(20 * Math.log10(rms + 1e-9));
  }
  return out;
}

/* ---------- segmentation ---------- */
function recompute() {
  if (!levels.length) return;
  const thr = +$('thr').value, minSil = +$('min').value, pad = +$('pad').value;
  $('thrOut').textContent = thr + ' dB';
  $('minOut').textContent = minSil.toFixed(2) + ' s';
  $('padOut').textContent = pad.toFixed(2) + ' s';

  // 1. raw loud runs
  let runs = [], start = null;
  levels.forEach((l, i) => {
    if (l > thr) { if (start === null) start = i; }
    else if (start !== null) { runs.push([start * WIN, i * WIN]); start = null; }
  });
  if (start !== null) runs.push([start * WIN, levels.length * WIN]);

  // 2. pad, then merge runs separated by less than minSil (those gaps are too short to cut)
  runs = runs.map(([s, e]) => [Math.max(0, s - pad), Math.min(duration, e + pad)]);
  const merged = [];
  for (const r of runs) {
    const last = merged[merged.length - 1];
    if (last && r[0] - last[1] < minSil) last[1] = Math.max(last[1], r[1]);
    else merged.push([...r]);
  }
  keep = merged.map(([s, e]) => ({ s, e }));
  draw();
  const kept = keep.reduce((a, k) => a + k.e - k.s, 0);
  $('stats').textContent = `Original ${fmt(duration)} → trimmed ${fmt(kept)} (removes ${fmt(duration - kept)}, ${duration ? Math.round(100 * (1 - kept / duration)) : 0}%)`;
}
['thr', 'min', 'pad'].forEach(id => $(id).addEventListener('input', recompute));

const fmt = s => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;
const setMsg = t => msg.textContent = t;

/* ---------- waveform ---------- */
function draw() {
  const dpr = devicePixelRatio || 1, w = wave.clientWidth, h = 90;
  wave.width = w * dpr; wave.height = h * dpr;
  const g = wave.getContext('2d'); g.scale(dpr, dpr);
  g.clearRect(0, 0, w, h);
  // cut regions
  g.fillStyle = 'rgba(255,90,90,.25)'; g.fillRect(0, 0, w, h);
  g.fillStyle = 'rgba(91,140,255,.18)';
  for (const k of keep) g.fillRect(k.s / duration * w, 0, (k.e - k.s) / duration * w, h);
  // bars
  const per = levels.length / w;
  g.fillStyle = '#c9d4ff';
  for (let x = 0; x < w; x++) {
    let m = -90;
    for (let i = Math.floor(x * per); i < Math.floor((x + 1) * per) && i < levels.length; i++) m = Math.max(m, levels[i]);
    const a = Math.max(0, (m + 60) / 60) * (h / 2);
    g.fillRect(x, h / 2 - a, 1, Math.max(1, a * 2));
  }
  // playhead
  g.fillStyle = '#fff'; g.fillRect(video.currentTime / duration * w, 0, 2, h);
}
wave.addEventListener('click', e => { video.currentTime = (e.offsetX / wave.clientWidth) * duration; });
addEventListener('resize', draw);

/* ---------- skip on playback ---------- */
function nextKeep(t) { return keep.find(k => k.e > t); }
function tick() {
  if (!video.paused && !exporting && $('skipOn').checked && keep.length) {
    const k = nextKeep(video.currentTime);
    if (!k) { video.pause(); }
    else if (video.currentTime < k.s - 0.02) video.currentTime = k.s;
  }
  if (keep.length) draw();
  requestAnimationFrame(tick);
}
requestAnimationFrame(tick);

/* ---------- export (MediaRecorder, fully client-side) ---------- */
$('export').addEventListener('click', async () => {
  if (!keep.length) return setMsg('Load a video first.');
  if (!video.captureStream || !window.MediaRecorder) return setMsg('Export needs a recent Chrome, Edge or Firefox on desktop/Android.');
  exporting = true; $('export').disabled = true; $('download').hidden = true; $('prog').hidden = false;
  video.pause(); video.loop = false;
  const wasMuted = video.muted;

  // route audio into the recording only (silent while exporting)
  audioCtx = audioCtx || new AudioContext();
  if (!srcNode) srcNode = audioCtx.createMediaElementSource(video);
  srcNode.disconnect();               // silent while exporting
  const dest = audioCtx.createMediaStreamDestination();
  srcNode.connect(dest);
  await audioCtx.resume();

  const vTracks = video.captureStream().getVideoTracks();
  const stream = new MediaStream([...vTracks, ...dest.stream.getAudioTracks()]);
  const mime = ['video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm'].find(m => MediaRecorder.isTypeSupported(m));
  const rec = new MediaRecorder(stream, { mimeType: mime, videoBitsPerSecond: 5e6 });
  const chunks = [];
  rec.ondataavailable = e => e.data.size && chunks.push(e.data);
  const done = new Promise(r => rec.onstop = r);

  const total = keep.reduce((a, k) => a + k.e - k.s, 0);
  let doneSecs = 0;
  rec.start(1000);
  try {
    for (const k of keep) {
      await seek(k.s);
      await video.play();
      await new Promise(res => {
        const iv = setInterval(() => {
          const p = Math.min(video.currentTime, k.e) - k.s;
          $('prog').value = (doneSecs + Math.max(0, p)) / total;
          if (video.currentTime >= k.e || video.ended) { clearInterval(iv); res(); }
        }, 30);
      });
      video.pause();
      doneSecs += k.e - k.s;
    }
  } finally {
    rec.stop(); await done;
    srcNode.disconnect();
    srcNode.connect(audioCtx.destination); // restore preview sound
    video.muted = wasMuted;
    exporting = false; $('export').disabled = false; $('prog').hidden = true;
  }
  const url = URL.createObjectURL(new Blob(chunks, { type: 'video/webm' }));
  const a = $('download'); a.href = url; a.download = 'trimmed.webm'; a.textContent = 'Download trimmed.webm'; a.hidden = false;
  setMsg('Done!');
});

function seek(t) {
  return new Promise(res => {
    if (Math.abs(video.currentTime - t) < 0.01) return res();
    video.addEventListener('seeked', res, { once: true });
    video.currentTime = t;
  });
}
