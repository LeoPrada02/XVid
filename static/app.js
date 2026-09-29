const $ = (sel) => document.querySelector(sel);

// ---------------------------------------------------------------- state
// On the phone, the app is installed from the home PC but works with every XVid PC in the house:
// it remembers them (and the login they share) and uses whichever ones are on.
// In a PC's own browser it only shows that PC.

const store = {
  get(key, fallback) {
    try { return JSON.parse(localStorage.getItem(`xvid.${key}`)) ?? fallback; } catch { return fallback; }
  },
  set(key, value) {
    try { localStorage.setItem(`xvid.${key}`, JSON.stringify(value)); } catch {}
  },
};

let auth = store.get("auth", null); // {session, media_key}: the same on every joined PC
let knownPcs = store.get("pcs", []); // every PC we've heard of: {url, name}
let pcs = []; // PCs that answered this time: {url, name, home, me, jobs, videos}
let isLocal = false; // this browser runs on the PC itself
let connectedAt = 0;
let pollTimer = null;
const knownDone = new Set();
let current = null; // {pc, video} open in the player
let phoneJobs = []; // downloads started on this phone, which go straight to the phone

// ---------------------------------------------------------------- helpers

async function api(pc, path, opts = {}) {
  const headers = {};
  if (typeof opts.body === "string") headers["Content-Type"] = "application/json";
  if (auth) headers.Authorization = `Bearer ${auth.session}`;
  const res = await fetch(pc.url + path, { ...opts, headers, signal: AbortSignal.timeout(opts.timeout ?? 120000) });
  if (!res.ok) {
    let msg = res.statusText;
    try { msg = (await res.json()).detail || msg; } catch {}
    const err = new Error(msg);
    err.status = res.status;
    throw err;
  }
  return res.json();
}

// <video>, <img> and download links can't send headers, so they carry the media key instead.
function mediaUrl(pc, path) {
  return `${pc.url}${path}?t=${encodeURIComponent(auth?.media_key ?? "")}`;
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

// ---------------------------------------------------------------- finding the PCs

function remember(data) {
  if (data?.session) {
    auth = { session: data.session, media_key: data.media_key };
    store.set("auth", auth);
  }
}

function rememberPcs(list) {
  for (const pc of list) {
    const known = knownPcs.find((k) => k.url === pc.url);
    if (known) known.name = pc.name || known.name;
    else knownPcs.push({ url: pc.url, name: pc.name || "" });
  }
  store.set("pcs", knownPcs);
}

async function connect() {
  const here = { url: location.origin };
  let me = null;
  let needLogin = false;
  try {
    me = await api(here, "/api/me", { timeout: 4000 });
  } catch (err) {
    needLogin = err.status === 401;
  }
  pcs = [];
  isLocal = !!me?.local;
  const addOnline = (pc, m) => {
    remember(m);
    pcs.push({ url: pc.url, name: m.name, home: m.is_home, me: m, jobs: [], videos: [] });
    rememberPcs([{ url: pc.url, name: m.name }]);
  };
  if (me) addOnline(here, me);

  if (!isLocal) {
    const probe = async (pc) => {
      try {
        addOnline(pc, await api(pc, "/api/me", { timeout: 4000 }));
      } catch (err) {
        if (err.status === 401) needLogin = true;
      }
    };
    const tried = new Set([here.url]);
    const probeNew = (list) => Promise.all(list.filter((pc) => !tried.has(pc.url) && tried.add(pc.url)).map(probe));
    await probeNew(knownPcs);
    // Ask the PCs that answered about PCs added since last time.
    const lists = await Promise.all(pcs.map((pc) => api(pc, "/api/pcs", { timeout: 4000 }).catch(() => [])));
    rememberPcs(lists.flat());
    await probeNew(knownPcs);
    pcs.sort((a, b) => b.home - a.home || a.name.localeCompare(b.name));
  }

  connectedAt = Date.now();
  if (pcs.length) return true;
  if (needLogin) showLogin();
  else {
    $("#boot-message").textContent =
      "Can't reach any of your PCs. Check that one is on and running XVid, and that you're on the home Wi-Fi.";
  }
  return false;
}

async function redeemPairing(code, url) {
  try {
    remember(await api({ url }, "/api/pair/redeem", { method: "POST", body: JSON.stringify({ code }) }));
    rememberPcs([{ url }]);
    toast("Phone connected to XVid");
  } catch (err) {
    toast(err.message);
  }
}

// ---------------------------------------------------------------- session

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
  $("#download").textContent = isLocal ? "Download" : "To phone";
  $("#to-pc").hidden = isLocal;
  $("#add-phone").hidden = !isLocal;
  $("#add-pc").hidden = !(isLocal && pcs[0].me.is_home);
  $("#install-hint").hidden = isLocal || isInstalled();
  renderTargets();
  renderWarnings();
  renderOffline();
  renderLibraries();
  pcs.forEach(loadLibrary);
  refreshJobs();
}

