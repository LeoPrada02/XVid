const $ = (sel) => document.querySelector(sel);

// ---------------------------------------------------------------- state
// The web UI of this PC, in a browser: its own PC library and downloads. The PC's own browser is
// trusted; a browser elsewhere logs in with the token (the session is kept in a cookie).
// Phones use the XVid phone app instead (Add a phone).

let me = null; // /api/me: this PC
let jobs = [];
let videos = [];
let pollTimer = null;
const knownDone = new Set();
let current = null; // the video open in the player

// ---------------------------------------------------------------- helpers

async function api(path, opts = {}) {
  const headers = {};
  if (typeof opts.body === "string") headers["Content-Type"] = "application/json";
  const res = await fetch(path, { ...opts, headers, signal: AbortSignal.timeout(opts.timeout ?? 120000) });
  if (!res.ok) {
    let msg = res.statusText;
    try { msg = (await res.json()).detail || msg; } catch {}
    const err = new Error(msg);
    err.status = res.status;
    throw err;
  }
  return res.json();
}

function el(tag, props = {}, ...children) {
  const node = Object.assign(document.createElement(tag), props);
  node.append(...children.filter((c) => c != null));
  return node;
}

let toastTimer;
function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), 3500);
}

function fmtDuration(s) {
  if (!s) return null;
  s = Math.round(s);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

function fmtSize(bytes) {
  return bytes > 1e9 ? `${(bytes / 1e9).toFixed(1)} GB` : `${(bytes / 1e6).toFixed(1)} MB`;
}

function fmtDate(ts) {
  return new Date(ts * 1000).toLocaleDateString(undefined, { day: "numeric", month: "short" });
}

function fmtCountdown(seconds) {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

const mediaPath = (name) => `/media/${encodeURIComponent(name)}`;

// ---------------------------------------------------------------- session

async function connect() {
  try {
    me = await api("/api/me", { timeout: 4000 });
    return true;
  } catch (err) {
    if (err.status === 401) showLogin();
    else $("#boot-message").textContent = `Can't reach XVid on this PC: ${err.message}. Restart it with start.cmd.`;
    return false;
  }
}

function showLogin() {
  $("#boot").hidden = true;
  $("#app").hidden = true;
  $("#login").hidden = false;
  $("#token").focus();
}

function showApp() {
  $("#boot").hidden = true;
  $("#login").hidden = true;
  $("#app").hidden = false;
  $("#add-phone").hidden = !me.local;
  $("#add-pc").hidden = !(me.local && me.is_home);
  renderWarnings();
  renderLibrary();
  loadLibrary();
  refreshJobs();
}

$("#login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  try {
    await api("/api/login", { method: "POST", body: JSON.stringify({ token: $("#token").value }) });
    $("#login-error").hidden = true;
    if (await connect()) showApp();
  } catch (err) {
    $("#login-error").textContent = err.message;
    $("#login-error").hidden = false;
  }
});

function renderWarnings() {
  const missing = [];
  if (!me.cookies) missing.push("no X login, so sensitive, protected and subscriber-only posts fail");
  if (!me.ffmpeg) missing.push("no ffmpeg, so some videos download in lower quality");
  const text = missing.join("; ");
  $("#warning").textContent = text ? `${text[0].toUpperCase()}${text.slice(1)}.` : "";
  $("#warning").hidden = !text;
}

// ---------------------------------------------------------------- downloads

function isActive(job) {
  return job.status === "queued" || job.status === "downloading";
}

$("#add-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const url = $("#url").value.trim();
  $("#url").value = "";
  if (!url) return;
  try {
    await api("/api/jobs", { method: "POST", body: JSON.stringify({ url }) });
    toast("Downloading…");
    refreshJobs();
  } catch (err) {
    toast(err.message);
  }
});

$("#paste").addEventListener("click", async () => {
  try {
    $("#url").value = await navigator.clipboard.readText();
  } catch {
    toast("Clipboard not available, paste manually");
  }
});

