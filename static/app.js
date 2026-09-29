const $ = (sel) => document.querySelector(sel);

let pendingShare = new URLSearchParams(location.search).get("share");
let pollTimer = null;
let knownDone = new Set();
let current = null; // video open in the player

// ---------------------------------------------------------------- helpers

async function api(path, opts = {}) {
  const headers = typeof opts.body === "string" ? { "Content-Type": "application/json" } : {};
  const res = await fetch(path, { ...opts, headers });
  if (res.status === 401 && path !== "/api/login" && path !== "/api/pair/redeem") {
    showLogin();
    throw new Error("Not logged in");
  }
  if (!res.ok) {
    let msg = res.statusText;
    try { msg = (await res.json()).detail || msg; } catch {}
    throw new Error(msg);
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

// ---------------------------------------------------------------- session

function showLogin() {
  $("#boot").hidden = true;
  $("#app").hidden = true;
  $("#login").hidden = false;
  $("#token").focus();
}

async function showApp() {
  $("#boot").hidden = true;
  $("#login").hidden = true;
  $("#app").hidden = false;
  const me = await api("/api/me");
  const warnings = [];
  if (!me.cookies) warnings.push("No X cookies configured: sensitive, protected and subscriber-only posts will fail.");
  if (!me.ffmpeg) warnings.push("ffmpeg not found: some videos may download in lower quality.");
  $("#warning").textContent = warnings.join(" ");
  $("#warning").hidden = !warnings.length;
  $("#library-path").textContent = me.library;
  $("#open-folder").hidden = !me.local;
  $("#add-phone").hidden = !me.local;
  $("#install-hint").hidden = me.local || isInstalled();
  if (me.local && location.hash === "#add-phone") {
    history.replaceState(null, "", "/");
    openPairing();
  }

  loadLibrary();
  refreshJobs();
  if (pendingShare !== null) {
    const shared = pendingShare;
    pendingShare = null;
    history.replaceState(null, "", "/");
    if (shared) queue(shared);
    else toast("No X link found in what you shared");
  }
}

$("#login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  try {
    await api("/api/login", { method: "POST", body: JSON.stringify({ token: $("#token").value }) });
    $("#login-error").hidden = true;
    showApp();
  } catch (err) {
    $("#login-error").textContent = err.message;
    $("#login-error").hidden = false;
  }
});

// ---------------------------------------------------------------- downloads

async function queue(url) {
  try {
    await api("/api/jobs", { method: "POST", body: JSON.stringify({ url }) });
    toast("Downloading on the PC…");
    refreshJobs();
  } catch (err) {
    toast(err.message);
  }
}

$("#add-form").addEventListener("submit", (e) => {
  e.preventDefault();
  const url = $("#url").value.trim();
  if (!url) return;
  $("#url").value = "";
  queue(url);
});

$("#paste").addEventListener("click", async () => {
  try {
    $("#url").value = await navigator.clipboard.readText();
  } catch {
    toast("Clipboard not available, paste manually");
  }
});

$("#clear-jobs").addEventListener("click", async () => {
  await api("/api/jobs", { method: "DELETE" });
  refreshJobs();
});

async function refreshJobs() {
  clearTimeout(pollTimer);
  const jobs = await api("/api/jobs");
  $("#jobs-section").hidden = !jobs.length;
  $("#jobs").replaceChildren(...jobs.map(renderJob));

  const newlyDone = jobs.filter((j) => j.status === "done" && !knownDone.has(j.id));
  newlyDone.forEach((j) => knownDone.add(j.id));
  if (newlyDone.length) loadLibrary();

  if (jobs.some((j) => j.status === "queued" || j.status === "downloading")) {
    pollTimer = setTimeout(refreshJobs, 1500);
  }
}

function renderJob(job) {
  const label = {
    queued: "Queued",
    downloading: job.progress != null ? `${job.progress}%` : "Downloading",
    done: "Done",
    error: "Failed",
  }[job.status];
  const active = job.status === "queued" || job.status === "downloading";
  const bar = active
    ? el("div", { className: job.progress == null ? "bar indeterminate" : "bar" },
        el("div", { style: job.progress == null ? "" : `width:${job.progress}%` }))
    : null;
  return el("li", { className: "job" },
    el("div", { className: "job-top" },
      el("span", { className: "job-url", textContent: job.title || job.url.replace("https://", "") }),
      el("span", { className: job.status === "error" ? "error" : "muted", textContent: label })),
    bar,
    job.error ? el("p", { className: "error", textContent: job.error }) : null,
    job.status === "done" ? el("div", { className: "job-actions" }, ...job.files.map(saveLink)) : null);
}

