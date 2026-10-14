/* Car Tracking – webový prehľad. Vanilla JS + Bootstrap 5.3 + Chart.js 4 + Leaflet 1.9 */
"use strict";

// =====================================================================
//  Pomocné funkcie a stav
// =====================================================================
const LS = {
  get(k, d) { try { return localStorage.getItem(k) ?? sessionStorage.getItem(k) ?? d; } catch { return d; } },
  set(k, v, persist = true) { try { (persist ? localStorage : sessionStorage).setItem(k, v); } catch {} },
  del(k) { try { localStorage.removeItem(k); sessionStorage.removeItem(k); } catch {} },
};
const S = {
  api: LS.get("ct_api", new URL("../api/", location.href).href),
  token: LS.get("ct_token", null),
  user: (() => { try { return JSON.parse(LS.get("ct_user", "null")); } catch { return null; } })(),
  period: LS.get("ct_period", "month"), offset: 0, vehicle: LS.get("ct_vehicle", ""),
  range: null, trips: [], prevTrips: [], vehicles: [], vmap: {}, items: { place: [], refuel: [], reminder: [] },
  settings: {}, page: "prehlad", loadedKey: "",
};
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const nf = (v, d = 1) => Number(v || 0).toLocaleString("sk-SK", { minimumFractionDigits: d, maximumFractionDigits: d });
const int = (v) => Math.round(Number(v || 0)).toLocaleString("sk-SK");
const km = (m, d = 1) => nf(m / 1000, d) + " km";
const eur = (v) => nf(v, 2) + " €";
const dt = (ts) => new Date(+ts).toLocaleDateString("sk-SK", { day: "numeric", month: "numeric", year: "numeric" });
const dtShort = (ts) => new Date(+ts).toLocaleDateString("sk-SK", { day: "numeric", month: "numeric" });
const tm = (ts) => new Date(+ts).toLocaleTimeString("sk-SK", { hour: "2-digit", minute: "2-digit" });
const dayName = (ts) => new Date(+ts).toLocaleDateString("sk-SK", { weekday: "long", day: "numeric", month: "long", year: "numeric" });
const dur = (ms) => { const m = Math.round(ms / 60000); return m < 60 ? m + " min" : Math.floor(m / 60) + " h " + (m % 60) + " min"; };
const durH = (ms) => nf(ms / 3.6e6, 1) + " h";
const sod = (ts) => { const d = new Date(+ts); d.setHours(0, 0, 0, 0); return +d; };
const DAY = 864e5;
const tripMs = (t) => (t.ended_at || t.started_at) - t.started_at;
const isWalk = (t) => t.type === "walk";
const placeShort = (p, a) => p || (a ? a.split(",").slice(0, 2).join(",").trim() : "—");
const css = (v) => getComputedStyle(document.documentElement).getPropertyValue(v).trim();

let toastObj;
function toast(msg) { $("toastBody").textContent = msg; (toastObj ||= new bootstrap.Toast($("toast"), { delay: 3500 })).show(); }
function loading(on) { $("loading").classList.toggle("d-none", !on); }

// =====================================================================
//  API
// =====================================================================
async function api(path, opt = {}) {
  const base = S.api.endsWith("/") ? S.api : S.api + "/";
  const res = await fetch(base + path, {
    ...opt, headers: { "Content-Type": "application/json", ...(S.token ? { Authorization: "Bearer " + S.token } : {}), ...(opt.headers || {}) },
  });
  let data = {};
  try { data = await res.json(); } catch {}
  if (res.status === 401 && S.token) { logout(); throw new Error("Prihlásenie vypršalo"); }
  if (!res.ok) throw new Error(data.error || ("Chyba servera " + res.status));
  return data;
}
const normTrip = (x) => ({ ...x, started_at: +x.started_at, ended_at: x.ended_at ? +x.ended_at : null, distance_m: +x.distance_m || 0,
  max_speed_kmh: +x.max_speed_kmh || 0, avg_speed_kmh: +x.avg_speed_kmh || 0, steps: +x.steps || 0,
  score: x.score == null ? null : +x.score, hard_brakes: +x.hard_brakes || 0, hard_accels: +x.hard_accels || 0, speeding_sec: +x.speeding_sec || 0,
  start_lat: x.start_lat == null ? null : +x.start_lat, start_lng: x.start_lng == null ? null : +x.start_lng,
  end_lat: x.end_lat == null ? null : +x.end_lat, end_lng: x.end_lng == null ? null : +x.end_lng });
async function fetchTrips(from, to, type) {
  const out = []; let offset = 0;
  while (true) {
    const r = await api(`trips?from=${from}&to=${to}${type ? "&type=" + type : ""}&limit=2000&offset=${offset}`);
    out.push(...r.trips.map(normTrip));
    if (r.trips.length < 2000) break; offset += 2000;
  }
  return out.sort((a, b) => a.started_at - b.started_at);
}
const pointCache = {};
function points(uuid) {
  return pointCache[uuid] ||= api("trips/" + uuid).then(r => r.points.map(p => ({ ts: +p.ts, lat: +p.lat, lng: +p.lng,
    speed: p.speed_kmh == null ? null : +p.speed_kmh, event: p.event })));
}

// =====================================================================
//  Prihlásenie / odhlásenie / téma
// =====================================================================
$("apiUrl").value = S.api;
$("loginForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const err = $("loginErr"); err.classList.add("d-none");
  S.api = $("apiUrl").value.trim() || S.api;
  if (!/^https:\/\//.test(S.api) && !/^https?:\/\/(localhost|127\.)/.test(S.api)) { err.textContent = "Adresa API musí začínať https://"; err.classList.remove("d-none"); return; }
  try {
    const r = await api("login", { method: "POST", body: JSON.stringify({ email: $("em").value, password: $("pw").value, device_name: "Web" }) });
    const keep = $("remember").checked;
    S.token = r.token; S.user = r.user;
    LS.set("ct_api", S.api); LS.set("ct_token", r.token, keep); LS.set("ct_user", JSON.stringify(r.user), keep);
    start();
  } catch (ex) { err.textContent = ex.message; err.classList.remove("d-none"); }
});
function logout() {
  if (S.token) fetch((S.api.endsWith("/") ? S.api : S.api + "/") + "logout", { method: "POST", headers: { Authorization: "Bearer " + S.token } }).catch(() => {});
  S.token = null; LS.del("ct_token"); LS.del("ct_user");
  $("app").classList.add("d-none"); $("login").classList.remove("d-none");
}
$("logoutBtn").onclick = logout;
function setTheme(t, rerender = true) {
  document.documentElement.dataset.bsTheme = t; LS.set("ct_theme", t);
  $("themeBtn").innerHTML = t === "dark" ? '<i class="bi bi-sun"></i>' : '<i class="bi bi-moon-stars"></i>';
  if (rerender && S.token && !$("app").classList.contains("d-none")) render();
}
$("themeBtn").onclick = () => setTheme(document.documentElement.dataset.bsTheme === "dark" ? "light" : "dark");

// =====================================================================
//  Obdobie
// =====================================================================
function computeRange(period, offset) {
  const d = new Date(); d.setHours(0, 0, 0, 0);
  let from, to, title;
  if (period === "week") {
    d.setDate(d.getDate() - (d.getDay() + 6) % 7 + 7 * offset);
    from = new Date(d); to = new Date(d); to.setDate(to.getDate() + 7);
    title = dtShort(from) + " – " + dt(+to - 1);
  } else if (period === "month") {
    from = new Date(d.getFullYear(), d.getMonth() + offset, 1); to = new Date(from.getFullYear(), from.getMonth() + 1, 1);
    title = from.toLocaleDateString("sk-SK", { month: "long", year: "numeric" });
  } else {
    from = new Date(d.getFullYear() + offset, 0, 1); to = new Date(from.getFullYear() + 1, 0, 1);
    title = "Rok " + from.getFullYear();
  }
  return { from: +from, to: +to, title, period };
}
function buckets(r) {
  const out = [];
  if (r.period === "year") {
    const y = new Date(r.from).getFullYear();
    for (let m = 0; m < 12; m++) out.push({ label: new Date(y, m, 1).toLocaleDateString("sk-SK", { month: "short" }), a: +new Date(y, m, 1), z: +new Date(y, m + 1, 1) });
  } else {
    for (let a = new Date(r.from); +a < r.to; a.setDate(a.getDate() + 1)) {
      const b = new Date(a); b.setDate(b.getDate() + 1);
      out.push({ label: r.period === "week" ? a.toLocaleDateString("sk-SK", { weekday: "short" }) : String(a.getDate()), a: +a, z: +b });
    }
  }
  return out;
}

// =====================================================================
//  Načítanie údajov
// =====================================================================
async function loadBase() {
  const [v, items, st] = await Promise.all([
    api("vehicles"), api("items").catch(() => ({ items: [] })), api("settings").catch(() => ({ settings: null })),
  ]);
  S.vehicles = v.vehicles.map(x => ({ ...x, consumption: +x.consumption, fuel_price: +x.fuel_price, rate_per_km: +x.rate_per_km || 0 }));
  S.vmap = Object.fromEntries(S.vehicles.map(x => [x.uuid, x]));
  S.items = { place: [], refuel: [], reminder: [] };
  items.items.filter(i => !+i.deleted && i.data).forEach(i => (S.items[i.kind] ||= []).push({ uuid: i.uuid, ...i.data }));
  S.settings = st.settings || {};
  fillVehicles();
}
async function loadPeriod(force) {
  S.range = computeRange(S.period, S.offset);
  const key = S.range.from + "-" + S.range.to;
  $("periodTitle").textContent = S.range.title;
  $("next").disabled = S.offset >= 0;
  if (!force && key === S.loadedKey) return;
  loading(true);
  try {
    const span = S.range.to - S.range.from;
    const prev = computeRange(S.period, S.offset - 1);
    const [cur, prv] = await Promise.all([fetchTrips(S.range.from, S.range.to), fetchTrips(prev.from, prev.from + span)]);
    S.trips = cur; S.prevTrips = prv; S.loadedKey = key;
  } finally { loading(false); }
}
function fillVehicles() {
  $("vehicleSel").innerHTML = '<option value="">Všetky vozidlá</option>' + S.vehicles.filter(v => !+v.archived)
    .map(v => `<option value="${v.uuid}" ${v.uuid === S.vehicle ? "selected" : ""}>${esc(v.name)}${v.plate ? " · " + esc(v.plate) : ""}${v.is_owner ? "" : " 👥"}</option>`).join("");
}
const carTrips = (list = S.trips) => list.filter(t => !isWalk(t) && (!S.vehicle || t.vehicle_uuid === S.vehicle));
const walkTrips = (list = S.trips) => list.filter(isWalk);
const veh = (t) => S.vmap[t.vehicle_uuid];
const liters = (t) => { const v = veh(t); return v ? t.distance_m / 1000 / 100 * v.consumption : 0; };
const cost = (t) => { const v = veh(t); return v ? liters(t) * v.fuel_price : 0; };
const sum = (arr, f) => arr.reduce((s, x) => s + f(x), 0);