function renderJob(job) {
  const status = {
    queued: "Queued",
    downloading: job.progress != null ? `${job.progress}%` : "Downloading",
    done: "Done",
    error: "Failed",
  }[job.status];
  const bar = isActive(job)
    ? el("div", { className: job.progress == null ? "bar indeterminate" : "bar" },
        el("div", { style: job.progress == null ? "" : `width:${job.progress}%` }))
    : null;
  return el("li", { className: "job" },
    el("div", { className: "job-top" },
      el("span", { className: "job-url", textContent: job.title || job.url.replace("https://", "") }),
      el("span", { className: job.status === "error" ? "error" : "muted", textContent: status })),
    bar,
    job.error ? el("p", { className: "error", textContent: job.error }) : null);
}

function renderDownloads() {
  $("#jobs-section").hidden = !jobs.length;
  $("#jobs").replaceChildren(...jobs.map(renderJob));
}

$("#clear-jobs").addEventListener("click", async () => {
  await api("/api/jobs", { method: "DELETE" }).catch(() => {});
  refreshJobs();
});

async function refreshJobs() {
  clearTimeout(pollTimer);
  try {
    jobs = await api("/api/jobs");
  } catch {
    return;
  }
  const newlyDone = jobs.filter((j) => j.status === "done" && !knownDone.has(j.id));
  newlyDone.forEach((j) => knownDone.add(j.id));
  if (newlyDone.length) loadLibrary();
  renderDownloads();
  if (jobs.some(isActive)) pollTimer = setTimeout(refreshJobs, 1500);
}

// ---------------------------------------------------------------- library

async function loadLibrary() {
  try {
    videos = await api("/api/videos");
  } catch {
    return;
  }
  renderLibrary();
}

function renderLibrary() {
  const n = videos.length;
  const count = el("span", { className: "muted small", textContent: n ? `${n} video${n === 1 ? "" : "s"}` : "" });
  const grid = n ? el("div", { className: "grid" }, ...videos.map(renderTile))
    : el("p", { className: "muted", textContent: "No videos yet." });
  const folder = el("div", { className: "folder" }, el("code", { className: "muted small", textContent: me.library }));
  if (me.local) {
    const openFolder = el("button", { className: "link", textContent: "Open folder" });
    openFolder.addEventListener("click", () => api("/api/open-folder", { method: "POST" }).catch((err) => toast(err.message)));
    folder.append(openFolder);
  }
  $("#library").replaceChildren(
    el("div", { className: "section-head" }, el("h2", { textContent: "Library" }), count),
    folder,
    grid);
}

function renderTile(v) {
  if (!v.thumb) wantThumb(v);
  const duration = fmtDuration(v.duration);
  const tile = el("button", { className: "tile", type: "button" },
    el("div", { className: "thumb" },
      v.thumb ? el("img", { src: `/thumb/${encodeURIComponent(v.thumb)}`, alt: "", loading: "lazy" }) : null,
      duration ? el("span", { className: "duration", textContent: duration }) : null),
    el("div", { className: "tile-body" },
      el("p", { className: "tile-title", textContent: v.title }),
      el("p", { className: "tile-meta muted small",
        textContent: [v.uploader_id ? `@${v.uploader_id}` : v.uploader, fmtSize(v.size), fmtDate(v.added)].filter(Boolean).join(" · ") })));
  tile.addEventListener("click", () => openPlayer(v));
  return tile;
}

// ---------------------------------------------------------------- thumbnails for videos without one
// yt-dlp only gets thumbnails for X videos. For uploads and files dropped into the library, the browser
// grabs a frame and sends it to the PC (so the PC doesn't need ffmpeg). One video at a time.

const thumbTried = new Set();
const thumbQueue = [];
let thumbBusy = false;

