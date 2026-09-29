// Minimal service worker: makes the app installable. Everything is fetched live
// from the PC, since the library lives there.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));
