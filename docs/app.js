// PRF Security admin panel v2.1 - per-device tree, base64-correct text, live status.
// Talks straight to the GitHub Contents API. No server.
//
// Database layout (prf-database):
//   <AndroidID>/
//     number/<phone>.txt                + "Edit Phone Number N.txt" history
//     info/user info.txt
//     images/<YYYY-MM-DD>/front_1.jpg … back_3.jpg
//     voice/<date>_<time>_attendance.m4a
const $ = (id) => document.getElementById(id);
const API = "https://api.github.com";
let token = null, owner = null, repo = null;
let devices = [], currentDevice = null, pollTimer = null, lastSha = null;

/** Fails any API call after this many ms - the panel must never hang forever. */
const TIMEOUT_MS = 20000;

$("connect").onclick = async () => {
  token = $("token").value.trim();
  owner = $("owner").value.trim() || "poriafasor";
  repo = $("repo").value.trim() || "prf-database";
  if (!token) { $("err").textContent = "Enter a GitHub token."; return; }
  $("err").textContent = "";
  try {
    const me = await api("/user");
    await api(`/repos/${owner}/${repo}`);
    localStorage.setItem("prf-panel", JSON.stringify({ token, owner, repo }));
    $("login").hidden = true;
    $("panel").hidden = false;
    $("dbMeta").textContent = `${owner}/${repo} - signed in as ${me.login}`;
    await loadDevices();
    startPolling();
  } catch (e) {
    $("err").textContent = "Connection failed: " + (e.message || e);
  }
};

$("logout").onclick = () => { localStorage.removeItem("prf-panel"); location.reload(); };
$("refresh").onclick = async () => { await loadDevices(); if (currentDevice) await openDevice(currentDevice, true); };
$("search").oninput = (e) => renderDevices(e.target.value.toLowerCase());

async function api(path, opts = {}) {
  const ctrl = new AbortController();
  const kill = setTimeout(() => ctrl.abort(), TIMEOUT_MS);
  const res = await fetch(API + path, {
    signal: ctrl.signal,
    ...opts,
    headers: {
      Authorization: "Bearer " + token,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      ...(opts.headers || {}),
    },
  });
  clearTimeout(kill);
  if (!res.ok) {
    const body = await res.text();
    throw `${res.status} ${res.statusText} - ${body.slice(0, 140)}`;
  }
  const ct = res.headers.get("content-type") || "";
  return ct.includes("json") ? res.json() : res.text();
}

/** Fetches a file as a blob - the only way private-repo media reaches the browser. */
async function apiBlob(path) {
  const ctrl = new AbortController();
  const kill = setTimeout(() => ctrl.abort(), TIMEOUT_MS);
  const res = await fetch(API + path, {
    signal: ctrl.signal,
    headers: { Authorization: "Bearer " + token, Accept: "application/vnd.github.raw" },
  });
  if (!res.ok) { clearTimeout(kill); throw `${res.status}`; }
  const b = await res.blob();
  clearTimeout(kill);
  return b;
}

/**
 * Reads a UTF-8 text file from the database as plain text.
 *
 * THE BUG THIS EXISTS FOR: the Contents API listing hands back `content` as base64, and
 * for a private repo that field is omitted entirely. The old code assigned `f.content` to
 * textContent, so every phone file rendered as a wall of base64 garbage (or empty). Media
 * already went through apiBlob; text files now do the same, with the base64 `content` as
 * an explicit fallback so nothing depends on which shape the API chose to return.
 */
async function fetchText(path, fallback) {
  try {
    const blob = await apiBlob(`/repos/${owner}/${repo}/contents/${enc(path)}`);
    const txt = await blob.text();
    if (txt) return txt;
  } catch (_) {}
  try {
    const meta = await api(`/repos/${owner}/${repo}/contents/${enc(path)}`);
    if (meta.content != null) return decodeB64(meta.content) || fallback;
    if (meta.encoding === "none" && meta.git_blob_sha) {
      const blobObj = await api(`/repos/${owner}/${repo}/git/blobs/${meta.git_blob_sha}`);
      if (blobObj.content) return decodeB64(blobObj.content);
    }
  } catch (_) {}
  return fallback;
}