function grabFrame(src) {
  return new Promise((resolve, reject) => {
    const video = el("video", { muted: true, playsInline: true, preload: "auto" });
    const timer = setTimeout(() => done(new Error("Timed out")), 20000);
    function done(err, frame) {
      clearTimeout(timer);
      video.removeAttribute("src");
      video.load();
      if (err) reject(err);
      else resolve(frame);
    }
    video.addEventListener("error", () => done(new Error("Can't play this video")));
    video.addEventListener("loadedmetadata", () => (video.currentTime = Math.min(1, video.duration / 3 || 0)));
    video.addEventListener("seeked", () => {
      if (!video.videoWidth) return done(new Error("No picture"));
      const scale = Math.min(1, 640 / video.videoWidth);
      const canvas = el("canvas", { width: Math.round(video.videoWidth * scale), height: Math.round(video.videoHeight * scale) });
      try {
        canvas.getContext("2d").drawImage(video, 0, 0, canvas.width, canvas.height);
      } catch (err) {
        return done(err);
      }
      canvas.toBlob((blob) => (blob ? done(null, { blob, duration: video.duration }) : done(new Error("No picture"))),
        "image/jpeg", 0.8);
    }, { once: true });
    video.src = src;
  });
}

function saveThumb(name, frame) {
  const form = new FormData();
  form.append("file", frame.blob, "thumb.jpg");
  if (Number.isFinite(frame.duration)) form.append("duration", String(frame.duration));
  return api(`/api/videos/${encodeURIComponent(name)}/thumb`, { method: "POST", body: form });
}

function wantThumb(v) {
  if (thumbTried.has(v.name)) return;
  thumbTried.add(v.name);
  thumbQueue.push(v);
  nextThumb();
}

async function nextThumb() {
  if (thumbBusy || !thumbQueue.length) return;
  thumbBusy = true;
  const v = thumbQueue.shift();
  try {
    Object.assign(v, await saveThumb(v.name, await grabFrame(mediaPath(v.name))));
    renderLibrary();
  } catch {
    // Not every video can be played in the browser; it just keeps the empty thumbnail.
  }
  thumbBusy = false;
  nextThumb();
}

// ---------------------------------------------------------------- player

function openPlayer(v) {
  current = v;
  $("#video").src = mediaPath(v.name);
  $("#player-title").textContent = v.title;
  $("#player-meta").textContent = [v.uploader, fmtSize(v.size), fmtDate(v.added)].filter(Boolean).join(" · ");
  $("#source").hidden = !v.source_url;
  if (v.source_url) $("#source").href = v.source_url;
  $("#player").showModal();
  $("#video").play().catch(() => {});
}

$("#player").addEventListener("close", () => {
  $("#video").pause();
  $("#video").removeAttribute("src");
  $("#video").load();
  current = null;
});

$("#close").addEventListener("click", () => $("#player").close());

$("#delete").addEventListener("click", async () => {
  if (!current) return;
  if (!confirm(`Delete "${current.title}" from this PC?`)) return;
  try {
    await api(`/api/videos/${encodeURIComponent(current.name)}`, { method: "DELETE" });
  } catch (err) {
    return toast(err.message);
  }
  $("#player").close();
  loadLibrary();
});

// ---------------------------------------------------------------- upload (into this PC's library)

$("#upload").addEventListener("change", () => {
  const file = $("#upload").files[0];
  $("#upload").value = "";
  if (!file) return;
  const form = new FormData();
  form.append("file", file);
  const xhr = new XMLHttpRequest();
  xhr.open("POST", "/api/upload");
  xhr.upload.onprogress = (e) => {
    if (e.lengthComputable) toast(`Uploading ${Math.round((100 * e.loaded) / e.total)}%`);
  };
  xhr.onload = () => {
    if (xhr.status === 401) return showLogin();
    if (xhr.status >= 400) {
      let msg = "Upload failed";
      try { msg = JSON.parse(xhr.responseText).detail || msg; } catch {}
      return toast(msg);
    }
    toast("Uploaded");
    // Make its thumbnail from the file still on this computer, instead of reading it back.
    const { name } = JSON.parse(xhr.responseText);
    thumbTried.add(name);
    loadLibrary();
    const local = URL.createObjectURL(file);
    grabFrame(local)
      .then((frame) => saveThumb(name, frame))
      .then(() => loadLibrary())
      .catch(() => {})
      .finally(() => URL.revokeObjectURL(local));
  };
  xhr.onerror = () => toast("Upload failed. Is XVid still running?");
  xhr.send(form);
});