$("#login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  try {
    const body = JSON.stringify({ token: $("#token").value });
    remember(await api({ url: location.origin }, "/api/login", { method: "POST", body }));
    $("#login-error").hidden = true;
    if (await connect()) showApp();
  } catch (err) {
    $("#login-error").textContent = err.message;
    $("#login-error").hidden = false;
  }
});

function renderTargets() {
  const select = $("#target");
  const saved = store.get("target", null);
  select.replaceChildren(...pcs.map((pc) => el("option", { value: pc.url, textContent: pc.name })));
  select.value = pcs.some((pc) => pc.url === saved) ? saved : pcs[0].url;
  $("#target-row").hidden = isLocal || pcs.length < 2;
}

$("#target").addEventListener("change", () => store.set("target", $("#target").value));

// Where "To PC" and uploads go.
function targetPc() {
  return pcs.find((pc) => pc.url === $("#target").value) ?? pcs[0];
}

function renderWarnings() {
  const lines = pcs.flatMap((pc) => {
    const missing = [];
    if (!pc.me.cookies) missing.push("no X login, so sensitive, protected and subscriber-only posts fail");
    if (!pc.me.ffmpeg) missing.push("no ffmpeg, so some videos download in lower quality");
    if (!missing.length) return [];
    const text = missing.join("; ");
    return [pcs.length > 1 ? `${pc.name}: ${text}.` : `${text[0].toUpperCase()}${text.slice(1)}.`];
  });
  $("#warning").textContent = lines.join(" ");
  $("#warning").hidden = !lines.length;
}

function renderOffline() {
  const off = isLocal ? [] : knownPcs.filter((k) => k.name && !pcs.some((pc) => pc.url === k.url));
  $("#offline").textContent = off.map((k) => `${k.name} is off or not reachable.`).join(" ");
  $("#offline").hidden = !off.length;
}

// ---------------------------------------------------------------- downloads

function isActive(job) {
  return job.status === "queued" || job.status === "downloading";
}

async function queue(url, pc = targetPc()) {
  try {
    await api(pc, "/api/jobs", { method: "POST", body: JSON.stringify({ url }) });
    toast(isLocal ? "Downloading…" : `Downloading on ${pc.name}…`);
    refreshJobs();
  } catch (err) {
    toast(err.message);
  }
}

function takeUrl() {
  const url = $("#url").value.trim();
  $("#url").value = "";
  return url;
}

$("#add-form").addEventListener("submit", (e) => {
  e.preventDefault();
  const url = takeUrl();
  if (!url) return;
  if (isLocal) queue(url);
  else saveToPhone(url);
});

$("#to-pc").addEventListener("click", () => {
  const url = takeUrl();
  if (url) queue(url);
});

// Straight to the phone: a PC finds the video and passes it through, without keeping it.
// Any PC that's on will do; if one doesn't answer, try the next.
async function saveToPhone(url) {
  const job = { label: url.replace("https://", ""), status: "preparing", files: [], error: null, pc: null };
  phoneJobs.unshift(job);
  renderDownloads();
  for (const pc of pcs) {
    try {
      job.files = await api(pc, "/api/direct", { method: "POST", body: JSON.stringify({ url }) });
      job.pc = pc;
      break;
    } catch (err) {
      job.error = err.message;
      if (err.status) break; // the PC answered with a real error (bad link, private post...): don't retry
    }
  }
  if (job.pc) {
    job.error = null;
    job.label = job.files[0].title || job.label;
    job.status = "done";
    job.files.forEach((f) => {
      const a = directLink(job.pc, f, "");
      document.body.append(a);
      a.click();
      a.remove();
    });
  } else {
    job.status = "error";
  }
  renderDownloads();
}

function directLink(pc, file, text) {
  const link = el("a", {
    className: "button small-button",
    href: mediaUrl(pc, `/api/direct/${encodeURIComponent(file.id)}`),
    download: file.filename,
    textContent: text,
  });
  link.addEventListener("click", savedToast);
  return link;
}