/** Decodes GitHub base64 (base64-of-UTF8, possibly with embedded newlines). */
function decodeB64(b64) {
  try {
    const bin = atob(String(b64 || "").replace(/\s+/g, ""));
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return new TextDecoder("utf-8", { fatal: false }).decode(bytes);
  } catch (_) { return ""; }
}

/** Encodes each path segment so spaces and unicode survive the URL. */
function enc(path) {
  return path.split("/").map(encodeURIComponent).join("/");
}

/** Confirm dialog backed by a real overlay instead of window.confirm. */
function confirmDlg(title, body) {
  return new Promise((resolve) => {
    const over = document.createElement("div");
    over.className = "over";
    over.innerHTML = `<div class="box"><h3>${title}</h3><p>${body}</p>
      <div class="row"><button class="ghost sm" data-a="no">Cancel</button>
      <button class="danger sm" data-a="yes">Delete</button></div></div>`;
    document.body.appendChild(over);
    over.querySelector('[data-a="yes"]').onclick = () => { over.remove(); resolve(true); };
    over.querySelector('[data-a="no"]').onclick = () => { over.remove(); resolve(false); };
  });
}

/** Deletes a file in the database repo by path (needs its blob SHA). */
async function deleteEntry(path, label) {
  const ok = await confirmDlg("Delete " + label, path + "<br>This removes it from the database.");
  if (!ok) return;
  try {
    const meta = await api(`/repos/${owner}/${repo}/contents/${enc(path)}`);
    await api(`/repos/${owner}/${repo}/contents/${enc(path)}`, {
      method: "DELETE",
      body: JSON.stringify({ message: "panel: delete " + path, sha: meta.sha }),
      headers: { "Content-Type": "application/json" },
    });
    toast("Deleted " + label);
    await openDevice(currentDevice, true);
  } catch (e) {
    toast("Delete failed: " + (e.message || e), true);
  }
}

/** Small toast so deletes do not need an alert(). */
function toast(msg, bad) {
  const t = document.createElement("div");
  t.className = "toast" + (bad ? " bad" : "");
  t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), 2600);
}

// ─── device list ───────────────────────────────────────────────────────
async function loadDevices() {
  const list = $("devices");
  list.innerHTML = '<div class="spin">Loading database…</div>';
  try {
    const root = await api(`/repos/${owner}/${repo}/contents/`);
    devices = root
      .filter((e) => e.type === "dir" && !e.name.startsWith("_"))
      .map((d) => ({ id: d.name, path: d.path, meta: "" }));
    $("dbCount").textContent = `${devices.length} device(s) in database`;
    renderDevices("");
    // Stats are decorative; never let a slow sub-folder listing block the device list.
    loadAllStats().catch(() => {});
  } catch (e) {
    list.innerHTML = '<div class="spin">Failed: ' + (e.message || e) + "</div>";
  }
}

function renderDevices(filter) {
  const list = $("devices");
  const filtered = devices.filter((d) => d.id.toLowerCase().includes(filter));
  if (!filtered.length) { list.innerHTML = '<div class="spin">No devices match.</div>'; return; }
  list.innerHTML = filtered
    .map((d) => `<div class="device" data-id="${d.id}">
        <span class="id">${d.id}</span><span class="meta">tap to open</span></div>`)
    .join("");
  list.querySelectorAll(".device").forEach((el) => {
    el.onclick = () => openDevice(el.dataset.id);
  });
}

/** Per-device "N day(s) · N voice" line, shown in the device list. */
async function loadAllStats() {
  await Promise.all(devices.map(async (d) => {
    try {
      const subs = await api(`/repos/${owner}/${repo}/contents/${enc(d.id)}`);
      const names = subs.filter((e) => e.type === "dir").map((e) => e.name);
      let nDates = 0, nVoice = 0;
      if (names.includes("images")) {
        nDates = (await api(`/repos/${owner}/${repo}/contents/${enc(d.id + "/images")}`))
          .filter((e) => e.type === "dir").length;
      }
      if (names.includes("voice")) {
        nVoice = (await api(`/repos/${owner}/${repo}/contents/${enc(d.id + "/voice")}`))
          .filter((e) => e.type === "file").length;
      }
      d.meta = `${nDates} day(s) · ${nVoice} voice`;
    } catch (_) { d.meta = ""; }
  }));
  if (devices.length) renderDevices($("search").value.toLowerCase());
}