function saveLink(name, i, files) {
  const link = el("a", {
    className: "button small-button",
    href: `/media/${encodeURIComponent(name)}?download=true`,
    download: name,
    textContent: files.length > 1 ? `Save video ${i + 1} to phone` : "Save to phone",
  });
  link.addEventListener("click", savedToast);
  return link;
}

function savedToast() {
  toast("Saving to Downloads — it will appear in your gallery");
}

// ---------------------------------------------------------------- library

$("#open-folder").addEventListener("click", () =>
  api("/api/open-folder", { method: "POST" }).catch((err) => toast(err.message)));

async function loadLibrary() {
  const videos = await api("/api/videos");
  $("#count").textContent = videos.length ? `${videos.length} video${videos.length === 1 ? "" : "s"}` : "";
  $("#empty").hidden = videos.length > 0;
  $("#grid").replaceChildren(...videos.map(renderTile));
}

function renderTile(v) {
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

// ---------------------------------------------------------------- player

function openPlayer(v) {
  current = v;
  const src = `/media/${encodeURIComponent(v.name)}`;
  $("#video").src = src;
  $("#player-title").textContent = v.title;
  $("#player-meta").textContent = [v.uploader, fmtSize(v.size), fmtDate(v.added)].filter(Boolean).join(" · ");
  $("#save").href = `${src}?download=true`;
  $("#save").setAttribute("download", v.name);
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
  if (!current || !confirm(`Delete "${current.title}" from the PC?`)) return;
  await api(`/api/videos/${encodeURIComponent(current.name)}`, { method: "DELETE" });
  $("#player").close();
  loadLibrary();
});

// ---------------------------------------------------------------- upload (phone -> PC)

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
    toast("Uploaded to the PC");
    loadLibrary();
  };
  xhr.onerror = () => toast("Upload failed — is the PC on?");
  xhr.send(form);
});

// ---------------------------------------------------------------- add a phone (PC only)

let pairTimer;

async function openPairing() {
  if (!$("#pair").open) $("#pair").showModal();
  $("#pair-qr").replaceChildren();
  $("#pair-status").textContent = "Creating a code…";
  clearInterval(pairTimer);
  let pairing;
  try {
    pairing = await api("/api/pair", { method: "POST" });
  } catch (err) {
    $("#pair-status").textContent = err.message;
    return;
  }
  // The SVG comes from our own server (the qrcode library), not from user input.
  $("#pair-qr").innerHTML = pairing.svg;
  $("#pair-url").textContent = pairing.url;
  const expires = Date.now() + pairing.expires_in * 1000;
  const tick = () => {
    const left = Math.round((expires - Date.now()) / 1000);
    if (left <= 0) {
      clearInterval(pairTimer);
      $("#pair-qr").replaceChildren();
      $("#pair-status").textContent = "This code expired. Tap New code.";
      return;
    }
    $("#pair-status").textContent = `One-time code, expires in ${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}.`;
  };
  tick();
  pairTimer = setInterval(tick, 1000);
}

$("#add-phone").addEventListener("click", openPairing);
$("#pair-new").addEventListener("click", openPairing);
$("#pair-close").addEventListener("click", () => $("#pair").close());
$("#pair").addEventListener("close", () => clearInterval(pairTimer));

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

// ---------------------------------------------------------------- start

if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js");

async function start() {
  const code = new URLSearchParams(location.hash.slice(1)).get("pair");
  if (code) {
    history.replaceState(null, "", "/");
    try {
      await api("/api/pair/redeem", { method: "POST", body: JSON.stringify({ code }) });
      toast("Phone connected to XVid");
    } catch (err) {
      toast(err.message);
    }
  }
  try {
    await api("/api/me");
  } catch (err) {
    if (err.message !== "Not logged in") {
      $("#boot-message").textContent =
        `Can't reach the PC (${err.message}). Check that XVid is running on the PC and you're on the same Wi-Fi, then reload.`;
    }
    return;
  }
  showApp();
}

start();