function statusRow(label, status, error) {
  return el("div", { className: "job-top" },
    el("span", { className: "job-url", textContent: label }),
    el("span", { className: error ? "error" : "muted", textContent: status }));
}

function renderPhoneJob(job) {
  const status = { preparing: "Getting video…", done: "Sent to phone", error: "Failed" }[job.status];
  const n = job.files.length;
  return el("li", { className: "job" },
    statusRow(job.label, status, job.status === "error"),
    job.status === "preparing" ? el("div", { className: "bar indeterminate" }, el("div")) : null,
    job.error ? el("p", { className: "error", textContent: job.error }) : null,
    job.status === "done"
      ? el("div", { className: "job-actions" },
          ...job.files.map((f, i) => directLink(job.pc, f, n > 1 ? `Save video ${i + 1} to phone` : "Save to phone")))
      : null);
}

function renderJob(pc, job) {
  const label = {
    queued: "Queued",
    downloading: job.progress != null ? `${job.progress}%` : "Downloading",
    done: "Done",
    error: "Failed",
  }[job.status];
  const status = pcs.length > 1 ? `${label} · ${pc.name}` : label;
  const bar = isActive(job)
    ? el("div", { className: job.progress == null ? "bar indeterminate" : "bar" },
        el("div", { style: job.progress == null ? "" : `width:${job.progress}%` }))
    : null;
  return el("li", { className: "job" },
    statusRow(job.title || job.url.replace("https://", ""), status, job.status === "error"),
    bar,
    job.error ? el("p", { className: "error", textContent: job.error }) : null,
    // On the PC the video is already in the library; on the phone, offer to copy it over.
    job.status === "done" && !isLocal
      ? el("div", { className: "job-actions" }, ...job.files.map((name, i) => saveLink(pc, name, i, job.files.length)))
      : null);
}

function saveLink(pc, name, i, count) {
  const link = el("a", {
    className: "button small-button",
    href: `${mediaUrl(pc, `/media/${encodeURIComponent(name)}`)}&download=true`,
    download: name,
    textContent: count > 1 ? `Save video ${i + 1} to phone` : "Save to phone",
  });
  link.addEventListener("click", savedToast);
  return link;
}

function savedToast() {
  toast("Saving to Downloads — it will appear in your gallery");
}

function renderDownloads() {
  const items = [...phoneJobs.map(renderPhoneJob), ...pcs.flatMap((pc) => pc.jobs.map((job) => renderJob(pc, job)))];
  $("#jobs-section").hidden = !items.length;
  $("#jobs").replaceChildren(...items);
}

$("#paste").addEventListener("click", async () => {
  try {
    $("#url").value = await navigator.clipboard.readText();
  } catch {
    toast("Clipboard not available, paste manually");
  }
});

$("#clear-jobs").addEventListener("click", async () => {
  phoneJobs = phoneJobs.filter((j) => j.status === "preparing");
  await Promise.all(pcs.map((pc) => api(pc, "/api/jobs", { method: "DELETE" }).catch(() => {})));
  refreshJobs();
});

async function refreshJobs() {
  clearTimeout(pollTimer);
  await Promise.all(pcs.map(async (pc) => {
    try {
      pc.jobs = await api(pc, "/api/jobs");
    } catch {
      return;
    }
    const newlyDone = pc.jobs.filter((j) => j.status === "done" && !knownDone.has(pc.url + j.id));
    newlyDone.forEach((j) => knownDone.add(pc.url + j.id));
    if (newlyDone.length) loadLibrary(pc);
  }));
  renderDownloads();
  if (pcs.some((pc) => pc.jobs.some(isActive))) pollTimer = setTimeout(refreshJobs, 1500);
}

// ---------------------------------------------------------------- libraries

async function loadLibrary(pc) {
  try {
    pc.videos = await api(pc, "/api/videos");
  } catch {
    return;
  }
  renderLibraries();
}

function renderLibraries() {
  $("#libraries").replaceChildren(...pcs.map(renderLibrary));
}