// =====================================================================
//  Grafy (Chart.js) a mapy (Leaflet)
// =====================================================================
const charts = {};
function chart(id, config) {
  if (charts[id]) { charts[id].destroy(); delete charts[id]; }
  const el = $(id); if (!el || typeof Chart === "undefined") return null;
  const dark = document.documentElement.dataset.bsTheme === "dark";
  Chart.defaults.font.family = "Inter, system-ui, sans-serif";
  Chart.defaults.color = dark ? "#94a3b8" : "#64748b";
  Chart.defaults.borderColor = dark ? "rgba(148,163,184,.14)" : "rgba(100,116,139,.15)";
  config.options = Object.assign({ responsive: true, maintainAspectRatio: false, plugins: { legend: { labels: { boxWidth: 10, boxHeight: 10, usePointStyle: true } } } }, config.options || {});
  return (charts[id] = new Chart(el, config));
}
const C = { car: "#16a34a", walk: "#2563eb", biz: "#7c3aed", priv: "#94a3b8", amber: "#f59e0b", rose: "#e11d48", cyan: "#06b6d4" };
const PALETTE = ["#2563eb", "#16a34a", "#f59e0b", "#7c3aed", "#e11d48", "#06b6d4", "#84cc16", "#f97316"];
const SPEED_STOPS = [[0, "#2e7d32"], [30, "#43a047"], [50, "#c0ca33"], [70, "#fdd835"], [90, "#fb8c00"], [110, "#e53935"], [130, "#b71c1c"], [150, "#6a1b9a"]];
function speedColor(v) {
  for (let i = 1; i < SPEED_STOPS.length; i++) if (v <= SPEED_STOPS[i][0]) {
    const [s0, c0] = SPEED_STOPS[i - 1], [s1, c1] = SPEED_STOPS[i], t = (v - s0) / (s1 - s0);
    const p = (c, k) => parseInt(c.substr(1 + k * 2, 2), 16);
    return "#" + [0, 1, 2].map(k => Math.round(p(c0, k) + (p(c1, k) - p(c0, k)) * t).toString(16).padStart(2, "0")).join("");
  }
  return SPEED_STOPS[SPEED_STOPS.length - 1][1];
}
const maps = {};
function makeMap(id) {
  if (maps[id]) { maps[id].remove(); delete maps[id]; }
  if (typeof L === "undefined" || !$(id)) return null;
  const m = L.map(id, { zoomControl: true, attributionControl: true }).setView([48.67, 19.7], 7);
  const dark = document.documentElement.dataset.bsTheme === "dark";
  const base = dark ? "Canvas/World_Dark_Gray_Base" : "World_Street_Map";
  L.tileLayer(`https://server.arcgisonline.com/ArcGIS/rest/services/${base}/MapServer/tile/{z}/{y}/{x}`,
    { maxZoom: 19, maxNativeZoom: dark ? 16 : 18, attribution: "© Esri, HERE, Garmin, OpenStreetMap" }).addTo(m);
  if (dark) L.tileLayer("https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}", { maxZoom: 19, maxNativeZoom: 16 }).addTo(m);
  maps[id] = m;
  setTimeout(() => m.invalidateSize(), 60);
  return m;
}
function pin(lat, lng, color, text) {
  return L.marker([lat, lng], { icon: L.divIcon({ className: "", iconSize: [26, 26], iconAnchor: [13, 13],
    html: `<div style="background:${color};color:#fff;border:2px solid #fff;border-radius:50%;width:26px;height:26px;display:grid;place-items:center;font:700 12px Inter,system-ui;box-shadow:0 2px 6px #0005">${text}</div>` }) });
}
function drawRoute(layer, P, byspeed, color) {
  if (P.length < 2) return;
  if (byspeed) {
    for (let i = 1; i < P.length; i++) L.polyline([[P[i - 1].lat, P[i - 1].lng], [P[i].lat, P[i].lng]], { color: speedColor(P[i].speed ?? 0), weight: 5, opacity: .95 }).addTo(layer);
  } else L.polyline(P.map(p => [p.lat, p.lng]), { color, weight: 4, opacity: .85 }).addTo(layer);
}

// =====================================================================
//  Komponenty
// =====================================================================
function kpi(icon, cls, label, value, cur, prev, invert) {
  let tr = "";
  if (prev !== undefined && prev !== null) {
    if (prev > 0) {
      const p = Math.round((cur - prev) / prev * 100);
      const good = invert ? p < 0 : p > 0;
      tr = `<span class="trend ${p === 0 ? "flat" : good ? "up" : "down"}" title="oproti predchádzajúcemu obdobiu"><i class="bi bi-arrow-${p >= 0 ? "up" : "down"}-right"></i>${Math.abs(p)} %<span class="opacity-75 fw-normal ms-1">vs. predtým</span></span>`;
    } else if (cur > 0) tr = `<span class="trend flat">nové</span>`;
  }
  return `<div class="col-6 col-lg-4 kpi-col"><div class="ct-card kpi"><div class="ico ${cls}"><i class="bi bi-${icon}"></i></div>
    <div class="min-w-0"><div class="lbl">${label}</div><div class="val">${value}</div>${tr}</div></div></div>`;
}
function card(title, body, opts = {}) {
  return `<div class="${opts.col || "col-12"}"><div class="ct-card">
    <div class="ct-head"><h2>${title}</h2>${opts.sub ? `<span class="sub">· ${opts.sub}</span>` : ""}<div class="ms-auto d-flex gap-1 noprint">${opts.tools || ""}</div></div>
    <div class="ct-body">${body}</div></div></div>`;
}
const empty = (icon, text) => `<div class="empty-state"><i class="bi bi-${icon}"></i>${text}</div>`;
const typeBadge = (t) => isWalk(t) ? '<span class="badge badge-walk rounded-pill"><i class="bi bi-person-walking"></i> chôdza</span>'
  : t.category === "business" ? '<span class="badge badge-biz rounded-pill">služobná</span>' : '<span class="badge badge-priv rounded-pill">súkromná</span>';
