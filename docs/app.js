// PRF Security admin panel - talks straight to the GitHub API. No server.
const $ = (id) => document.getElementById(id);
const API = "https://api.github.com";
let token = null, owner = null, repo = null;
let devices = [], currentDevice = null, currentTab = "info";

$("connect").onclick = async () => {
  token = $("token").value.trim();
  owner = $("owner").value.trim() || "poriafasor";
  repo = $("repo").value.trim() || "prf-database";
  if (!token) { $("err").textContent = "Enter a GitHub token."; return; }
  $("err").textContent = "";
  try {
    const me = await api("/user");
    const info = await api(`/repos/${owner}/${repo}`);
    $("login").hidden = true;
    $("panel").hidden = false;
    $("dbMeta").textContent = `${owner}/${repo} - signed in as ${me.login}`;
    localStorage.setItem("prf-panel", JSON.stringify({ token, owner, repo }));
    await loadDevices();
  } catch (e) {
    $("err").textContent = "Connection failed: " + (e.message || e);
  }
};

$("logout").onclick = () => {
  localStorage.removeItem("prf-panel");
  location.reload();
};

$("refresh").onclick = loadDevices;

$("search").oninput = (e) => renderDevices(e.target.value.toLowerCase());

// Tabs inside a device detail card.
$("tabInfo").onclick = () => showTab("info");
$("tabVoice").onclick = () => showTab("voice");
$("tabPhotos").onclick = () => showTab("photos");

function showTab(which) {
  currentTab = which;
  for (const [el, on] of [
    [$("tabInfo"), which === "info"],
    [$("tabVoice"), which === "voice"],
    [$("tabPhotos"), which === "photos"],
  ]) {
    el.classList.toggle("active", on);
  }
  $("detailInfo").hidden = which !== "info";
  $("voiceCard").hidden = which !== "voice";
  $("detailImages").hidden = which !== "photos";
  $("detailDates").hidden = which === "voice";
}

// Never interpolate device-controlled text straight into innerHTML.
function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[c]);
}

async function api(path) {
  const res = await fetch(API + path, {
    headers: {
      Authorization: "Bearer " + token,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
    },
  });
  if (!res.ok) {
    const body = await res.text();
    throw `${res.status} ${res.statusText} - ${body.slice(0, 140)}`;
  }
  const ct = res.headers.get("content-type") || "";
  return ct.includes("json") ? res.json() : res.text();
}

// Download a blob (used for photos and voice clips, which are binary on the wire).
async function apiBlob(path) {
  const res = await fetch(API + path, {
    headers: { Authorization: "Bearer " + token, Accept: "application/vnd.github.raw" },
  });
  if (!res.ok) throw `${res.status}`;
  return await res.blob();
}

// Enumerate <AndroidID>/ directories at the repo root. The shared Voices/ folder is
// listed separately on each device, so it is skipped here.
async function loadDevices() {
  const list = $("devices");
  list.innerHTML = '<div class="spin">Loading database…</div>';
  try {
    const root = await api(`/repos/${owner}/${repo}/contents/`);
    devices = root.filter((e) => e.type === "dir" && /^[0-9a-f]{16}$/.test(e.name))
      .map((d) => ({ id: d.name, path: d.path, dates: [], info: null }));
    $("dbCount").textContent = `${devices.length} device(s) in database`;
    renderDevices("");
  } catch (e) {
    list.innerHTML = '<div class="spin">Failed: ' + esc(e.message || e) + "</div>";
  }
}

function renderDevices(filter) {
  const list = $("devices");
  const filtered = devices.filter((d) => d.id.toLowerCase().includes(filter));
  if (!filtered.length) { list.innerHTML = '<div class="spin">No devices match.</div>'; return; }
  list.innerHTML = filtered
    .map(
      (d) => `<div class="device" data-id="${esc(d.id)}">
        <span class="id">${esc(d.id)}</span>
        <span class="meta">tap to open</span>
      </div>`
    )
    .join("");
  list.querySelectorAll(".device").forEach((el) => {
    el.onclick = () => openDevice(el.dataset.id);
  });
}

async function openDevice(id) {
  currentDevice = id;
  $("detailCard").hidden = false;
  $("detailTitle").textContent = id;
  $("detailInfo").textContent = "Loading…";
  $("detailDates").innerHTML = "";
  $("detailImages").innerHTML = "";
  $("voiceHint").textContent = "";
  $("voicePlayer").removeAttribute("src");
  showTab("info");
  try {
    // <AndroidID>/user info.txt
    const info = await api(`/repos/${owner}/${repo}/contents/${encodeURIComponent(id)}/user%20info.txt`);
    $("detailInfo").textContent = await fetchRaw(info);
    // <AndroidID>/<YYYY-MM-DD>/ directories
    const entries = await api(`/repos/${owner}/${repo}/contents/${encodeURIComponent(id)}`);
    const dates = entries.filter((e) => e.type === "dir" && /^\d{4}-\d{2}-\d{2}$/.test(e.name));
    $("detailDates").innerHTML = dates
      .map((d) => `<span class="chip" data-date="${esc(d.name)}">${esc(d.name)}</span>`)
      .join("");
    $("detailDates").querySelectorAll(".chip").forEach((c) => {
      c.onclick = () => loadPhotos(id, c.dataset.date, c);
    });
    // Voice clips: Names/<...>_attendance.m4a. List the whole shared Voices/ folder once
    // and keep the ones whose path prefix matches this device's check-in dates.
    await loadVoices(id, dates.map((d) => d.name));
    if (dates.length && currentTab === "photos") loadPhotos(id, dates.sort().at(-1).name);
  } catch (e) {
    $("detailInfo").textContent = "Failed: " + (e.message || e);
  };
}