function renderLibrary(pc) {
  const n = pc.videos.length;
  const count = el("span", { className: "muted small", textContent: n ? `${n} video${n === 1 ? "" : "s"}` : "" });
  const videos = n ? el("div", { className: "grid" }, ...pc.videos.map((v) => renderTile(pc, v)))
    : el("p", { className: "muted", textContent: "No videos yet." });
  if (isLocal) {
    const openFolder = el("button", { className: "link", textContent: "Open folder" });
    openFolder.addEventListener("click", () =>
      api(pc, "/api/open-folder", { method: "POST" }).catch((err) => toast(err.message)));
    return el("section", {},
      el("div", { className: "section-head" }, el("h2", { textContent: "Library" }), count),
      el("div", { className: "folder" }, el("code", { className: "muted small", textContent: pc.me.library }), openFolder),
      videos);
  }
  // On the phone, each PC's library can be collapsed; the phone remembers which ones.
  const details = el("details", { className: "library", open: !store.get("collapsed", []).includes(pc.url) },
    el("summary", { className: "section-head" }, el("h2", { textContent: pc.name }), count),
    videos);
  details.addEventListener("toggle", () => {
    const collapsed = new Set(store.get("collapsed", []));
    if (details.open) collapsed.delete(pc.url);
    else collapsed.add(pc.url);
    store.set("collapsed", [...collapsed]);
  });
  return el("section", {}, details);
}

function renderTile(pc, v) {
  if (!v.thumb) wantThumb(pc, v);
  const duration = fmtDuration(v.duration);
  const tile = el("button", { className: "tile", type: "button" },
    el("div", { className: "thumb" },
      v.thumb ? el("img", { src: mediaUrl(pc, `/thumb/${encodeURIComponent(v.thumb)}`), alt: "", loading: "lazy" }) : null,
      duration ? el("span", { className: "duration", textContent: duration }) : null),
    el("div", { className: "tile-body" },
      el("p", { className: "tile-title", textContent: v.title }),
      el("p", { className: "tile-meta muted small",
        textContent: [v.uploader_id ? `@${v.uploader_id}` : v.uploader, fmtSize(v.size), fmtDate(v.added)].filter(Boolean).join(" · ") })));
  tile.addEventListener("click", () => openPlayer(pc, v));
  return tile;
}

// ---------------------------------------------------------------- thumbnails for videos without one
// yt-dlp only gets thumbnails for X videos. For uploads and files dropped into a library, the browser
// grabs a frame and sends it to the PC (so the PC doesn't need ffmpeg). One video at a time.

const thumbTried = new Set();
const thumbQueue = [];
let thumbBusy = false;

