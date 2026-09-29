// Keeps a copy of the app on the phone, so it opens (and receives shares from X) even when the home
// PC it was installed from is off; the app then talks to whichever PCs are on. The copy is refreshed
// in the background whenever the home PC answers. Videos and API calls always go to the network.
const CACHE = "xvid-v2";
const SHELL = ["/", "/static/app.js", "/static/style.css", "/manifest.webmanifest",
  "/static/icon-192.png", "/static/icon-512.png"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) if (key !== CACHE) await caches.delete(key);
    await self.clients.claim();
  })());
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET" || url.origin !== location.origin) return;
  // The app page for "/" and for shares ("/share?text=..."); app.js reads the shared link itself.
  const isPage = event.request.mode === "navigate" && (url.pathname === "/" || url.pathname === "/share");
  if (!isPage && !SHELL.includes(url.pathname)) return;
  const path = isPage ? "/" : url.pathname;
  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    const refresh = fetch(path, { cache: "no-store" })
      .then((res) => (res.ok ? cache.put(path, res.clone()).then(() => res) : res));
    const cached = await cache.match(path);
    if (cached) {
      event.waitUntil(refresh.catch(() => {}));
      return cached;
    }
    return refresh;
  })());
});