// ─── device detail ─────────────────────────────────────────────────────
async function openDevice(id, keepScroll) {
  currentDevice = id;
  const scrollY = keepScroll ? window.scrollY : 0;
  $("detailCard").hidden = false;
  $("detailTitle").textContent = id;
  $("detailPhone").textContent = "Loading…";
  $("detailInfo").textContent = "Loading…";
  $("detailDates").innerHTML = "";
  $("detailImages").innerHTML = "";
  $("detailVoices").innerHTML = "";
  try {
    const entries = await api(`/repos/${owner}/${repo}/contents/${enc(id)}`);
    const subs = entries.filter((e) => e.type === "dir").map((e) => e.name);

    // number/ - decoded text, never the raw base64 `content` from the listing.
    if (subs.includes("number")) {
      const numDir = await api(`/repos/${owner}/${repo}/contents/${enc(id + "/number")}`);
      const parts = [];
      for (const f of numDir) {
        if (f.type !== "file") continue;
        const body = await fetchText(id + "/number/" + f.name, "(unreadable)");
        const num = body.match(/Number Phone\s*:\s*(.+)/);
        const op = body.match(/Operator\s*:\s*(.+)/);
        // Compact one-line form keeps the whole correction history visible at once.
        parts.push(num
          ? `${f.name} \u2192 ${num[1].trim()} (${(op && op[1].trim()) || "?"})`
          : `${f.name}:\n${body}`);
      }
      $("detailPhone").textContent = parts.length
        ? parts.join("\n")
        : "(no readable registration)";
    } else {
      $("detailPhone").textContent = "(no phone registered)";
    }

    // info/user info.txt - decoded the same way as the number files.
    $("detailInfo").textContent = subs.includes("info")
      ? await fetchText(id + "/info/user info.txt", "(no info file)")
      : "(no info file)";

    // images/<date>/
    const imgDates = subs.includes("images") ?
      (await api(`/repos/${owner}/${repo}/contents/${enc(id + "/images")}`))
        .filter((e) => e.type === "dir").map((e) => e.name) : [];
    $("detailDates").innerHTML = imgDates
      .map((d) => `<span class="chip" data-date="${d}">${d}</span>`).join("");
    $("detailDates").querySelectorAll(".chip").forEach((c) => {
      c.onclick = () => loadPhotos(id, c.dataset.date, c);
    });
    if (imgDates.length) loadPhotos(id, imgDates.sort().at(-1), null);

    // voice/
    if (subs.includes("voice")) await loadVoices(id);
    else $("detailVoices").innerHTML = '<div class="spin">No voice attendance.</div>';

    $("deleteDevice").onclick = () => deleteDevice(id);
  } catch (e) {
    $("detailInfo").textContent = "Failed: " + (e.message || e);
  }
  if (keepScroll) window.scrollTo(0, scrollY);
}

/** Kept for external callers; everything now goes through fetchText(). */
async function fetchRaw(entry) {
  return fetchText(entry.path, "(unreadable)");
}

async function loadPhotos(id, date, chip) {
  document.querySelectorAll("#detailDates .chip").forEach((c) => c.classList.remove("active"));
  if (chip) chip.classList.add("active");
  const grid = $("detailImages");
  grid.innerHTML = '<div class="spin">Loading photos…</div>';
  try {
    const entries = await api(`/repos/${owner}/${repo}/contents/${enc(id + "/images/" + date)}`);
    const photos = entries.filter((e) => e.type === "file" && /\.(jpg|jpeg|png)$/i.test(e.name));
    if (!photos.length) {
      // A date folder holding only the .no-media marker: the user declined photo consent.
      const declined = entries.some((e) => e.name === ".no-media");
      grid.innerHTML = declined
        ? '<div class="spin">No photos - the user declined photo consent for this day.</div>'
        : '<div class="spin">No photos for this date.</div>';
      return;
    }
    grid.innerHTML = photos.map((p) => {
      const cap = p.name.replace(/\.\w+$/, "");
      return `<div class="wrap"><img alt="${p.name}" title="${p.name}">
        <span class="cap">${cap}</span>
        <button class="photodel" data-path="${id}/images/${date}/${p.name}">✕</button></div>`;
    }).join("");
    grid.querySelectorAll(".photodel").forEach((b) => {
      b.onclick = (e) => { e.stopPropagation(); deleteEntry(b.dataset.path, "photo"); };
    });
    for (const img of grid.querySelectorAll("img")) {
      const name = img.getAttribute("title");
      try {
        const blob = await apiBlob(`/repos/${owner}/${repo}/contents/${enc(id + "/images/" + date + "/" + name)}`);
        img.src = URL.createObjectURL(blob);
      } catch (_) { img.alt = "failed to load"; }
    }
  } catch (e) {
    grid.innerHTML = '<div class="spin">Failed: ' + (e.message || e) + "</div>";
  }
}