function printHead(title) {
  const v = S.vmap[S.vehicle];
  return `<div class="print-head"><h2 class="h4 fw-bold mb-1">${esc(title)}</h2><div>Obdobie: <b>${esc(S.range.title)}</b> · ${esc(S.user?.name || "")}${v ? " · Vozidlo: <b>" + esc(v.name) + "</b> " + esc(v.plate || "") : ""}</div></div>`;
}
function csvDownload(name, rows) {
  const text = "﻿" + rows.map(r => r.map(c => typeof c === "number" ? String(c).replace(".", ",") : '"' + String(c ?? "").replace(/"/g, '""') + '"').join(";")).join("\r\n");
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([text], { type: "text/csv;charset=utf-8" }));
  a.download = name.replace(/\s+/g, "-"); a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 3000);
}
function modal(title, html) {
  $("modalTitle").innerHTML = title; $("modalBody").innerHTML = html;
  const m = bootstrap.Modal.getOrCreateInstance($("modal")); m.show(); return m;
}

// =====================================================================
//  Smerovanie
// =====================================================================
const PAGES = {
  prehlad: ["Dashboard", pageDashboard], jazdy: ["Jazdy a chôdza", pageTrips], mapa: ["Mapa", pageMap],
  den: ["Časová os dňa", pageDay], kniha: ["Kniha jázd", pageLogbook], nahrady: ["Cestovné náhrady", pageReimb],
  vozidla: ["Vozidlá a tankovanie", pageVehicles], trasy: ["Časté trasy", pageRoutes], chodza: ["Chôdza a kroky", pageWalk],
  miesta: ["Miesta", pagePlaces], ucet: ["Účet a server", pageAccount],
};
function route() {
  const p = (location.hash.replace(/^#\/?/, "").split("?")[0]) || "prehlad";
  S.page = PAGES[p] ? p : "prehlad";
  document.querySelectorAll("#nav .nav-link").forEach(a => a.classList.toggle("active", a.dataset.page === S.page));
  $("pageTitle").textContent = PAGES[S.page][0];
  $("periodBar").classList.toggle("d-none", ["den", "ucet", "trasy"].includes(S.page));
  bootstrap.Offcanvas.getInstance($("sidebar"))?.hide();
  render();
}
async function render() {
  Object.keys(charts).forEach(k => { charts[k].destroy(); delete charts[k]; });
  try {
    if (!["den", "ucet", "trasy"].includes(S.page)) await loadPeriod();
    await PAGES[S.page][1]();
  } catch (e) { $("content").innerHTML = `<div class="alert alert-danger">${esc(e.message)}</div>`; }
}
window.addEventListener("hashchange", route);

// =====================================================================
//  Dashboard
// =====================================================================
async function pageDashboard() {
  const cars = carTrips(), pcars = carTrips(S.prevTrips), walks = walkTrips(), pwalks = walkTrips(S.prevTrips);
  const kmCur = sum(cars, t => t.distance_m), kmPrev = sum(pcars, t => t.distance_m);
  const biz = sum(cars.filter(t => t.category === "business"), t => t.distance_m);
  const steps = sum(walks, t => t.steps), pSteps = sum(pwalks, t => t.steps);
  const time = sum(cars, tripMs), pTime = sum(pcars, tripMs);
  const costs = sum(cars, cost), pCosts = sum(pcars, cost);
  const reminders = S.items.reminder.filter(r => !r.done);
  const b = buckets(S.range);

  $("content").innerHTML = printHead("Prehľad") + `<div class="row g-3">
    ${kpi("speedometer", "ico-green", "Najazdené autom", km(kmCur), kmCur, kmPrev)}
    ${kpi("signpost-2", "ico-blue", "Počet jázd", int(cars.length), cars.length, pcars.length)}
    ${kpi("stopwatch", "ico-cyan", "Za volantom", durH(time), time, pTime)}
    ${kpi("fuel-pump", "ico-amber", "Náklady na palivo", eur(costs), costs, pCosts, true)}
    ${kpi("briefcase", "ico-violet", "Služobne", km(biz), biz, sum(pcars.filter(t => t.category === "business"), t => t.distance_m))}
    ${kpi("person-walking", "ico-rose", "Kroky", int(steps), steps, pSteps)}

    ${card("Kilometre v čase", `<div class="chart-box lg"><canvas id="chKm"></canvas></div>`, { col: "col-xl-8", sub: "služobné / súkromné" })}
    ${card("Podľa vozidiel", S.vehicles.length ? `<div class="chart-box lg"><canvas id="chVeh"></canvas></div>` : empty("car-front", "Žiadne vozidlá"), { col: "col-xl-4" })}

    ${card("Kedy jazdíte", `<div class="chart-box"><canvas id="chHour"></canvas></div>`, { col: "col-lg-6", sub: "začiatky jázd podľa hodiny" })}
    ${card("Dni v týždni", `<div class="chart-box"><canvas id="chDow"></canvas></div>`, { col: "col-lg-6", sub: "km a kroky" })}

    ${card("Mapa obdobia", `<div class="map-box" id="dashMap"></div><div class="small text-body-secondary mt-2">Posledných 30 jázd obdobia · <a href="#/mapa">celá mapa</a></div>`, { col: "col-xl-7" })}
    ${card("Posledné záznamy", recentList(S.trips.slice(-8).reverse()), { col: "col-xl-5", tools: '<a class="btn btn-sm btn-outline-secondary" href="#/jazdy">Všetky</a>' })}

    ${card("Štýl jazdy", styleBlock(cars), { col: "col-md-6 col-xl-4" })}
    ${card("Rekordy obdobia", recordsBlock(cars, walks), { col: "col-md-6 col-xl-4" })}
    ${card("Pripomienky", remindersBlock(reminders), { col: "col-xl-4", tools: '<a class="btn btn-sm btn-outline-secondary" href="#/vozidla">Vozidlá</a>' })}
  </div>`;
  bindTripLinks();

  // km v čase – služobné / súkromné, kroky ako čiara
  chart("chKm", { type: "bar", data: { labels: b.map(x => x.label), datasets: [
      { label: "Služobné km", data: b.map(x => sum(cars.filter(t => t.category === "business" && t.started_at >= x.a && t.started_at < x.z), t => t.distance_m) / 1000), backgroundColor: C.biz, borderRadius: 6, stack: "km" },
      { label: "Súkromné km", data: b.map(x => sum(cars.filter(t => t.category !== "business" && t.started_at >= x.a && t.started_at < x.z), t => t.distance_m) / 1000), backgroundColor: C.car, borderRadius: 6, stack: "km" },
      { label: "Kroky (tis.)", type: "line", data: b.map(x => sum(walks.filter(t => t.started_at >= x.a && t.started_at < x.z), t => t.steps) / 1000), borderColor: C.walk, backgroundColor: C.walk, tension: .35, pointRadius: 0, yAxisID: "y2" },
    ] },
    options: { interaction: { mode: "index", intersect: false }, scales: { x: { stacked: true, grid: { display: false } }, y: { stacked: true, title: { display: true, text: "km" } },
      y2: { position: "right", grid: { display: false }, title: { display: true, text: "tis. krokov" } } } } });
  // vozidlá
  const byV = {}; cars.forEach(t => { const k = veh(t)?.name || "Nepriradené"; byV[k] = (byV[k] || 0) + t.distance_m / 1000; });
  chart("chVeh", { type: "doughnut", data: { labels: Object.keys(byV), datasets: [{ data: Object.values(byV).map(v => +v.toFixed(1)), backgroundColor: PALETTE, borderWidth: 0 }] },
    options: { cutout: "68%", plugins: { legend: { position: "bottom" }, tooltip: { callbacks: { label: (c) => ` ${c.label}: ${nf(c.parsed, 1)} km` } } } } });
  // hodiny
  const hours = Array(24).fill(0), hoursW = Array(24).fill(0);
  cars.forEach(t => hours[new Date(t.started_at).getHours()]++); walks.forEach(t => hoursW[new Date(t.started_at).getHours()]++);
  chart("chHour", { type: "bar", data: { labels: hours.map((_, h) => h + ":00"), datasets: [
    { label: "Jazdy", data: hours, backgroundColor: C.car, borderRadius: 4 }, { label: "Chôdza", data: hoursW, backgroundColor: C.walk, borderRadius: 4 }] },
    options: { scales: { x: { grid: { display: false } }, y: { ticks: { precision: 0 } } } } });
  // dni v týždni
  const names = ["Po", "Ut", "St", "Št", "Pi", "So", "Ne"], dk = Array(7).fill(0), ds = Array(7).fill(0);
  cars.forEach(t => dk[(new Date(t.started_at).getDay() + 6) % 7] += t.distance_m / 1000);
  walks.forEach(t => ds[(new Date(t.started_at).getDay() + 6) % 7] += t.steps / 1000);
  chart("chDow", { type: "bar", data: { labels: names, datasets: [
    { label: "km autom", data: dk.map(v => +v.toFixed(1)), backgroundColor: C.car, borderRadius: 6 },
    { label: "tis. krokov", data: ds.map(v => +v.toFixed(1)), backgroundColor: C.walk, borderRadius: 6 }] },
    options: { scales: { x: { grid: { display: false } } } } });
  // mapa
  const m = makeMap("dashMap");
  if (m) {
    const layer = L.featureGroup().addTo(m);
    const list = S.trips.filter(t => !S.vehicle || isWalk(t) || t.vehicle_uuid === S.vehicle).slice(-30);
    for (const t of list) {
      const P = await points(t.uuid).catch(() => []);
      if (S.page !== "prehlad") return;
      if (P.length > 1) L.polyline(P.map(p => [p.lat, p.lng]), { color: isWalk(t) ? C.walk : C.car, weight: 3, opacity: .75 })
        .bindTooltip(`${dt(t.started_at)} ${tm(t.started_at)} · ${km(t.distance_m)}`).on("click", () => openTrip(t.uuid)).addTo(layer);
    }
    if (layer.getLayers().length) m.fitBounds(layer.getBounds(), { padding: [20, 20] });
  }
}
function recentList(list) {
  if (!list.length) return empty("inbox", "Žiadne záznamy v období");
  return `<div class="list-ct">` + list.map(t => `<div class="item clickable" role="button" data-trip="${t.uuid}">
    <div class="ico ${isWalk(t) ? "ico-blue" : "ico-green"} rounded-3 d-grid" style="width:38px;height:38px;place-items:center"><i class="bi bi-${isWalk(t) ? "person-walking" : "car-front-fill"}"></i></div>
    <div class="flex-grow-1 min-w-0"><div class="fw-semibold text-truncate">${esc(placeShort(t.start_place, t.start_address))} → ${esc(placeShort(t.end_place, t.end_address))}</div>
      <div class="small text-body-secondary">${dt(t.started_at)} ${tm(t.started_at)} · ${dur(tripMs(t))}${isWalk(t) ? " · " + int(t.steps) + " krokov" : veh(t) ? " · " + esc(veh(t).name) : ""}</div></div>
    <div class="text-end"><div class="fw-bold">${km(t.distance_m)}</div>${isWalk(t) ? "" : `<div class="small text-body-secondary">${eur(cost(t))}</div>`}</div></div>`).join("") + `</div>`;
}
function styleBlock(cars) {
  const scored = cars.filter(t => t.score != null);
  if (!scored.length) return empty("emoji-smile", "Bez hodnotenia štýlu");
  const avg = Math.round(sum(scored, t => t.score * Math.max(t.distance_m, 1)) / sum(scored, t => Math.max(t.distance_m, 1)));
  const color = avg >= 85 ? C.car : avg >= 70 ? C.amber : C.rose;
  const label = avg >= 90 ? "Výborný" : avg >= 80 ? "Veľmi dobrý" : avg >= 70 ? "Dobrý" : avg >= 55 ? "Priemerný" : "Riskantný";
  const R = 46, L2 = 2 * Math.PI * R;
  return `<div class="d-flex align-items-center gap-4">
    <svg width="120" height="120" viewBox="0 0 120 120"><circle cx="60" cy="60" r="${R}" fill="none" stroke="${css("--ct-grid")}" stroke-width="12"/>
      <circle cx="60" cy="60" r="${R}" fill="none" stroke="${color}" stroke-width="12" stroke-linecap="round" stroke-dasharray="${L2 * avg / 100} ${L2}" transform="rotate(-90 60 60)"/>
      <text x="60" y="66" text-anchor="middle" font-size="28" font-weight="800" fill="currentColor">${avg}</text></svg>
    <div><div class="fw-bold fs-5" style="color:${color}">${label}</div>
      <div class="small text-body-secondary">vážený priemer ${scored.length} jázd</div>
      <div class="mt-2 small"><i class="bi bi-exclamation-octagon text-danger"></i> prudké brzdenia: <b>${int(sum(cars, t => t.hard_brakes))}</b></div>
      <div class="small"><i class="bi bi-lightning-charge text-warning"></i> prudké zrýchlenia: <b>${int(sum(cars, t => t.hard_accels))}</b></div>
      <div class="small"><i class="bi bi-speedometer2 text-primary"></i> nad limitom: <b>${dur(sum(cars, t => t.speeding_sec) * 1000)}</b></div></div></div>`;
}
function recordsBlock(cars, walks) {
  const rows = [];
  const longest = [...cars].sort((a, b) => b.distance_m - a.distance_m)[0];
  if (longest) rows.push(["trophy", "Najdlhšia jazda", km(longest.distance_m), dt(longest.started_at), longest.uuid]);
  const fastest = [...cars].sort((a, b) => b.max_speed_kmh - a.max_speed_kmh)[0];
  if (fastest) rows.push(["rocket-takeoff", "Najvyššia rýchlosť", int(fastest.max_speed_kmh) + " km/h", dt(fastest.started_at), fastest.uuid]);
  const byDay = {}; cars.forEach(t => { const d = sod(t.started_at); byDay[d] = (byDay[d] || 0) + t.distance_m; });
  const bd = Object.entries(byDay).sort((a, b) => b[1] - a[1])[0];
  if (bd) rows.push(["calendar-check", "Najviac km za deň", km(bd[1]), dt(+bd[0])]);
  const sd = {}; walks.forEach(t => { const d = sod(t.started_at); sd[d] = (sd[d] || 0) + t.steps; });
  const bs = Object.entries(sd).sort((a, b) => b[1] - a[1])[0];
  if (bs) rows.push(["person-walking", "Najviac krokov za deň", int(bs[1]), dt(+bs[0])]);
  if (!rows.length) return empty("trophy", "Zatiaľ bez rekordov");
  return `<div class="list-ct">` + rows.map(([i, l, v, d, u]) => `<div class="item ${u ? "clickable" : ""}" ${u ? `role="button" data-trip="${u}"` : ""}>
    <i class="bi bi-${i} fs-5 text-primary"></i><div class="flex-grow-1"><div>${l}</div><div class="small text-body-secondary">${d}</div></div><div class="fw-bold">${v}</div></div>`).join("") + `</div>`;
}
function reminderStatus(r) {
  const now = Date.now();
  const days = r.due_date ? Math.round((+r.due_date - now) / DAY) : null;
  const v = r.vehicle_uuid ? S.vmap[r.vehicle_uuid] : null;
  const odoNow = v && v.odometer_km ? v.odometer_km + sum(S.trips.filter(t => t.vehicle_uuid === v.uuid && t.started_at > +v.odometer_date), t => t.distance_m) / 1000 : null;
  const kmLeft = r.due_km && odoNow ? Math.round(r.due_km - odoNow) : null;
  const nd = r.notify_days ?? 14, nk = r.notify_km ?? 500;
  const overdue = (days != null && days < 0) || (kmLeft != null && kmLeft < 0);
  const soon = !overdue && ((days != null && days <= nd) || (kmLeft != null && kmLeft <= nk));
  return { days, kmLeft, cls: overdue ? "overdue" : soon ? "soon" : "ok", v };
}
function remindersBlock(list) {
  if (!list.length) return empty("bell", "Žiadne pripomienky");
  const rows = list.map(r => ({ r, s: reminderStatus(r) })).sort((a, b) => (a.s.days ?? 9999) - (b.s.days ?? 9999)).slice(0, 6);
  return `<div class="list-ct">` + rows.map(({ r, s }) => `<div class="item"><i class="bi bi-bell fs-5 text-warning"></i>
    <div class="flex-grow-1 min-w-0"><div class="fw-semibold text-truncate">${esc(r.title)}</div><div class="small text-body-secondary">${esc(s.v?.name || "")}${r.due_date ? " · " + dt(r.due_date) : ""}${r.due_km ? " · " + int(r.due_km) + " km" : ""}</div></div>
    <span class="badge rounded-pill reminder-badge ${s.cls}">${s.days != null ? (s.days < 0 ? -s.days + " dní po" : "o " + s.days + " dní") : s.kmLeft != null ? (s.kmLeft < 0 ? -s.kmLeft + " km po" : "o " + int(s.kmLeft) + " km") : "–"}</span></div>`).join("") + `</div>`;
}
function bindTripLinks() { document.querySelectorAll("[data-trip]").forEach(el => el.onclick = () => openTrip(el.dataset.trip)); }

// =====================================================================
//  Detail jazdy (offcanvas)
// =====================================================================
async function openTrip(uuid) {
  const t = S.trips.find(x => x.uuid === uuid) || S.dayTrips?.find(x => x.uuid === uuid) || (await api("trips/" + uuid).then(r => normTrip(r.trip)).catch(() => null));
  if (!t) return;
  const panel = bootstrap.Offcanvas.getOrCreateInstance($("tripPanel"));
  $("tripTitle").innerHTML = `${isWalk(t) ? '<i class="bi bi-person-walking text-primary"></i>' : '<i class="bi bi-car-front-fill text-success"></i>'} ${esc(placeShort(t.start_place, t.start_address))} → ${esc(placeShort(t.end_place, t.end_address))}`;
  $("tripSub").textContent = `${dayName(t.started_at)} · ${tm(t.started_at)}–${t.ended_at ? tm(t.ended_at) : ""}`;
  const v = veh(t);
  const stats = isWalk(t)
    ? [["Vzdialenosť", km(t.distance_m, 2)], ["Kroky", int(t.steps)], ["Trvanie", dur(tripMs(t))], ["Tempo", t.distance_m > 50 ? nf(tripMs(t) / 60000 / (t.distance_m / 1000), 1) + " min/km" : "–"]]
    : [["Vzdialenosť", km(t.distance_m)], ["Trvanie", dur(tripMs(t))], ["Ø rýchlosť", int(t.avg_speed_kmh) + " km/h"], ["Max", int(t.max_speed_kmh) + " km/h"],
       ["Palivo", nf(liters(t), 2) + " l"], ["Náklady", eur(cost(t))], ["Skóre", t.score ?? "–"], ["Udalosti", `${t.hard_brakes} / ${t.hard_accels}`]];
  $("tripBody").innerHTML = `
    <div class="map-box mb-3" id="tripMap" style="height:300px"></div>
    <div class="row g-2 mb-3">${stats.map(([l, val]) => `<div class="col-6 col-md-3"><div class="stat-mini"><div class="v">${val}</div><div class="l">${l}</div></div></div>`).join("")}</div>
    ${isWalk(t) ? "" : `<h6 class="fw-bold">Rýchlosť počas jazdy</h6><div class="chart-box sm mb-1"><canvas id="chTripSpeed"></canvas></div><div class="small text-body-secondary mb-3" id="speedInfo"></div>`}
    <dl class="row small mb-0">
      <dt class="col-4 text-body-secondary fw-normal">Začiatok</dt><dd class="col-8">${esc(t.start_address || "—")}${t.start_place ? ` <span class="badge text-bg-light">${esc(t.start_place)}</span>` : ""}</dd>
      <dt class="col-4 text-body-secondary fw-normal">Cieľ</dt><dd class="col-8">${esc(t.end_address || "—")}${t.end_place ? ` <span class="badge text-bg-light">${esc(t.end_place)}</span>` : ""}</dd>
      ${isWalk(t) ? "" : `<dt class="col-4 text-body-secondary fw-normal">Vozidlo</dt><dd class="col-8">${esc(v ? v.name + (v.plate ? " · " + v.plate : "") : "—")}</dd>
      <dt class="col-4 text-body-secondary fw-normal">Typ</dt><dd class="col-8">${typeBadge(t)}</dd>`}
      <dt class="col-4 text-body-secondary fw-normal">Účel / poznámka</dt><dd class="col-8">${esc(t.note || "—")}</dd>
      <dt class="col-4 text-body-secondary fw-normal">Záznam</dt><dd class="col-8">${t.trigger_source === "manual" ? "ručne" : t.trigger_source === "bluetooth" ? "Bluetooth" : "automaticky"}</dd>
    </dl>
    <a class="btn btn-sm btn-outline-primary mt-2" href="#/den?d=${sod(t.started_at)}" data-bs-dismiss="offcanvas"><i class="bi bi-clock-history me-1"></i>Časová os dňa</a>`;
  panel.show();
  const P = await points(uuid).catch(() => []);
  setTimeout(() => {
    const m = makeMap("tripMap"); if (!m) return;
    if (P.length < 2) { m.setView([t.start_lat || 48.67, t.start_lng || 19.7], t.start_lat ? 13 : 7); return; }
    const layer = L.featureGroup().addTo(m);
    drawRoute(layer, P, !isWalk(t), C.walk);
    pin(P[0].lat, P[0].lng, "#16a34a", "A").addTo(layer); pin(P[P.length - 1].lat, P[P.length - 1].lng, "#dc2626", "B").addTo(layer);
    P.filter(p => p.event).forEach(p => L.circleMarker([p.lat, p.lng], { radius: 6, color: "#fff", weight: 2, fillOpacity: 1,
      fillColor: { brake: "#d32f2f", accel: "#f57c00", speeding: "#6a1b9a" }[p.event] }).bindTooltip({ brake: "prudké brzdenie", accel: "prudké zrýchlenie", speeding: "nad limitom" }[p.event]).addTo(layer));
    m.fitBounds(layer.getBounds(), { padding: [24, 24] });
  }, 250);
  if (!isWalk(t) && P.length > 1) {
    const sp = P.map(p => p.speed ?? 0);
    let moving = 0, movingKmh = 0;
    for (let i = 1; i < P.length; i++) if (sp[i] >= 5) { const d = P[i].ts - P[i - 1].ts; moving += d; movingKmh += d * sp[i]; }
    chart("chTripSpeed", { type: "line", data: { labels: P.map(p => tm(p.ts)), datasets: [{ label: "km/h", data: sp, fill: true, pointRadius: 0, tension: .25, borderWidth: 2.5,
      segment: { borderColor: (c) => speedColor(c.p1.parsed.y) }, backgroundColor: "rgba(37,99,235,.08)" }] },
      options: { plugins: { legend: { display: false } }, interaction: { mode: "index", intersect: false }, scales: { x: { ticks: { maxTicksLimit: 8 }, grid: { display: false } }, y: { beginAtZero: true } } } });
    $("speedInfo").textContent = `Ø v pohybe ${moving ? Math.round(movingKmh / moving) : 0} km/h · v pohybe ${dur(moving)} · státie ${dur(Math.max(0, tripMs(t) - moving))}`;
  }
}

// =====================================================================
//  Jazdy a chôdza (tabuľka)
// =====================================================================
const TS = { q: "", type: "", cat: "", sort: "started_at", dir: -1, page: 0 };
function pageTrips() {
  $("content").innerHTML = printHead("Jazdy a chôdza") + `<div class="ct-card"><div class="ct-body pt-3">
    <div class="row g-2 mb-3 noprint">
      <div class="col-md-4"><div class="input-group"><span class="input-group-text"><i class="bi bi-search"></i></span><input class="form-control" id="tq" placeholder="Hľadať adresu, miesto, účel…" value="${esc(TS.q)}"></div></div>
      <div class="col-6 col-md-2"><select class="form-select" id="tt"><option value="">Všetko</option><option value="car">Jazdy</option><option value="walk">Chôdza</option></select></div>
      <div class="col-6 col-md-2"><select class="form-select" id="tc"><option value="">Typ jazdy</option><option value="business">Služobné</option><option value="private">Súkromné</option></select></div>
      <div class="col-md-4 text-md-end"><button class="btn btn-outline-secondary" id="tcsv"><i class="bi bi-filetype-csv me-1"></i>Export CSV</button> <button class="btn btn-outline-secondary" onclick="print()"><i class="bi bi-printer me-1"></i>Tlač</button></div>
    </div>
    <div class="table-responsive"><table class="table table-ct table-hover"><thead><tr>
      <th class="sortable" data-s="started_at">Dátum</th><th>Trasa</th><th>Typ</th><th>Vozidlo</th>
      <th class="sortable text-end" data-s="distance_m">km</th><th class="sortable text-end" data-s="dur">Trvanie</th>
      <th class="sortable text-end" data-s="avg_speed_kmh">Ø</th><th class="sortable text-end" data-s="max_speed_kmh">Max</th>
      <th class="sortable text-end" data-s="score">Skóre</th><th class="sortable text-end" data-s="cost">Náklady</th></tr></thead><tbody id="tbody"></tbody>
      <tfoot id="tfoot"></tfoot></table></div>
    <div class="d-flex align-items-center gap-2 mt-3 noprint"><div class="small text-body-secondary me-auto" id="tinfo"></div><div id="tpager"></div></div>
  </div></div>`;
  $("tt").value = TS.type; $("tc").value = TS.cat;
  const rerun = () => { TS.page = 0; tripsTable(); };
  $("tq").oninput = (e) => { TS.q = e.target.value; rerun(); };
  $("tt").onchange = (e) => { TS.type = e.target.value; rerun(); };
  $("tc").onchange = (e) => { TS.cat = e.target.value; rerun(); };
  document.querySelectorAll("th.sortable").forEach(th => th.onclick = () => { TS.dir = TS.sort === th.dataset.s ? -TS.dir : -1; TS.sort = th.dataset.s; tripsTable(); });
  $("tcsv").onclick = () => csvDownload(`jazdy-${S.range.title}.csv`, [["Dátum", "Začiatok", "Koniec", "Typ", "Odkiaľ", "Kam", "Vozidlo", "Kategória", "km", "Trvanie min", "Ø km/h", "Max km/h", "Kroky", "Skóre", "Náklady €", "Účel"]]
    .concat(filteredTrips().map(t => [dt(t.started_at), tm(t.started_at), t.ended_at ? tm(t.ended_at) : "", isWalk(t) ? "chôdza" : "jazda", t.start_address || t.start_place || "", t.end_address || t.end_place || "",
      veh(t)?.name || "", isWalk(t) ? "" : t.category === "business" ? "služobná" : "súkromná", +(t.distance_m / 1000).toFixed(2), Math.round(tripMs(t) / 60000),
      Math.round(t.avg_speed_kmh), Math.round(t.max_speed_kmh), t.steps || "", t.score ?? "", +cost(t).toFixed(2), t.note || ""])));
  tripsTable();
}
function filteredTrips() {
  const q = TS.q.trim().toLowerCase();
  return S.trips.filter(t => (!TS.type || t.type === TS.type) && (!TS.cat || (!isWalk(t) && t.category === TS.cat))
    && (isWalk(t) || !S.vehicle || t.vehicle_uuid === S.vehicle)
    && (!q || [t.start_address, t.end_address, t.start_place, t.end_place, t.note, veh(t)?.name].some(x => (x || "").toLowerCase().includes(q))));
}
function tripsTable() {
  const key = { dur: tripMs, cost: cost }[TS.sort] || ((t) => t[TS.sort] ?? -1);
  const list = filteredTrips().sort((a, b) => (key(a) - key(b)) * TS.dir);
  const per = 25, pages = Math.max(1, Math.ceil(list.length / per));
  TS.page = Math.min(TS.page, pages - 1);
  const rows = list.slice(TS.page * per, TS.page * per + per);
  $("tbody").innerHTML = rows.length ? rows.map(t => `<tr class="clickable" data-trip="${t.uuid}">
    <td class="text-nowrap"><div class="fw-semibold">${dt(t.started_at)}</div><div class="small text-body-secondary">${tm(t.started_at)}–${t.ended_at ? tm(t.ended_at) : ""}</div></td>
    <td><div class="route"><span class="from">${esc(placeShort(t.start_place, t.start_address))}</span><span class="to">→ ${esc(placeShort(t.end_place, t.end_address))}</span></div>${t.note ? `<div class="small text-body-secondary text-truncate" style="max-width:340px">${esc(t.note)}</div>` : ""}</td>
    <td>${typeBadge(t)}</td><td class="small">${esc(veh(t)?.name || "")}</td>
    <td class="text-end fw-semibold">${nf(t.distance_m / 1000, 1)}</td><td class="text-end">${dur(tripMs(t))}</td>
    <td class="text-end">${isWalk(t) ? nf(t.avg_speed_kmh, 1) : int(t.avg_speed_kmh)}</td><td class="text-end">${isWalk(t) ? "" : int(t.max_speed_kmh)}</td>
    <td class="text-end">${t.score ?? ""}</td><td class="text-end">${isWalk(t) ? int(t.steps) + " kr." : eur(cost(t))}</td></tr>`).join("")
    : `<tr><td colspan="10">${empty("search", "Nič sa nenašlo")}</td></tr>`;
  $("tfoot").innerHTML = list.length ? `<tr><td colspan="4">Spolu ${list.length} záznamov</td><td class="text-end">${nf(sum(list, t => t.distance_m) / 1000, 1)}</td>
    <td class="text-end">${durH(sum(list, tripMs))}</td><td></td><td></td><td></td><td class="text-end">${eur(sum(list, cost))}</td></tr>` : "";
  document.querySelectorAll("th.sortable").forEach(th => th.innerHTML = th.innerHTML.replace(/ <i.*$/, "") + (th.dataset.s === TS.sort ? ` <i class="bi bi-caret-${TS.dir < 0 ? "down" : "up"}-fill"></i>` : ""));
  $("tinfo").textContent = list.length ? `${TS.page * per + 1}–${Math.min(list.length, TS.page * per + per)} z ${list.length}` : "";
  $("tpager").innerHTML = pages > 1 ? `<div class="btn-group btn-group-sm"><button class="btn btn-outline-secondary" ${TS.page ? "" : "disabled"} id="pp"><i class="bi bi-chevron-left"></i></button>
    <button class="btn btn-outline-secondary" disabled>${TS.page + 1} / ${pages}</button><button class="btn btn-outline-secondary" ${TS.page < pages - 1 ? "" : "disabled"} id="pn"><i class="bi bi-chevron-right"></i></button></div>` : "";
  if ($("pp")) { $("pp").onclick = () => { TS.page--; tripsTable(); }; $("pn").onclick = () => { TS.page++; tripsTable(); }; }
  bindTripLinks();
}

// =====================================================================
//  Mapa
// =====================================================================
async function pageMap() {
  $("content").innerHTML = `<div class="ct-card"><div class="ct-body pt-3">
    <div class="d-flex flex-wrap gap-3 align-items-center mb-3 noprint">
      <div class="form-check form-switch"><input class="form-check-input" type="checkbox" id="mCar" checked><label class="form-check-label" for="mCar"><span class="dot" style="background:${C.car}"></span> Jazdy</label></div>
      <div class="form-check form-switch"><input class="form-check-input" type="checkbox" id="mWalk" checked><label class="form-check-label" for="mWalk"><span class="dot" style="background:${C.walk}"></span> Chôdza</label></div>
      <div class="form-check form-switch"><input class="form-check-input" type="checkbox" id="mSpeed"><label class="form-check-label" for="mSpeed">Farba podľa rýchlosti</label></div>
      <div class="form-check form-switch"><input class="form-check-input" type="checkbox" id="mPlaces" checked><label class="form-check-label" for="mPlaces"><i class="bi bi-geo-alt-fill text-danger"></i> Miesta</label></div>
      <span class="d-none" id="mLegend"><span class="small text-body-secondary me-1">pomaly</span><span class="speed-legend d-inline-block align-middle"></span><span class="small text-body-secondary ms-1">rýchlo</span></span>
      <div class="ms-auto small text-body-secondary" id="mInfo"></div>
    </div>
    <div class="progress mb-2 d-none" style="height:4px" id="mProg"><div class="progress-bar" id="mBar"></div></div>
    <div class="map-full" id="bigMap"></div></div></div>`;
  const m = makeMap("bigMap"); if (!m) return;
  const groups = { car: L.featureGroup().addTo(m), walk: L.featureGroup().addTo(m), places: L.featureGroup().addTo(m) };
  S.items.place.forEach(p => { if (p.lat == null) return;
    L.circle([+p.lat, +p.lng], { radius: +p.radius_m || 150, color: "#e11d48", weight: 1, fillOpacity: .12 }).addTo(groups.places);
    L.marker([+p.lat, +p.lng]).bindTooltip(esc(p.name)).addTo(groups.places); });
  const list = S.trips.filter(t => isWalk(t) || !S.vehicle || t.vehicle_uuid === S.vehicle).slice(-150);
  const data = [];
  $("mProg").classList.remove("d-none");
  for (let i = 0; i < list.length; i++) {
    const P = await points(list[i].uuid).catch(() => []);
    if (S.page !== "mapa") return;
    data.push([list[i], P]);
    $("mBar").style.width = Math.round((i + 1) / list.length * 100) + "%";
  }
  $("mProg").classList.add("d-none");
  const draw = () => {
    groups.car.clearLayers(); groups.walk.clearLayers();
    const bySpeed = $("mSpeed").checked;
    $("mLegend").classList.toggle("d-none", !bySpeed);
    data.forEach(([t, P]) => {
      if (P.length < 2) return;
      const g = isWalk(t) ? groups.walk : groups.car;
      if (bySpeed && !isWalk(t)) drawRoute(g, P, true);
      else L.polyline(P.map(p => [p.lat, p.lng]), { color: isWalk(t) ? C.walk : C.car, weight: 3.5, opacity: .7 })
        .bindTooltip(`${dt(t.started_at)} ${tm(t.started_at)} · ${km(t.distance_m)}`).on("click", () => openTrip(t.uuid)).addTo(g);
    });
    const tog = (id, g) => $(id).checked ? m.addLayer(g) : m.removeLayer(g);
    tog("mCar", groups.car); tog("mWalk", groups.walk); tog("mPlaces", groups.places);
    $("mInfo").textContent = `${data.length} záznamov${list.length < S.trips.length ? " (posledných 150)" : ""}`;
  };
  ["mCar", "mWalk", "mSpeed", "mPlaces"].forEach(id => $(id).onchange = draw);
  draw();
  const all = L.featureGroup([groups.car, groups.walk]);
  if (all.getLayers().some(g => g.getLayers().length)) m.fitBounds(all.getBounds(), { padding: [20, 20] });
}

// =====================================================================
//  Časová os dňa
// =====================================================================
async function pageDay() {
  const q = new URLSearchParams(location.hash.split("?")[1] || "");
  let day = sod(+q.get("d") || Date.now());
  const draw = async () => {
    loading(true);
    const list = await fetchTrips(day - DAY, day + 2 * DAY).finally(() => loading(false));
    S.dayTrips = list;
    const trips = list.filter(t => (t.ended_at || t.started_at) > day && t.started_at < day + DAY);
    const seg = [];
    trips.forEach((t, i) => {
      if (i > 0) { const p = trips[i - 1], gf = p.ended_at || p.started_at; if (t.started_at - gf >= 120000) seg.push({ k: "stay", from: gf, to: t.started_at, place: p.end_place || p.end_address || t.start_place || t.start_address }); }
      seg.push({ k: isWalk(t) ? "walk" : "car", from: t.started_at, to: t.ended_at || t.started_at, t });
    });
    const cars = trips.filter(t => !isWalk(t)), walks = trips.filter(isWalk);
    const colors = { car: C.car, walk: C.walk, stay: "#a16207" };
    $("content").innerHTML = `<div class="print-head"><h2 class="h4 fw-bold">Časová os – ${dayName(day)}</h2><div>${esc(S.user?.name || "")}</div></div>
    <div class="d-flex flex-wrap align-items-center gap-2 mb-3 noprint">
      <div class="btn-group"><button class="btn btn-outline-secondary" id="dPrev"><i class="bi bi-chevron-left"></i></button>
        <input type="date" class="form-control rounded-0" id="dPick" style="max-width:170px" value="${new Date(day - new Date(day).getTimezoneOffset() * 6e4).toISOString().slice(0, 10)}">
        <button class="btn btn-outline-secondary" id="dNext" ${day >= sod(Date.now()) ? "disabled" : ""}><i class="bi bi-chevron-right"></i></button></div>
      <span class="fw-semibold">${dayName(day)}</span>
      <div class="ms-auto"><button class="btn btn-outline-secondary" id="dCsv"><i class="bi bi-filetype-csv me-1"></i>CSV</button> <button class="btn btn-primary" onclick="print()"><i class="bi bi-printer me-1"></i>Tlač / PDF</button></div>
    </div>
    <div class="row g-3">
      ${kpi("speedometer", "ico-green", "Autom", km(sum(cars, t => t.distance_m)))}${kpi("stopwatch", "ico-cyan", "Za volantom", dur(sum(cars, tripMs)))}
      ${kpi("signpost-2", "ico-blue", "Jázd", cars.length)}${kpi("person-walking", "ico-rose", "Kroky", int(sum(walks, t => t.steps)))}
      ${kpi("geo", "ico-violet", "Pešo", km(sum(walks, t => t.distance_m), 2))}${kpi("fuel-pump", "ico-amber", "Náklady", eur(sum(cars, cost)))}
      ${card("Priebeh dňa", seg.length ? `<div class="timeline">` + seg.map(g => {
        const t = g.t;
        const body = g.k === "stay" ? `<div><i class="bi bi-p-square-fill" style="color:${colors.stay}"></i> ${esc(g.place || "Na mieste")}</div><div class="small text-body-secondary">pobyt ${dur(g.to - g.from)}</div>`
          : `<div class="fw-semibold" role="button" data-trip="${t.uuid}"><i class="bi bi-${g.k === "car" ? "car-front-fill" : "person-walking"}" style="color:${colors[g.k]}"></i> ${esc(placeShort(t.start_place, t.start_address))} → ${esc(placeShort(t.end_place, t.end_address))}</div>
             <div class="small text-body-secondary">${km(t.distance_m, g.k === "walk" ? 2 : 1)} · ${dur(g.to - g.from)}${g.k === "car" ? ` · Ø ${int(t.avg_speed_kmh)} km/h${veh(t) ? " · " + esc(veh(t).name) : ""}` : ` · ${int(t.steps)} krokov`} ${g.k === "car" ? typeBadge(t) : ""}</div>${t.note ? `<div class="small">${esc(t.note)}</div>` : ""}`;
        return `<div class="tl-item"><div class="tl-time">${tm(g.from)}<small>${tm(g.to)}</small></div>
          <div class="tl-rail ${g.k === "stay" ? "stay" : ""}" style="--c:${colors[g.k]}"><div class="tl-dot"></div></div><div class="tl-body">${body}</div></div>`;
      }).join("") + `</div>` : empty("calendar-x", "V tento deň nie sú žiadne záznamy"), { col: "col-xl-6" })}
      ${card("Mapa dňa", `<div class="map-box" id="dayMap" style="height:520px"></div>`, { col: "col-xl-6" })}
    </div>`;
    const go = (d) => { day = d; history.replaceState(null, "", "#/den?d=" + day); draw(); };
    $("dPrev").onclick = () => go(sod(day - DAY / 2));
    $("dNext").onclick = () => go(sod(day + DAY * 1.5));
    $("dPick").onchange = (e) => { const [y, mo, dd] = e.target.value.split("-").map(Number); if (y) go(+new Date(y, mo - 1, dd)); };
    $("dCsv").onclick = () => csvDownload(`casova-os-${dt(day)}.csv`, [["Od", "Do", "Činnosť", "Odkiaľ", "Kam / miesto", "km", "Trvanie min", "Kroky", "Vozidlo", "Účel"]].concat(seg.map(g => [tm(g.from), tm(g.to),
      { car: "jazda", walk: "chôdza", stay: "pobyt" }[g.k], g.t ? g.t.start_place || g.t.start_address || "" : "", g.t ? g.t.end_place || g.t.end_address || "" : g.place || "",
      g.t ? +(g.t.distance_m / 1000).toFixed(2) : "", Math.round((g.to - g.from) / 60000), g.t?.steps || "", g.t ? veh(g.t)?.name || "" : "", g.t?.note || ""])));
    bindTripLinks();
    const m = makeMap("dayMap");
    if (m) {
      const layer = L.featureGroup().addTo(m);
      for (const t of trips) { const P = await points(t.uuid).catch(() => []); drawRoute(layer, P, false, isWalk(t) ? C.walk : C.car); }
      if (layer.getLayers().length) m.fitBounds(layer.getBounds(), { padding: [20, 20] });
    }
  };
  await draw();
}

// =====================================================================
//  Kniha jázd
// =====================================================================
function needVehicle(what) {
  const own = S.vehicles.filter(v => !+v.archived);
  if (!S.vehicle && own.length === 1) { S.vehicle = own[0].uuid; fillVehicles(); }
  if (S.vehicle) return S.vmap[S.vehicle];
  $("content").innerHTML = `<div class="ct-card">${empty("car-front", `${what} sa vedie pre každé vozidlo zvlášť – vyberte vozidlo hore.`)}
    <div class="text-center pb-4">${own.map(v => `<button class="btn btn-outline-primary m-1" data-v="${v.uuid}">${esc(v.name)}</button>`).join("")}</div></div>`;
  document.querySelectorAll("[data-v]").forEach(b => b.onclick = () => { S.vehicle = b.dataset.v; LS.set("ct_vehicle", S.vehicle); fillVehicles(); render(); });
  return null;
}
function pageLogbook() {
  const v = needVehicle("Kniha jázd"); if (!v) return;
  const list = carTrips();
  const total = sum(list, t => t.distance_m), biz = sum(list.filter(t => t.category === "business"), t => t.distance_m);
  $("content").innerHTML = printHead("Kniha jázd") + `<div class="row g-3">
    ${kpi("signpost-2", "ico-blue", "Jázd", list.length)}${kpi("speedometer", "ico-green", "Spolu", km(total))}
    ${kpi("briefcase", "ico-violet", "Služobné (S)", km(biz))}${kpi("house", "ico-cyan", "Súkromné (P)", km(total - biz))}
    ${kpi("fuel-pump", "ico-amber", "Palivo", nf(sum(list, liters), 1) + " l")}${kpi("cash", "ico-rose", "Náklady", eur(sum(list, cost)))}
    ${card(`Kniha jázd · ${esc(v.name)} ${v.plate ? `<span class="plate ms-1">${esc(v.plate)}</span>` : ""}`, list.length ? `<div class="table-responsive"><table class="table table-ct table-sm">
      <thead><tr><th>#</th><th>Dátum</th><th>Čas</th><th>Odkiaľ</th><th>Kam</th><th>Účel</th><th>Typ</th><th class="text-end">km</th><th class="text-end">Palivo l</th><th class="text-end">Náklady €</th></tr></thead>
      <tbody>${list.map((t, i) => `<tr class="clickable" data-trip="${t.uuid}"><td>${i + 1}</td><td class="text-nowrap">${dt(t.started_at)}</td><td class="text-nowrap">${tm(t.started_at)}–${t.ended_at ? tm(t.ended_at) : ""}</td>
        <td>${esc(t.start_address || t.start_place || "—")}</td><td>${esc(t.end_address || t.end_place || "—")}</td><td>${esc(t.note || (t.category === "business" ? "služobná cesta" : ""))}</td>
        <td><span class="badge ${t.category === "business" ? "badge-biz" : "badge-priv"}">${t.category === "business" ? "S" : "P"}</span></td>
        <td class="text-end fw-semibold">${nf(t.distance_m / 1000, 1)}</td><td class="text-end">${nf(liters(t), 2)}</td><td class="text-end">${nf(cost(t), 2)}</td></tr>`).join("")}</tbody>
      <tfoot><tr><td colspan="7">Spolu ${list.length} jázd</td><td class="text-end">${nf(total / 1000, 1)}</td><td class="text-end">${nf(sum(list, liters), 1)}</td><td class="text-end">${nf(sum(list, cost), 2)}</td></tr></tfoot></table></div>
      <div class="d-flex justify-content-between mt-5 small text-body-secondary"><span>Dátum: ______________</span><span>Podpis vodiča: ________________________</span></div>`
      : empty("journal", "V období nie sú žiadne jazdy tohto vozidla"),
      { tools: `<button class="btn btn-sm btn-outline-secondary" id="lCsv"><i class="bi bi-filetype-csv me-1"></i>CSV</button><button class="btn btn-sm btn-primary" onclick="print()"><i class="bi bi-printer me-1"></i>Tlač / PDF</button>` })}
  </div>`;
  bindTripLinks();
  $("lCsv").onclick = () => csvDownload(`kniha-jazd-${v.plate || v.name}-${S.range.title}.csv`, [["Dátum", "Začiatok", "Koniec", "Odkiaľ", "Kam", "Účel", "Typ", "km", "Palivo l", "Náklady €"]]
    .concat(list.map(t => [dt(t.started_at), tm(t.started_at), t.ended_at ? tm(t.ended_at) : "", t.start_address || "", t.end_address || "", t.note || "",
      t.category === "business" ? "služobná" : "súkromná", +(t.distance_m / 1000).toFixed(2), +liters(t).toFixed(2), +cost(t).toFixed(2)])));
}

// =====================================================================
//  Cestovné náhrady
// =====================================================================
function pageReimb() {
  const v = needVehicle("Výkaz náhrad"); if (!v) return;
  const rate = v.rate_per_km;
  const rows = carTrips().filter(t => t.category === "business").map(t => { const k = t.distance_m / 1000; return { t, k, basic: k * rate, fuel: cost(t) }; });
  const tot = (f) => sum(rows, f);
  $("content").innerHTML = printHead("Cestovné náhrady – použitie vlastného vozidla") + `<div class="row g-3">
    ${kpi("signpost-2", "ico-blue", "Služobných jázd", rows.length)}${kpi("speedometer", "ico-violet", "Služobné km", km(tot(r => r.k * 1000)))}
    ${kpi("cash-stack", "ico-green", "Základná náhrada", eur(tot(r => r.basic)))}${kpi("fuel-pump", "ico-amber", "Náhrada za PHL", eur(tot(r => r.fuel)))}
    ${kpi("wallet2", "ico-rose", "Na vyplatenie", eur(tot(r => r.basic + r.fuel)))}${kpi("calculator", "ico-cyan", "Sadzba", nf(rate, 4) + " €/km")}
    ${rate ? "" : `<div class="col-12"><div class="alert alert-warning mb-0"><i class="bi bi-exclamation-triangle me-1"></i>Pri vozidle nie je nastavená sadzba €/km (v aplikácii: Vozidlá → upraviť). Počíta sa iba náhrada za palivo.</div></div>`}
    ${card(`Výkaz náhrad · ${esc(v.name)}`, rows.length ? `<div class="small text-body-secondary mb-2">Spotreba ${nf(v.consumption, 1)} l/100 km · cena PHL ${nf(v.fuel_price, 3)} €</div><div class="table-responsive"><table class="table table-ct table-sm">
      <thead><tr><th>#</th><th>Dátum</th><th>Trasa</th><th>Účel</th><th class="text-end">km</th><th class="text-end">Základná</th><th class="text-end">PHL</th><th class="text-end">Spolu</th></tr></thead>
      <tbody>${rows.map((r, i) => `<tr class="clickable" data-trip="${r.t.uuid}"><td>${i + 1}</td><td class="text-nowrap">${dt(r.t.started_at)}</td><td>${esc(placeShort(r.t.start_place, r.t.start_address))} → ${esc(placeShort(r.t.end_place, r.t.end_address))}</td>
        <td>${esc(r.t.note || "služobná cesta")}</td><td class="text-end">${nf(r.k, 1)}</td><td class="text-end">${eur(r.basic)}</td><td class="text-end">${eur(r.fuel)}</td><td class="text-end fw-bold">${eur(r.basic + r.fuel)}</td></tr>`).join("")}</tbody>
      <tfoot><tr><td colspan="4">Spolu</td><td class="text-end">${nf(tot(r => r.k), 1)}</td><td class="text-end">${eur(tot(r => r.basic))}</td><td class="text-end">${eur(tot(r => r.fuel))}</td><td class="text-end">${eur(tot(r => r.basic + r.fuel))}</td></tr></tfoot></table></div>
      <div class="d-flex justify-content-between mt-5 small text-body-secondary"><span>Podpis zamestnanca: ________________</span><span>Schválil: ________________</span></div>
      <div class="small text-body-secondary mt-3">Sadzbu a spôsob výpočtu overte podľa platných predpisov a pokynov zamestnávateľa.</div>`
      : empty("briefcase", "V období nie sú žiadne služobné jazdy tohto vozidla"),
      { tools: `<button class="btn btn-sm btn-outline-secondary" id="rCsv"><i class="bi bi-filetype-csv me-1"></i>CSV</button><button class="btn btn-sm btn-primary" onclick="print()"><i class="bi bi-printer me-1"></i>Tlač / PDF</button>` })}
  </div>`;
  bindTripLinks();
  $("rCsv").onclick = () => csvDownload(`nahrady-${S.range.title}.csv`, [["Dátum", "Trasa", "Účel", "km", "Základná náhrada €", "Náhrada za PHL €", "Spolu €"]].concat(rows.map(r =>
    [dt(r.t.started_at), placeShort(r.t.start_place, r.t.start_address) + " → " + placeShort(r.t.end_place, r.t.end_address), r.t.note || "", +r.k.toFixed(2), +r.basic.toFixed(2), +r.fuel.toFixed(2), +(r.basic + r.fuel).toFixed(2)])));
}

// =====================================================================
//  Vozidlá, tankovanie, pripomienky, zdieľanie
// =====================================================================
function refuelStats(list) {
  const r = [...list].filter(x => x.odometer_km).sort((a, b) => a.ts - b.ts);
  const pts = []; let lastFull = null, acc = 0;
  for (const x of r) {
    if (lastFull) acc += +x.liters;
    if (x.full_tank !== false) {
      if (lastFull && x.odometer_km > lastFull.odometer_km) pts.push({ ts: +x.ts, per100: acc / (x.odometer_km - lastFull.odometer_km) * 100 });
      lastFull = x; acc = 0;
    }
  }
  const avg = pts.length ? sum(pts, p => p.per100) / pts.length : null;
  return { pts, avg };
}
function pageVehicles() {
  const list = S.vehicles.filter(v => !+v.archived);
  if (!list.length) { $("content").innerHTML = `<div class="ct-card">${empty("car-front", "Žiadne vozidlá – pridajte ich v aplikácii.")}</div>`; return; }
  $("content").innerHTML = `<div class="row g-3">` + list.map((v, i) => {
    const tr = S.trips.filter(t => !isWalk(t) && t.vehicle_uuid === v.uuid);
    const refuels = S.items.refuel.filter(r => r.vehicle_uuid === v.uuid).sort((a, b) => b.ts - a.ts);
    const st = refuelStats(refuels);
    const odo = v.odometer_km ? v.odometer_km + sum(S.trips.filter(t => t.vehicle_uuid === v.uuid && t.started_at > +v.odometer_date), t => t.distance_m) / 1000 : null;
    const rem = S.items.reminder.filter(r => !r.done && r.vehicle_uuid === v.uuid);
    return `<div class="col-xl-6"><div class="ct-card vehicle-card">
      <div class="ct-head"><div class="ico ico-green rounded-3 d-grid me-1" style="width:42px;height:42px;place-items:center"><i class="bi bi-${v.fuel_type === "electric" ? "ev-front" : "car-front-fill"} fs-5"></i></div>
        <div><h2 class="mb-0">${esc(v.name)} ${v.plate ? `<span class="plate ms-1">${esc(v.plate)}</span>` : ""}</h2>
        <div class="sub">${v.is_owner ? (v.share_count ? `zdieľané s ${v.share_count} vodičmi` : "vlastné vozidlo") : "👥 zdieľané od " + esc(v.owner_name)}</div></div>
        <div class="ms-auto d-flex gap-1 noprint"><button class="btn btn-sm btn-outline-primary" data-shared="${v.uuid}"><i class="bi bi-people me-1"></i>Spoločná kniha</button>
        ${v.is_owner ? `<button class="btn btn-sm btn-outline-secondary" data-share="${v.uuid}" title="Zdieľanie"><i class="bi bi-share"></i></button>` : ""}</div></div>
      <div class="ct-body">
        <div class="row g-2 mb-3">
          ${[["V období", km(sum(tr, t => t.distance_m))], ["Jázd", tr.length], ["Náklady", eur(sum(tr, cost))], ["Tachometer", odo ? int(odo) + " km" : "–"],
             ["Spotreba (nast.)", nf(v.consumption, 1) + " " + (v.fuel_type === "electric" ? "kWh" : "l")], ["Skutočná", st.avg ? nf(st.avg, 1) : "–"],
             ["Cena", nf(v.fuel_price, 3) + " €"], ["Limity", `${v.limit_urban}/${v.limit_rural}/${v.limit_highway}`]]
            .map(([l, x]) => `<div class="col-6 col-md-3"><div class="stat-mini"><div class="v">${x}</div><div class="l">${l}</div></div></div>`).join("")}
        </div>
        ${st.pts.length > 1 ? `<h6 class="fw-bold small text-uppercase text-body-secondary">Skutočná spotreba (plná–plná)</h6><div class="chart-box sm mb-3"><canvas id="chCons${i}"></canvas></div>` : ""}
        ${rem.length ? `<h6 class="fw-bold small text-uppercase text-body-secondary">Pripomienky</h6>${remindersBlock(rem)}` : ""}
        ${refuels.length ? `<h6 class="fw-bold small text-uppercase text-body-secondary mt-3">Posledné tankovania</h6><div class="table-responsive"><table class="table table-ct table-sm"><thead><tr><th>Dátum</th><th class="text-end">Množstvo</th><th class="text-end">Suma</th><th class="text-end">€/l</th><th class="text-end">Tachometer</th></tr></thead><tbody>
          ${refuels.slice(0, 6).map(r => `<tr><td>${dt(r.ts)}${r.full_tank === false ? ' <span class="badge badge-priv">čiastočne</span>' : ""}</td><td class="text-end">${nf(r.liters, 2)}</td><td class="text-end">${eur(r.total_price)}</td>
            <td class="text-end">${r.liters ? nf(r.total_price / r.liters, 3) : "–"}</td><td class="text-end">${r.odometer_km ? int(r.odometer_km) : "–"}</td></tr>`).join("")}</tbody></table></div>` : `<div class="small text-body-secondary">Zatiaľ žiadne tankovania.</div>`}
      </div></div></div>`;
  }).join("") + `</div>`;
  list.forEach((v, i) => {
    const st = refuelStats(S.items.refuel.filter(r => r.vehicle_uuid === v.uuid));
    if (st.pts.length > 1) chart("chCons" + i, { type: "line", data: { labels: st.pts.map(p => dtShort(p.ts)), datasets: [
      { label: "Skutočná", data: st.pts.map(p => +p.per100.toFixed(2)), borderColor: C.car, backgroundColor: "rgba(22,163,74,.12)", fill: true, tension: .3 },
      { label: "Nastavená", data: st.pts.map(() => v.consumption), borderColor: C.amber, borderDash: [6, 4], pointRadius: 0 }] }, options: { scales: { y: { title: { display: true, text: "na 100 km" } } } } });
  });
  document.querySelectorAll("[data-shared]").forEach(b => b.onclick = () => sharedLogbook(S.vmap[b.dataset.shared]));
  document.querySelectorAll("[data-share]").forEach(b => b.onclick = () => manageShares(S.vmap[b.dataset.share]));
}
async function sharedLogbook(v) {
  modal(`<i class="bi bi-people me-1"></i>Spoločná kniha jázd · ${esc(v.name)} <span class="small text-body-secondary">${esc(S.range.title)}</span>`, `<div class="text-center py-5"><div class="spinner-border"></div></div>`);
  try {
    const r = await api(`vehicles/${v.uuid}/trips?from=${S.range.from}&to=${S.range.to}`);
    const T = r.trips.map(normTrip);
    const by = {}; T.forEach(t => by[t.driver_name] = (by[t.driver_name] || 0) + t.distance_m);
    $("modalBody").innerHTML = `<div class="d-flex flex-wrap gap-2 mb-3">${Object.entries(by).map(([d, m]) => `<span class="badge rounded-pill text-bg-primary fs-6 fw-normal">${esc(d)}: ${km(m)}</span>`).join("")}</div>
      ${T.length ? `<div class="table-responsive"><table class="table table-ct table-sm"><thead><tr><th>#</th><th>Dátum</th><th>Čas</th><th>Vodič</th><th>Odkiaľ</th><th>Kam</th><th>Účel</th><th>Typ</th><th class="text-end">km</th></tr></thead><tbody>
      ${T.map((t, i) => `<tr><td>${i + 1}</td><td>${dt(t.started_at)}</td><td class="text-nowrap">${tm(t.started_at)}–${t.ended_at ? tm(t.ended_at) : ""}</td><td>${esc(t.driver_name)}</td>
        <td>${esc(t.start_address || t.start_place || "—")}</td><td>${esc(t.end_address || t.end_place || "—")}</td><td>${esc(t.note || "")}</td><td>${t.category === "business" ? "S" : "P"}</td><td class="text-end">${nf(t.distance_m / 1000, 1)}</td></tr>`).join("")}
      </tbody><tfoot><tr><td colspan="8">Spolu ${T.length} jázd</td><td class="text-end">${nf(sum(T, t => t.distance_m) / 1000, 1)}</td></tr></tfoot></table></div>` : empty("journal", "Žiadne jazdy v období")}`;
  } catch (e) { $("modalBody").innerHTML = `<div class="alert alert-danger">${esc(e.message)}</div>`; }
}
async function manageShares(v) {
  modal(`<i class="bi bi-share me-1"></i>Zdieľanie · ${esc(v.name)}`, `<div class="text-center py-4"><div class="spinner-border"></div></div>`);
  const draw = async () => {
    const r = await api(`vehicles/${v.uuid}/shares`).catch(e => ({ shares: [], err: e.message }));
    $("modalBody").innerHTML = `<p class="text-body-secondary">Vodiči uvidia vozidlo vo svojej aplikácii a ich jazdy sa zapíšu do spoločnej knihy jázd. Musia mať účet na tomto serveri.</p>
      ${r.shares.length ? `<ul class="list-group mb-3">${r.shares.map(u => `<li class="list-group-item d-flex align-items-center"><i class="bi bi-person-circle fs-4 me-2"></i><div class="me-auto"><div class="fw-semibold">${esc(u.name)}</div><div class="small text-body-secondary">${esc(u.email)}</div></div>
        <button class="btn btn-sm btn-outline-danger" data-rm="${u.user_id}">Odobrať</button></li>`).join("")}</ul>` : `<div class="alert alert-light">Zatiaľ nezdieľané.</div>`}
      <div class="input-group"><input type="email" class="form-control" id="shEmail" placeholder="e-mail vodiča"><button class="btn btn-primary" id="shAdd"><i class="bi bi-person-plus me-1"></i>Pridať</button></div>
      <div class="text-danger small mt-2" id="shErr">${esc(r.err || "")}</div>`;
    $("shAdd").onclick = async () => { try { await api(`vehicles/${v.uuid}/shares`, { method: "POST", body: JSON.stringify({ email: $("shEmail").value }) }); v.share_count++; toast("Vozidlo zdieľané"); draw(); } catch (e) { $("shErr").textContent = e.message; } };
    document.querySelectorAll("[data-rm]").forEach(b => b.onclick = async () => { await api(`vehicles/${v.uuid}/shares/${b.dataset.rm}`, { method: "DELETE" }); v.share_count = Math.max(0, v.share_count - 1); draw(); });
  };
  draw();
}

// =====================================================================
//  Časté trasy
// =====================================================================
async function pageRoutes() {
  $("content").innerHTML = `<div class="ct-card"><div class="text-center py-5"><div class="spinner-border"></div><div class="small text-body-secondary mt-2">Počítam trasy za posledný rok…</div></div></div>`;
  const year = await fetchTrips(Date.now() - 365 * DAY, Date.now() + DAY, "car");
  const T = year.filter(t => t.start_lat != null && t.end_lat != null && t.ended_at && t.distance_m >= 1000 && (!S.vehicle || t.vehicle_uuid === S.vehicle));
  const cl = [];
  const hav = (a, b, c, d) => { const r = Math.PI / 180, x = Math.sin((c - a) * r / 2) ** 2 + Math.cos(a * r) * Math.cos(c * r) * Math.sin((d - b) * r / 2) ** 2; return 12742000 * Math.asin(Math.sqrt(x)); };
  const cid = (lat, lng, name) => { let i = cl.findIndex(c => hav(c.lat, c.lng, lat, lng) < 350); if (i < 0) { cl.push({ lat, lng, names: {} }); i = cl.length - 1; } if (name) cl[i].names[name] = (cl[i].names[name] || 0) + 1; return i; };
  const groups = {};
  T.forEach(t => { const a = cid(t.start_lat, t.start_lng, placeShort(t.start_place, t.start_address)), b = cid(t.end_lat, t.end_lng, placeShort(t.end_place, t.end_address)); if (a !== b) (groups[a + ">" + b] ||= []).push(t); });
  const label = (i) => Object.entries(cl[i].names).sort((x, y) => y[1] - x[1])[0]?.[0] || "?";
  const routes = Object.entries(groups).filter(([, l]) => l.length >= 3).sort((x, y) => y[1].length - x[1].length).map(([k, l]) => {
    const [a, b] = k.split(">").map(Number), d = l.map(tripMs);
    const byH = {}; l.forEach(t => (byH[new Date(t.started_at).getHours()] ||= []).push(tripMs(t)));
    const hours = Object.entries(byH).map(([h, x]) => ({ h: +h, avg: sum(x, v => v) / x.length, n: x.length })).sort((p, q) => p.h - q.h);
    const best = [...hours].sort((p, q) => (q.n >= 2) - (p.n >= 2) || p.avg - q.avg)[0];
    return { from: label(a), to: label(b), l, avg: sum(d, v => v) / d.length, min: Math.min(...d), max: Math.max(...d), km: sum(l, t => t.distance_m) / l.length, hours, best };
  });
  $("content").innerHTML = printHead("Časté trasy") + (routes.length ? `<div class="row g-3">` + routes.map((r, i) => card(
    `${esc(r.from)} <i class="bi bi-arrow-right text-body-secondary"></i> ${esc(r.to)}`,
    `<div class="row g-2 mb-3">${[["Počet", r.l.length + "×"], ["Ø čas", dur(r.avg)], ["Najrýchlejšie", dur(r.min)], ["Najpomalšie", dur(r.max)], ["Ø vzdialenosť", km(r.km)], ["Naposledy", dt(r.l[r.l.length - 1].started_at)]]
      .map(([l, x]) => `<div class="col-4 col-md-2"><div class="stat-mini"><div class="v">${x}</div><div class="l">${l}</div></div></div>`).join("")}</div>
     ${r.best && r.hours.length > 1 ? `<div class="alert alert-success py-2 small mb-2"><i class="bi bi-lightbulb me-1"></i>Najrýchlejšie pri odchode <b>${String(r.best.h).padStart(2, "0")}:00–${String(r.best.h).padStart(2, "0")}:59</b> (Ø ${dur(r.best.avg)})</div>` : ""}
     <div class="chart-box sm"><canvas id="chRoute${i}"></canvas></div>`, { col: "col-xl-6", sub: r.l.length + " jázd za rok" })).join("") + `</div>`
    : `<div class="ct-card">${empty("arrow-repeat", "Zatiaľ žiadne opakované trasy (aspoň 3 jazdy s rovnakým začiatkom a cieľom).")}</div>`);
  routes.forEach((r, i) => chart("chRoute" + i, { type: "bar", data: { labels: r.hours.map(h => h.h + ":00"), datasets: [{ label: "Ø trvanie (min)",
    data: r.hours.map(h => Math.round(h.avg / 60000)), backgroundColor: r.hours.map(h => h === r.best ? C.car : "rgba(37,99,235,.55)"), borderRadius: 6 }] },
    options: { plugins: { legend: { display: false }, tooltip: { callbacks: { afterLabel: (c) => r.hours[c.dataIndex].n + " jázd" } } }, scales: { x: { grid: { display: false } }, y: { title: { display: true, text: "min" } } } } }));
}

// =====================================================================
//  Chôdza a kroky
// =====================================================================
async function pageWalk() {
  const walks = walkTrips(), pw = walkTrips(S.prevTrips);
  const goal = +S.settings.dailyStepGoal || 8000;
  const steps = sum(walks, t => t.steps), dist = sum(walks, t => t.distance_m), time = sum(walks, tripMs);
  const days = new Set(walks.map(t => sod(t.started_at))).size;
  const b = buckets(S.range);
  $("content").innerHTML = printHead("Chôdza a kroky") + `<div class="row g-3">
    ${kpi("person-walking", "ico-blue", "Kroky", int(steps), steps, sum(pw, t => t.steps))}
    ${kpi("geo", "ico-green", "Vzdialenosť", km(dist, 1), dist, sum(pw, t => t.distance_m))}
    ${kpi("stopwatch", "ico-cyan", "Čas chôdze", durH(time), time, sum(pw, tripMs))}
    ${kpi("calendar-day", "ico-violet", "Kroky / aktívny deň", days ? int(steps / days) : "–")}
    ${kpi("bullseye", "ico-amber", "Denný cieľ", int(goal))}
    ${kpi("speedometer2", "ico-rose", "Tempo", dist > 100 ? nf(time / 60000 / (dist / 1000), 1) + " min/km" : "–")}
    ${card("Kroky v čase", `<div class="chart-box lg"><canvas id="chSteps"></canvas></div>`, { col: "col-xl-8", sub: "čiarkovaná čiara = denný cieľ" })}
    ${card("Kedy chodíte", `<div class="chart-box lg"><canvas id="chWalkHour"></canvas></div>`, { col: "col-xl-4" })}
    ${card("Posledný rok", `<div class="heatmap" id="heat"><div class="text-center py-4"><div class="spinner-border spinner-border-sm"></div></div></div>`, { col: "col-12", sub: "kroky po dňoch · orámované = splnený cieľ" })}
  </div>`;
  const perB = b.map(x => sum(walks.filter(t => t.started_at >= x.a && t.started_at < x.z), t => t.steps));
  chart("chSteps", { type: "bar", data: { labels: b.map(x => x.label), datasets: [
    { label: "Kroky", data: perB, backgroundColor: perB.map(v => S.range.period !== "year" && v >= goal ? C.car : C.walk), borderRadius: 6 },
    ...(S.range.period !== "year" ? [{ label: "Cieľ", type: "line", data: b.map(() => goal), borderColor: C.amber, borderDash: [6, 4], pointRadius: 0 }] : [])] },
    options: { scales: { x: { grid: { display: false } } } } });
  const hours = Array(24).fill(0); walks.forEach(t => hours[new Date(t.started_at).getHours()] += t.steps);
  chart("chWalkHour", { type: "bar", data: { labels: hours.map((_, h) => h + ":00"), datasets: [{ label: "Kroky", data: hours, backgroundColor: C.walk, borderRadius: 4 }] },
    options: { indexAxis: "y", plugins: { legend: { display: false } }, scales: { y: { grid: { display: false } } } } });
  // ročná heatmapa
  const year = await fetchTrips(sod(Date.now()) - 364 * DAY, Date.now() + DAY, "walk").catch(() => []);
  if (S.page !== "chodza") return;
  const per = {}; year.forEach(t => { const d = sod(t.started_at); per[d] = (per[d] || 0) + t.steps; });
  const start = sod(Date.now()) - 364 * DAY, startDow = (new Date(start).getDay() + 6) % 7, cell = 13, gap = 3;
  const max = Math.max(goal, ...Object.values(per));
  let svg = "", streak = 0, best = 0, run = 0;
  for (let i = 0; i < 365; i++) {
    const d = sod(start + i * DAY + 3 * 3.6e6), v = per[d] || 0;
    const col = Math.floor((i + startDow) / 7), row = (i + startDow) % 7;
    const a = v ? .18 + .82 * Math.min(1, v / max) : 0;
    svg += `<rect x="${30 + col * (cell + gap)}" y="${18 + row * (cell + gap)}" width="${cell}" height="${cell}" rx="3" fill="${v ? `rgba(37,99,235,${a.toFixed(2)})` : css("--ct-grid")}" ${v >= goal ? `stroke="#f59e0b" stroke-width="2"` : ""}><title>${dt(d)}: ${int(v)} krokov</title></rect>`;
    if (new Date(d).getDate() === 1) svg += `<text x="${30 + col * (cell + gap)}" y="12" font-size="10" fill="${css("--ct-muted")}">${new Date(d).toLocaleDateString("sk-SK", { month: "short" })}</text>`;
    run = v >= goal ? run + 1 : 0; best = Math.max(best, run);
  }
  for (let i = 364; i >= 0; i--) { const v = per[sod(start + i * DAY + 3 * 3.6e6)] || 0; if (v >= goal) streak++; else if (i < 364) break; }
  ["Po", "", "St", "", "Pi", "", "Ne"].forEach((n, r) => { if (n) svg += `<text x="0" y="${28 + r * (cell + gap)}" font-size="10" fill="${css("--ct-muted")}">${n}</text>`; });
  const W = 30 + 54 * (cell + gap);
  $("heat").innerHTML = `<svg viewBox="0 0 ${W} ${18 + 7 * (cell + gap)}" width="${W}">${svg}</svg>
    <div class="d-flex flex-wrap gap-3 mt-2 small"><span><i class="bi bi-fire text-warning"></i> aktuálna séria: <b>${streak}</b> dní</span><span><i class="bi bi-trophy text-primary"></i> najdlhšia séria: <b>${best}</b> dní</span>
    <span>dní so splneným cieľom: <b>${Object.values(per).filter(v => v >= goal).length}</b></span><span>spolu za rok: <b>${int(sum(Object.values(per), v => v))}</b> krokov</span></div>`;
}

// =====================================================================
//  Miesta
// =====================================================================
function pagePlaces() {
  const places = S.items.place;
  const kinds = { home: ["house-door-fill", "Domov"], work: ["building", "Práca"], client: ["briefcase-fill", "Klient"], other: ["geo-alt-fill", "Iné"] };
  const visits = (p) => S.trips.filter(t => t.start_place === p.name || t.end_place === p.name);
  $("content").innerHTML = `<div class="row g-3">
    ${card("Mapa miest", `<div class="map-box" id="placeMap" style="height:520px"></div>`, { col: "col-xl-7" })}
    ${card("Miesta", places.length ? `<div class="list-ct">${places.map((p, i) => { const v = visits(p), k = kinds[p.kind] || kinds.other;
      return `<div class="item" role="button" data-pl="${i}"><i class="bi bi-${k[0]} fs-4 text-danger"></i><div class="flex-grow-1"><div class="fw-semibold">${esc(p.name)}</div>
        <div class="small text-body-secondary">${k[1]} · okruh ${int(p.radius_m || 150)} m${p.category ? " · " + (p.category === "business" ? "služobné" : "súkromné") : ""}</div></div>
        <div class="text-end"><div class="fw-bold">${v.length}×</div><div class="small text-body-secondary">v období</div></div></div>`; }).join("")}</div>`
      : empty("geo-alt", "Žiadne miesta – pridajte ich v aplikácii (Nastavenia → Miesta)."), { col: "col-xl-5" })}
  </div>`;
  const m = makeMap("placeMap"); if (!m) return;
  const g = L.featureGroup().addTo(m), markers = [];
  places.forEach(p => { if (p.lat == null) return;
    L.circle([+p.lat, +p.lng], { radius: +p.radius_m || 150, color: "#e11d48", weight: 1.5, fillOpacity: .15 }).addTo(g);
    markers.push(L.marker([+p.lat, +p.lng]).bindPopup(`<b>${esc(p.name)}</b><br>${visits(p).length} návštev v období`).addTo(g)); });
  if (g.getLayers().length) m.fitBounds(g.getBounds(), { padding: [40, 40], maxZoom: 15 });
  document.querySelectorAll("[data-pl]").forEach(el => el.onclick = () => { const p = places[+el.dataset.pl]; if (p.lat != null) { m.setView([+p.lat, +p.lng], 16); markers[+el.dataset.pl]?.openPopup(); } });
}

// =====================================================================
//  Účet
// =====================================================================
async function pageAccount() {
  const st = await api("backup/status").catch(() => null);
  const s = S.settings;
  const rows = [["Automatická detekcia", s.autoDetection === false ? "vypnutá" : "zapnutá"], ["Rýchlosť potvrdzujúca auto", (s.minDriveSpeedKmh ?? "–") + " km/h"],
    ["Ukončiť po státí", (s.stopDelayMin ?? "–") + " min"], ["Sledovanie chôdze", s.walkTracking === false ? "vypnuté" : "zapnuté"], ["Vzdialenosť chôdze", s.walkDistanceGps ? "z GPS" : "z krokov"],
    ["Dĺžka kroku", (s.strideCm ?? "–") + " cm"], ["Denný cieľ krokov", int(s.dailyStepGoal || 8000)], ["Synchronizácia", s.autoSync === false ? "ručne" : "automaticky" + (s.syncWifiOnly ? " (iba Wi-Fi)" : "")]];
  $("content").innerHTML = `<div class="row g-3">
    ${card("Účet", `<div class="d-flex align-items-center gap-3 mb-3"><div class="brand-logo"><i class="bi bi-person-fill"></i></div><div><div class="fw-bold fs-5">${esc(S.user?.name || "")}</div><div class="text-body-secondary">${esc(S.user?.email || "")}</div></div></div>
      <div class="small text-body-secondary mb-1">Server API</div><div class="mb-3"><code>${esc(S.api)}</code></div>
      <button class="btn btn-outline-danger" id="accLogout"><i class="bi bi-box-arrow-right me-1"></i>Odhlásiť</button>`, { col: "col-lg-6" })}
    ${card("Záloha na serveri", st ? `<div class="row g-2">${[["Jazdy a chôdza", int(st.trips)], ["Vozidlá", int(st.vehicles)], ["Zdieľané vozidlá", int(st.shared_vehicles)],
        ["Miesta, tankovania, pripomienky", int(st.items)], ["Nastavenia", st.settings ? "uložené ✓" : "nie sú"]].map(([l, v]) => `<div class="col-6"><div class="stat-mini"><div class="v">${v}</div><div class="l">${l}</div></div></div>`).join("")}</div>
        <div class="small text-body-secondary mt-3">Po prihlásení na novom telefóne sa všetko stiahne do aplikácie.</div>` : `<div class="alert alert-warning">Stav zálohy nie je dostupný (starší server).</div>`, { col: "col-lg-6" })}
    ${card("Nastavenia aplikácie", Object.keys(s).length ? `<div class="row g-2">${rows.map(([l, v]) => `<div class="col-6 col-md-3"><div class="stat-mini"><div class="v">${esc(v)}</div><div class="l">${l}</div></div></div>`).join("")}</div>
      <div class="small text-body-secondary mt-3">Nastavenia sa menia v aplikácii v telefóne; tu sú len na prehľad.</div>` : empty("gear", "Nastavenia ešte neboli z aplikácie uložené na server."))}
  </div>`;
  $("accLogout").onclick = logout;
}

// =====================================================================
//  Ovládanie a štart
// =====================================================================
document.querySelectorAll("#periodSeg button").forEach(b => b.onclick = () => {
  S.period = b.dataset.p; S.offset = 0; LS.set("ct_period", S.period);
  document.querySelectorAll("#periodSeg button").forEach(x => x.classList.toggle("active", x === b)); render();
});
$("prev").onclick = () => { S.offset--; render(); };
$("next").onclick = () => { if (S.offset < 0) { S.offset++; render(); } };
$("vehicleSel").onchange = (e) => { S.vehicle = e.target.value; LS.set("ct_vehicle", S.vehicle); render(); };
$("reload").onclick = async () => { for (const k in pointCache) delete pointCache[k]; S.loadedKey = ""; await loadBase().catch(() => {}); render(); toast("Údaje obnovené"); };

async function start() {
  $("login").classList.add("d-none"); $("app").classList.remove("d-none");
  $("who").textContent = S.user?.name || ""; $("sideUser").textContent = S.user?.email || S.user?.name || "";
  document.querySelectorAll("#periodSeg button").forEach(x => x.classList.toggle("active", x.dataset.p === S.period));
  setTheme(document.documentElement.dataset.bsTheme || "light", false);
  loading(true);
  try { await loadBase(); } catch (e) { loading(false); $("content").innerHTML = `<div class="alert alert-danger">${esc(e.message)}</div>`; return; }
  loading(false);
  route();
}
if (S.token) start(); else $("login").classList.remove("d-none");