async function fetchRaw(entry) {
  const res = await fetch(entry.download_url, {
    headers: { Authorization: "Bearer " + token },
  });
  return res.ok ? res.text() : "(unreadable)";
}

// Photos under <AndroidID>/<date>/images/, as real thumbnails.
async function loadPhotos(id, date, chip) {
  document.querySelectorAll(".chip").forEach((c) => c.classList.remove("active"));
  if (chip) chip.classList.add("active");
  const grid = $("detailImages");
  grid.innerHTML = '<div class="spin">Loading photos…</div>';
  try {
    const entries = await api(
      `/repos/${owner}/${repo}/contents/${encodeURIComponent(id)}/${date}/images`,
    );
    const photos = entries.filter((e) => e.type === "file" && /\.(jpg|jpeg|png)$/i.test(e.name));
    if (!photos.length) { grid.innerHTML = '<div class="spin">No photos for this date.</div>'; return; }
    const html = photos.map((p) => {
      const cap = p.name.replace(/\.\w+$/, "");
      return `<div class="wrap"><img alt="${esc(p.name)}" title="${esc(p.name)}"><span class="cap">${esc(cap)}</span></div>`;
    });
    grid.innerHTML = html.join("");
    // Private repo: images need the token header, so load them as tokenized blob URLs.
    for (const img of grid.querySelectorAll("img")) {
      const name = img.getAttribute("title");
      const path = `${id}/${date}/images/${name}`;
      try {
        const blob = await apiBlob(
          `/repos/${owner}/${repo}/contents/${path.split("/").map(encodeURIComponent).join("/")}`,
        );
        img.src = URL.createObjectURL(blob);
      } catch (e) {
        img.alt = "failed to load";
      }
    }
  } catch (e) {
    grid.innerHTML = '<div class="spin">Failed: ' + esc(e.message || e) + "</div>";
  }
}

// Voice attendance clips for this device. They live in the shared Voices/ folder, keyed
// by the check-in date in the filename (Voices/<date>_<time>_attendance.m4a).
async function loadVoices(id, deviceDates) {
  try {
    const entries = await api(`/repos/${owner}/${repo}/contents/Voices`);
    const clips = entries.filter(
      (e) => e.type === "file" && /_attendance\.(m4a|amr)$/i.test(e.name),
    );
    if (!clips.length) {
      $("voiceHint").textContent = "No voice attendance clips yet.";
      return;
    }
    // The client names clips by device-local timestamp only. Show them grouped by the
    // dates this device actually checked in on, newest first.
    const dateSet = new Set(deviceDates);
    const owned = clips.filter((c) => {
      const d = c.name.slice(0, 10);
      return dateSet.has(d);
    });
    if (!owned.length) {
      $("voiceHint").textContent = `No voice clips on this device's check-in dates.`;
      return;
    }
    $("voiceHint").textContent = `${owned.length} clip(s). Newest first:`;
    // List every clip as a playable row.
    const rows = owned.map((c, i) => {
      const when = c.name.replace(/_attendance\.\w+$/, "");
      return `<div class="vrow" data-idx="${i}"><span class="vwhen">${esc(when)}</span>
        <span class="vsize">${(c.size / 1024).toFixed(0)} KB</span></div>`;
    });
    let holder = document.getElementById("voiceRows");
    if (!holder) {
      holder = document.createElement("div");
      holder.id = "voiceRows";
      $("voiceCard").appendChild(holder);
    }
    holder.innerHTML = rows.join("");
    holder.querySelectorAll(".vrow").forEach((row) => {
      row.onclick = () => playVoice(owned[+row.dataset.idx]);
    });
  } catch (e) {
    $("voiceHint").textContent = "Failed to list voice clips: " + (e.message || e);
  }
}

async function playVoice(entry) {
  try {
    const blob = await apiBlob(
      `/repos/${owner}/${repo}/contents/Voices/${encodeURIComponent(entry.name)}`,
    );
    const url = URL.createObjectURL(blob);
    const player = $("voicePlayer");
    if (player.src) URL.revokeObjectURL(player.src);
    player.src = url;
    player.play().catch(() => {});
  } catch (e) {
    $("voiceHint").textContent = "Failed to load clip: " + (e.message || e);
  }
}

// Restore a previous session.
try {
  const saved = JSON.parse(localStorage.getItem("prf-panel") || "null");
  if (saved && saved.token) {
    $("token").value = saved.token;
    $("owner").value = saved.owner;
    $("repo").value = saved.repo;
  }
} catch (_) {}