async function loadVoices(id) {
  const box = $("detailVoices");
  box.innerHTML = '<div class="spin">Loading voice attendance…</div>';
  try {
    const entries = await api(`/repos/${owner}/${repo}/contents/${enc(id + "/voice")}`);
    const clips = entries.filter((e) => e.type === "file" && /\.(m4a|amr|mp3|aac|wav)$/i.test(e.name));
    if (!clips.length) { box.innerHTML = '<div class="spin">No voice attendance.</div>'; return; }
    box.innerHTML = clips.map((c) => `<div class="voice">
        <span class="nm">${c.name}</span>
        <audio controls preload="none"></audio>
        <button class="del" data-path="${id}/voice/${c.name}">Delete</button></div>`).join("");
    box.querySelectorAll(".del").forEach((b) => {
      b.onclick = () => deleteEntry(b.dataset.path, "voice clip");
    });
    for (const el of box.querySelectorAll(".voice")) {
      const name = el.querySelector(".nm").textContent;
      const audio = el.querySelector("audio");
      try {
        const blob = await apiBlob(`/repos/${owner}/${repo}/contents/${enc(id + "/voice/" + name)}`);
        audio.src = URL.createObjectURL(blob);
      } catch (_) { audio.remove(); }
    }
  } catch (e) {
    box.innerHTML = '<div class="spin">Failed: ' + (e.message || e) + "</div>";
  }
}

/** Removes a whole device folder by emptying it first (GitHub has no recursive delete). */
async function deleteDevice(id) {
  const ok = await confirmDlg("Delete device " + id,
    "Every file under " + id + "/ is removed from the database. This cannot be undone.");
  if (!ok) return;
  try {
    for (const sub of ["number", "info", "images", "voice"]) {
      let stack = [id + "/" + sub];
      while (stack.length) {
        const dir = stack.pop();
        let entries = [];
        try { entries = await api(`/repos/${owner}/${repo}/contents/${enc(dir)}`); }
        catch (_) { continue; }
        for (const e of entries) {
          if (e.type === "dir") { stack.push(e.path); continue; }
          try {
            await api(`/repos/${owner}/${repo}/contents/${enc(e.path)}`, {
              method: "DELETE",
              body: JSON.stringify({ message: "panel: delete " + e.path, sha: e.sha }),
              headers: { "Content-Type": "application/json" },
            });
          } catch (err) { console.warn("could not delete", e.path, err); }
        }
      }
    }
    toast("Device " + id + " deleted");
    await loadDevices();
    $("detailCard").hidden = true;
  } catch (e) {
    toast("Delete failed: " + (e.message || e), true);
  }
}

/** Polls the repo so a new check-in lands without a manual Refresh. */
function startPolling() {
  stopPolling();
  pollTimer = setInterval(async () => {
    try {
      const commits = await api(`/repos/${owner}/${repo}/commits?per_page=1`);
      const sha = commits && commits.length ? commits[0].sha : null;
      if (sha && sha !== lastSha) {
        lastSha = sha;
        await loadDevices();
        if (currentDevice) await openDevice(currentDevice, true);
        toast("Database updated");
      }
    } catch (_) {}
  }, 20000);
}

function stopPolling() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null; } }

// ─── toast style hook (kept in styles.css) ──────────────────────────────
try {
  const saved = JSON.parse(localStorage.getItem("prf-panel") || "null");
  if (saved && saved.token) {
    $("token").value = saved.token;
    $("owner").value = saved.owner;
    $("repo").value = saved.repo;
  }
} catch (_) {}