// ---------------------------------------------------------------- add a phone / add a PC (on the PC itself)

function startCountdown(expiresIn, onTick, onExpire) {
  const expires = Date.now() + expiresIn * 1000;
  const timer = setInterval(tick, 1000);
  function tick() {
    const left = Math.round((expires - Date.now()) / 1000);
    if (left <= 0) {
      clearInterval(timer);
      onExpire();
    } else {
      onTick(left);
    }
  }
  tick();
  return timer;
}

let pairTimer;

async function showInstallStep() {
  $("#install-step").hidden = true;
  $("#install-status").textContent = "";
  let install;
  try {
    ({ install } = await api("/api/app-release"));
  } catch (err) {
    $("#install-status").textContent = err.message;
    return;
  }
  if (!install) {
    $("#install-status").textContent = "XVid doesn't know which GitHub repo the app comes from. " +
      "Set XVID_RELEASES_REPO (owner/name) on this PC and restart XVid.";
    return;
  }
  // The SVG comes from this PC (the qrcode library), not from user input.
  $("#install-qr").innerHTML = install.svg;
  $("#install-page").href = install.page;
  $("#install-step").hidden = false;
}

async function openPairing() {
  if (!$("#pair").open) {
    $("#pair").showModal();
    showInstallStep();
  }
  $("#pair-app-qr").replaceChildren();
  $("#pair-status").textContent = "Creating a code…";
  clearInterval(pairTimer);
  let pairing;
  try {
    pairing = await api("/api/pair", { method: "POST" });
  } catch (err) {
    $("#pair-status").textContent = err.message;
    return;
  }
  // The SVG comes from this PC (the qrcode library), not from user input.
  $("#pair-app-qr").innerHTML = pairing.app.svg;
  pairTimer = startCountdown(pairing.expires_in,
    (left) => ($("#pair-status").textContent = `One-time code, expires in ${fmtCountdown(left)}.`),
    () => {
      $("#pair-app-qr").replaceChildren();
      $("#pair-status").textContent = "This code expired. Click New code.";
    });
}

$("#add-phone").addEventListener("click", openPairing);
$("#pair-new").addEventListener("click", openPairing);
$("#pair-close").addEventListener("click", () => $("#pair").close());
$("#pair").addEventListener("close", () => clearInterval(pairTimer));

let joinTimer;

async function openJoinCode() {
  if (!$("#join").open) $("#join").showModal();
  $("#join-code").value = "";
  $("#join-status").textContent = "Creating a code…";
  clearInterval(joinTimer);
  let result;
  try {
    result = await api("/api/pc-code", { method: "POST" });
  } catch (err) {
    $("#join-status").textContent = err.message;
    return;
  }
  $("#join-code").value = result.code;
  joinTimer = startCountdown(result.expires_in,
    (left) => ($("#join-status").textContent = `One-time code, expires in ${fmtCountdown(left)}.`),
    () => {
      $("#join-code").value = "";
      $("#join-status").textContent = "This code expired. Click New code.";
    });
}

$("#add-pc").addEventListener("click", openJoinCode);
$("#join-new").addEventListener("click", openJoinCode);
$("#join-close").addEventListener("click", () => $("#join").close());
$("#join").addEventListener("close", () => clearInterval(joinTimer));
$("#join-copy").addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText($("#join-code").value);
    toast("Code copied");
  } catch {
    $("#join-code").select();
  }
});

// ---------------------------------------------------------------- start

async function start() {
  // setup.cmd opens #add-phone, to go straight on to pairing a phone.
  const addPhone = location.hash === "#add-phone";
  history.replaceState(null, "", "/");
  if (!(await connect())) return;
  showApp();
  if (addPhone && me.local) openPairing();
}

start();