function grabFrame(src) {
  return new Promise((resolve, reject) => {
    const video = el("video", { muted: true, playsInline: true, preload: "auto", crossOrigin: "anonymous" });
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

function saveThumb(pc, name, frame) {
  const form = new FormData();
  form.append("file", frame.blob, "thumb.jpg");
  if (Number.isFinite(frame.duration)) form.append("duration", String(frame.duration));
  return api(pc, `/api/videos/${encodeURIComponent(name)}/thumb`, { method: "POST", body: form });
}

function wantThumb(pc, v) {
  const key = `${pc.url}/${v.name}`;
  if (thumbTried.has(key)) return;
  thumbTried.add(key);
  thumbQueue.push({ pc, v });
  nextThumb();
}

async function nextThumb() {
  if (thumbBusy || !thumbQueue.length) return;
  thumbBusy = true;
  const { pc, v } = thumbQueue.shift();
  try {
    const frame = await grabFrame(mediaUrl(pc, `/media/${encodeURIComponent(v.name)}`));
    Object.assign(v, await saveThumb(pc, v.name, frame));
    renderLibraries();
  } catch {
    // Not every video can be played in the browser; it just keeps the empty thumbnail.
  }
  thumbBusy = false;
  nextThumb();
}

// ---------------------------------------------------------------- player

function openPlayer(pc, v) {
  current = { pc, video: v };
  const src = mediaUrl(pc, `/media/${encodeURIComponent(v.name)}`);
  $("#video").src = src;
  $("#player-title").textContent = v.title;
  const where = isLocal ? null : pc.name;
  $("#player-meta").textContent = [v.uploader, fmtSize(v.size), fmtDate(v.added), where].filter(Boolean).join(" · ");
  $("#save").href = `${src}&download=true`;
  $("#save").setAttribute("download", v.name);
  $("#save").hidden = isLocal;
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

$("#save").addEventListener("click", savedToast);

$("#delete").addEventListener("click", async () => {
  if (!current) return;
  const { pc, video } = current;
  if (!confirm(`Delete "${video.title}" from ${isLocal ? "this PC" : pc.name}?`)) return;
  try {
    await api(pc, `/api/videos/${encodeURIComponent(video.name)}`, { method: "DELETE" });
  } catch (err) {
    return toast(err.message);
  }
  $("#player").close();
  loadLibrary(pc);
});

// ---------------------------------------------------------------- upload (phone -> PC)

$("#upload").addEventListener("change", () => {
  const file = $("#upload").files[0];
  $("#upload").value = "";
  if (!file) return;
  const pc = targetPc();
  const form = new FormData();
  form.append("file", file);
  const xhr = new XMLHttpRequest();
  xhr.open("POST", `${pc.url}/api/upload`);
  if (auth) xhr.setRequestHeader("Authorization", `Bearer ${auth.session}`);
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
    toast(isLocal ? "Uploaded" : `Uploaded to ${pc.name}`);
    // Make its thumbnail from the file still on this device, instead of streaming it back.
    const { name } = JSON.parse(xhr.responseText);
    thumbTried.add(`${pc.url}/${name}`);
    loadLibrary(pc);
    const local = URL.createObjectURL(file);
    grabFrame(local)
      .then((frame) => saveThumb(pc, name, frame))
      .then(() => loadLibrary(pc))
      .catch(() => {})
      .finally(() => URL.revokeObjectURL(local));
  };
  xhr.onerror = () => toast(`Upload failed — is ${pc.name} on?`);
  xhr.send(form);
});

// ---------------------------------------------------------------- add a phone / add a PC (PC only)

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

async function openPairing() {
  if (!$("#pair").open) $("#pair").showModal();
  $("#pair-qr").replaceChildren();
  $("#pair-status").textContent = "Creating a code…";
  clearInterval(pairTimer);
  let pairing;
  try {
    pairing = await api(pcs[0], "/api/pair", { method: "POST" });
  } catch (err) {
    $("#pair-status").textContent = err.message;
    return;
  }
  // The SVG comes from our own server (the qrcode library), not from user input.
  $("#pair-qr").innerHTML = pairing.svg;
  $("#pair-url").textContent = pairing.url;
  pairTimer = startCountdown(pairing.expires_in,
    (left) => ($("#pair-status").textContent = `One-time code, expires in ${fmtCountdown(left)}.`),
    () => {
      $("#pair-qr").replaceChildren();
      $("#pair-status").textContent = "This code expired. Tap New code.";
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
    result = await api(pcs[0], "/api/pc-code", { method: "POST" });
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

// ---------------------------------------------------------------- install (phone)

let installPrompt = null;

function isInstalled() {
  return matchMedia("(display-mode: standalone)").matches;
}

window.addEventListener("beforeinstallprompt", (e) => {
  e.preventDefault();
  installPrompt = e;
});

$("#install").addEventListener("click", async () => {
  if (!installPrompt) {
    $("#install-manual").hidden = false; // Chrome didn't offer its prompt; point to the menu instead
    return;
  }
  installPrompt.prompt();
  const { outcome } = await installPrompt.userChoice;
  installPrompt = null;
  if (outcome === "accepted") $("#install-hint").hidden = true;
});

window.addEventListener("appinstalled", () => ($("#install-hint").hidden = true));

// ---------------------------------------------------------------- sharing from X

// The installed app opens /share?title=&text=&url= (the service worker answers it even when the home PC
// is off); before installing, the server redirects that to /?share=<link>.
function sharedLink() {
  const params = new URLSearchParams(location.search);
  if (params.has("share")) return params.get("share");
  if (location.pathname !== "/share") return null;
  const text = ["url", "text", "title"].map((k) => params.get(k) ?? "").join(" ");
  return text.match(/https?:\/\/(?:www\.|mobile\.)?(?:x|twitter)\.com\/\S+?\/status\/\d+/i)?.[0] ?? "";
}

function handleShare(link) {
  if (!link) toast("No X link found in what you shared");
  else if (isLocal) queue(link);
  else saveToPhone(link);
}

// ---------------------------------------------------------------- start

if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js");

async function start() {
  const hash = new URLSearchParams(location.hash.slice(1));
  const shared = sharedLink();
  history.replaceState(null, "", "/");
  if (hash.get("pair")) await redeemPairing(hash.get("pair"), hash.get("pc") || location.origin);
  if (!(await connect())) return;
  showApp();
  if (isLocal && hash.has("add-phone")) openPairing();
  if (shared !== null) handleShare(shared);
}

// Coming back to the app later: the PCs that are on may have changed.
document.addEventListener("visibilitychange", async () => {
  if (document.visibilityState === "visible" && !$("#app").hidden && Date.now() - connectedAt > 60000) {
    if (await connect()) showApp();
  }
});

start();
