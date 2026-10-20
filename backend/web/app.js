/* Trasník – webový prehľad. Bootstrap 5.3 + DataTables 2 (Responsive, Buttons) + Chart.js 4 + Leaflet 1.9 */
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
const strip = (h) => String(h ?? "").replace(/<[^>]*>/g, " ").replace(/&nbsp;/g, " ").replace(/\s+/g, " ").trim();
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
// ---- Prihlásenie cez QR kód z aplikácie (párovanie) ----
const PAIR = { code: null, timer: null, until: 0 };
function stopPairing() { clearTimeout(PAIR.timer); PAIR.timer = null; }
async function startPairing() {
  stopPairing();
  if ($("login").classList.contains("d-none")) return;
  $("qrState").textContent = "Pripravujem kód…"; $("qrImg").classList.add("dim");
  try {
    const r = await api("pair/start", { method: "POST", body: "{}" });
    PAIR.code = r.code; PAIR.until = Date.now() + r.expires_in * 1000;
    const text = "trasnik://pair?c=" + r.code + "&api=" + encodeURIComponent(S.api);
    if (window.qrcode) { const q = qrcode(0, "M"); q.addData(text); q.make(); $("qrImg").innerHTML = q.createSvgTag({ cellSize: 4, margin: 0, scalable: true }); }
    else $("qrImg").innerHTML = '<div class="small text-body-secondary p-3">QR knižnica sa nenačítala – použite kód nižšie</div>';
    $("qrImg").classList.remove("dim");
    $("qrShort").textContent = r.short.slice(0, 3) + " " + r.short.slice(3);
    pollPairing();
  } catch (e) {
    $("qrState").textContent = e.message; $("qrShort").textContent = "––– –––";
    PAIR.timer = setTimeout(startPairing, 15000);
  }
}
async function pollPairing() {
  if ($("login").classList.contains("d-none")) return stopPairing();
  const left = Math.max(0, Math.round((PAIR.until - Date.now()) / 1000));
  $("qrState").textContent = `Čakám na potvrdenie v telefóne · kód platí ${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`;
  if (left <= 0) return startPairing();
  try {
    const r = await api("pair/status?code=" + PAIR.code);
    if (r.status === "approved") {
      stopPairing();
      const keep = $("rememberQr").checked;
      S.token = r.token; S.user = r.user;
      LS.set("ct_api", S.api); LS.set("ct_token", r.token, keep); LS.set("ct_user", JSON.stringify(r.user), keep);
      start(); toast("Prihlásené cez telefón ✓"); return;
    }
    if (r.status === "expired") return startPairing();
  } catch {}
  PAIR.timer = setTimeout(pollPairing, 2000);
}
$("apiUrl").addEventListener("change", () => { const v = $("apiUrl").value.trim(); if (v) { S.api = v; startPairing(); } });

function logout() {
  if (S.token) fetch((S.api.endsWith("/") ? S.api : S.api + "/") + "logout", { method: "POST", headers: { Authorization: "Bearer " + S.token } }).catch(() => {});
  S.token = null; LS.del("ct_token"); LS.del("ct_user");
  $("app").classList.add("d-none"); $("login").classList.remove("d-none");
  startPairing();
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
  // OpenStreetMap (bez kľúča); tmavá mapa = prefarbené dlaždice (CSS filter v app.css)
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    { maxZoom: 19, attribution: '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a>',
      className: dark ? "tiles-dark" : "" }).addTo(m);
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
      tr = `<span class="trend ${p === 0 ? "flat" : good ? "up" : "down"}" title="oproti predchádzajúcemu obdobiu"><i class="bi bi-arrow-${p >= 0 ? "up" : "down"}-right"></i>${Math.abs(p)} %<span class="vs ms-1">vs. predtým</span></span>`;
    } else if (cur > 0) tr = `<span class="trend flat">nové</span>`;
  }
  return `<div class="col-6 col-md-4 kpi-col"><div class="ct-card kpi"><div class="ico ${cls}"><i class="bi bi-${icon}"></i></div>
    <div class="min-w-0 w-100"><div class="lbl">${label}</div><div class="val" title="${strip(String(value))}">${value}</div>${tr}</div></div></div>`;
}
function card(title, body, opts = {}) {
  return `<div class="${opts.col || "col-12"}"><div class="ct-card">
    <div class="ct-head"><h2>${title}</h2>${opts.sub ? `<span class="sub">· ${opts.sub}</span>` : ""}${opts.tools ? `<div class="tools noprint">${opts.tools}</div>` : ""}</div>
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
//  Tabuľky – DataTables 2 (Responsive: na úzkom displeji sa stĺpce zbalia do riadku, Buttons: Excel/CSV/tlač/stĺpce)
// =====================================================================
const DT_LANG = {
  decimal: ",", thousands: " ", emptyTable: "Žiadne záznamy", info: "_START_–_END_ z _TOTAL_", infoEmpty: "0 záznamov",
  infoFiltered: "(z _MAX_)", lengthMenu: "_MENU_ na stranu", loadingRecords: "Načítavam…", processing: "Spracúvam…",
  search: "", searchPlaceholder: "Hľadať…", zeroRecords: "Nič sa nenašlo",
  paginate: { first: "«", last: "»", next: "›", previous: "‹" },
};
const tables = {};
const tableFilters = {};
if (window.DataTable) DataTable.ext.search.push((st, _d, _i, row) => !tableFilters[st.nTable.id] || tableFilters[st.nTable.id](row));
const csvVal = (v) => typeof v === "number" ? String(v).replace(".", ",") : v;
function destroyTables() { for (const k in tables) { try { tables[k].destroy(); } catch {} delete tables[k]; } }
/**
 * Tabuľka v prvku [host]. cols: { t: nadpis, d: (r) => HTML, s: (r) => hodnota na triedenie, x: (r) => hodnota pre Excel/CSV,
 * cls: trieda, pri: priorita pri zbaľovaní (1 = zostane najdlhšie) }. o: order, pageLength, foot: (riadky) => [päta], file, title, …
 */
function grid(host, rows, cols, o = {}) {
  const el = $(host); if (!el) return null;
  const id = host + "T";
  if (tables[id]) { try { tables[id].destroy(); } catch {} delete tables[id]; }
  const foot = o.foot ? `<tfoot><tr><th class="dtr-control"></th>${cols.map(c => `<th class="${c.cls || ""}"></th>`).join("")}</tr></tfoot>` : "";
  el.innerHTML = `<table id="${id}" class="table table-ct table-hover ${o.sm ? "table-sm" : ""}">${foot}</table>`;
  const tbl = $(id);
  if (typeof DataTable === "undefined") {   // knižnica sa nenačítala – jednoduchá tabuľka
    tbl.innerHTML = `<thead><tr>${cols.map(c => `<th class="${c.cls || ""}">${c.t}</th>`).join("")}</tr></thead><tbody>${rows.map(r => `<tr ${r.uuid && o.rowTrip !== false ? `data-trip="${r.uuid}"` : ""}>${cols.map(c => `<td class="${c.cls || ""}">${c.d(r)}</td>`).join("")}</tr>`).join("")}</tbody>`;
    el.innerHTML = `<div class="table-responsive">${el.innerHTML}</div>`;
    $(id).addEventListener("click", (e) => { const tr = e.target.closest("tr[data-trip]"); if (tr) openTrip(tr.dataset.trip); });
    return null;
  }
  const columns = [{ data: null, title: "", className: "dtr-control", orderable: false, searchable: false, render: () => "" }].concat(cols.map(c => ({
    data: null, title: c.t, className: (c.cls || "") + (/text-end/.test(c.cls || "") ? "" : " ta-start"), orderable: c.sort !== false, responsivePriority: c.pri ?? 50,
    render: (_x, type, r) => {
      if (type === "display") return c.d(r);
      if (type === "sort" || type === "type") return c.s ? c.s(r) : strip(c.d(r));
      if (type === "export") return c.x ? c.x(r) : strip(c.d(r));
      if (type === "csv") return csvVal(c.x ? c.x(r) : strip(c.d(r)));
      return strip(c.d(r)) + (c.f ? " " + c.f(r) : "");
    },
  })));
  const ex = (orth) => ({ orthogonal: orth, columns: ":not(.dtr-control)" });
  const file = (o.file || "export").replace(/[\s/\\]+/g, "-");
  const buttons = o.buttons === false ? null : {
    dom: { button: { className: "btn btn-sm btn-outline-secondary" } },
    buttons: [
      { extend: "excelHtml5", text: '<i class="bi bi-file-earmark-excel me-1"></i>Excel', title: o.title || null, filename: file, exportOptions: ex("export"), footer: !!o.foot },
      { extend: "csvHtml5", text: '<i class="bi bi-filetype-csv me-1"></i>CSV', filename: file, fieldSeparator: ";", bom: true, charset: "utf-8", exportOptions: ex("csv"), footer: !!o.foot },
      { text: '<i class="bi bi-printer me-1"></i>Tlač', action: () => print() },
      { extend: "colvis", text: '<i class="bi bi-layout-three-columns me-1"></i>Stĺpce', columns: ":not(.dtr-control)" },
    ],
  };
  const paging = o.paging !== false && rows.length > (o.pageLength || 25);
  const t = new DataTable(tbl, {
    data: rows, columns, autoWidth: false, language: DT_LANG, deferRender: true,
    responsive: { details: { type: "column", target: 0, renderer: (_api, _row, cols) => {
      // zbalené stĺpce ako zoznam „názov: hodnota“ – prázdne sa vynechajú
      const li = cols.filter(c => c.hidden && c.title && strip(String(c.data)) !== "")
        .map(c => `<li><span class="dtr-title">${c.title}</span><span class="dtr-data">${c.data}</span></li>`).join("");
      return li ? `<ul class="dtr-details">${li}</ul>` : false;
    } } },
    order: o.order || [[1, "desc"]],
    paging, pageLength: o.pageLength || 25,
    lengthMenu: (() => { const l = [...new Set([o.pageLength || 25, 10, 25, 50, 100])].sort((a, b) => a - b); return [[...l, -1], [...l, "všetky"]]; })(),
    layout: {
      topStart: buttons ? { buttons } : null,
      topEnd: o.search === false ? null : "search",
      bottomStart: paging ? ["pageLength", "info"] : (o.info === false ? null : "info"),
      bottomEnd: paging ? "paging" : null,
    },
    createdRow: (row, r) => { if (o.rowTrip !== false && r.uuid) row.dataset.trip = r.uuid; },
    footerCallback: o.foot ? function () {
      const api = this.api(), f = o.foot(api.rows({ search: "applied" }).data().toArray());
      cols.forEach((_c, i) => { const cell = api.column(i + 1).footer(); if (cell) cell.innerHTML = f[i] ?? ""; });
    } : undefined,
  });
  tbl.querySelector("tbody").addEventListener("click", (e) => {
    if (e.target.closest("td.dtr-control, tr.child, a, button")) return;
    const tr = e.target.closest("tr[data-trip]"); if (tr) openTrip(tr.dataset.trip);
  });
  tables[id] = t;
  return t;
}
// Pri tlači vždy všetky riadky (stránkovanie by vytlačilo len aktuálnu stranu)
const printLens = {};
window.addEventListener("beforeprint", () => { for (const k in tables) { printLens[k] = tables[k].page.len(); if (printLens[k] !== -1) tables[k].page.len(-1).draw(false); } });
window.addEventListener("afterprint", () => { for (const k in tables) if (printLens[k] && printLens[k] !== -1) tables[k].page.len(printLens[k]).draw(false); });

// =====================================================================
//  Smerovanie
// =====================================================================
const PAGES = {
  prehlad: ["Dashboard", pageDashboard], analyzy: ["Analýzy", pageAnalytics], jazdy: ["Jazdy a chôdza", pageTrips], mapa: ["Mapa", pageMap],
  den: ["Časová os dňa", pageDay], kniha: ["Kniha jázd", pageLogbook], nahrady: ["Cestovné náhrady", pageReimb],
  vozidla: ["Vozidlá a tankovanie", pageVehicles], trasy: ["Časté trasy", pageRoutes], chodza: ["Chôdza a kroky", pageWalk],
  miesta: ["Miesta", pagePlaces], ucet: ["Účet a server", pageAccount],
};
function route() {
  const p = (location.hash.replace(/^#\/?/, "").split("?")[0]) || "prehlad";
  S.page = PAGES[p] ? p : "prehlad";
  document.querySelectorAll("#nav .nav-link, #bottomNav a[data-page]").forEach(a => a.classList.toggle("active", a.dataset.page === S.page));
  $("pageTitle").textContent = PAGES[S.page][0];
  $("periodBar").classList.toggle("d-none", ["den", "ucet", "trasy"].includes(S.page));
  bootstrap.Offcanvas.getInstance($("sidebar"))?.hide();
  window.scrollTo({ top: 0 });
  render();
}
async function render() {
  Object.keys(charts).forEach(k => { charts[k].destroy(); delete charts[k]; });
  destroyTables();
  for (const k in tableFilters) delete tableFilters[k];
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
    ${card("Podľa vozidiel", S.vehicles.length ? `<div class="chart-box lg"><canvas id="chVeh"></canvas></div>` : empty("car-front", "Žiadne vozidlá"), { col: "col-md-5 col-xl-4" })}

    ${card("Kedy jazdíte", `<div class="chart-box lg"><canvas id="chHour"></canvas></div>`, { col: "col-md-7 col-xl-6", sub: "začiatky jázd podľa hodiny" })}
    ${card("Dni v týždni", `<div class="chart-box"><canvas id="chDow"></canvas></div>`, { col: "col-xl-6", sub: "km a kroky" })}

    ${card("Mapa obdobia", `<div class="map-box" id="dashMap"></div><div class="small text-body-secondary mt-2">Posledných 30 jázd obdobia · <a href="#/mapa">celá mapa</a></div>`, { col: "col-xl-7" })}
    ${card("Posledné záznamy", recentList(S.trips.slice(-8).reverse()), { col: "col-xl-5", tools: '<a class="btn btn-sm btn-outline-secondary" href="#/jazdy">Všetky</a>' })}

    ${card("Štýl jazdy", styleBlock(cars), { col: "col-md-6 col-xl-4" })}
    ${card("Rekordy obdobia", recordsBlock(cars, walks), { col: "col-md-6 col-xl-4" })}
    ${card("Pripomienky", remindersBlock(reminders), { col: "col-md-12 col-xl-4", tools: '<a class="btn btn-sm btn-outline-secondary" href="#/vozidla">Vozidlá</a>' })}
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
    <div class="text-end text-nowrap"><div class="fw-bold">${km(t.distance_m)}</div>${isWalk(t) ? "" : `<div class="small text-body-secondary">${eur(cost(t))}</div>`}</div></div>`).join("") + `</div>`;
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
    <div class="map-box mb-3" id="tripMap" style="height:min(300px, 40vh)"></div>
    <div class="row g-2 mb-3">${stats.map(([l, val]) => `<div class="col-6 col-md-3"><div class="stat-mini"><div class="v">${val}</div><div class="l">${l}</div></div></div>`).join("")}</div>
    ${isWalk(t) ? "" : `<h6 class="fw-bold">Rýchlosť počas jazdy</h6><div class="chart-box sm mb-1"><canvas id="chTripSpeed"></canvas></div><div class="small text-body-secondary mb-3" id="speedInfo"></div>`}
    <dl class="row small mb-0 g-1">
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
const TS = { type: "", cat: "" };
const routeCell = (t) => `<div class="route"><span class="from">${esc(placeShort(t.start_place, t.start_address))}</span><span class="to">→ ${esc(placeShort(t.end_place, t.end_address))}</span></div>${t.note ? `<div class="small text-body-secondary text-truncate" style="max-width:340px">${esc(t.note)}</div>` : ""}`;
const routeText = (t) => placeShort(t.start_place, t.start_address) + " → " + placeShort(t.end_place, t.end_address);
function pageTrips() {
  const list = S.trips.filter(t => isWalk(t) || !S.vehicle || t.vehicle_uuid === S.vehicle);
  const cars = list.filter(t => !isWalk(t)), walks = list.filter(isWalk);
  $("content").innerHTML = printHead("Jazdy a chôdza") + `<div class="row g-3">
    ${kpi("signpost-2", "ico-green", "Jázd autom", int(cars.length))}${kpi("speedometer", "ico-blue", "Km autom", km(sum(cars, t => t.distance_m)))}
    ${kpi("briefcase", "ico-violet", "Služobné", km(sum(cars.filter(t => t.category === "business"), t => t.distance_m)))}${kpi("fuel-pump", "ico-amber", "Náklady", eur(sum(cars, cost)))}
    ${kpi("person-walking", "ico-rose", "Chôdza", int(walks.length) + "×")}${kpi("geo", "ico-cyan", "Kroky", int(sum(walks, t => t.steps)))}
    ${card("Všetky záznamy obdobia", `
      <div class="filters mb-2 noprint">
        <select class="form-select form-select-sm" id="tt" aria-label="Druh"><option value="">Jazdy aj chôdza</option><option value="car">Iba jazdy autom</option><option value="walk">Iba chôdza</option></select>
        <select class="form-select form-select-sm" id="tc" aria-label="Typ jazdy"><option value="">Služobné aj súkromné</option><option value="business">Iba služobné</option><option value="private">Iba súkromné</option></select>
      </div>
      <div id="tripsGrid"></div>`, { sub: "ťuknite na riadok pre detail" })}
  </div>`;
  $("tt").value = TS.type; $("tc").value = TS.cat;
  tableFilters.tripsGridT = (t) => (!TS.type || t.type === TS.type) && (!TS.cat || (!isWalk(t) && t.category === TS.cat));
  const g = grid("tripsGrid", list, [
    { t: "Dátum", pri: 1, cls: "text-nowrap", d: t => `<div class="fw-semibold">${dt(t.started_at)}</div><div class="small text-body-secondary">${tm(t.started_at)}–${t.ended_at ? tm(t.ended_at) : ""}</div>`,
      s: t => t.started_at, x: t => dt(t.started_at) + " " + tm(t.started_at) },
    { t: "Trasa", pri: 2, d: routeCell, x: routeText, f: t => (t.start_address || "") + " " + (t.end_address || "") + " " + (t.note || "") },
    { t: "Typ", pri: 6, d: typeBadge, x: t => isWalk(t) ? "chôdza" : t.category === "business" ? "služobná" : "súkromná" },
    { t: "Vozidlo", pri: 9, cls: "small", d: t => esc(veh(t)?.name || "") },
    { t: "km", pri: 3, cls: "text-end fw-semibold", d: t => nf(t.distance_m / 1000, 1), s: t => t.distance_m, x: t => +(t.distance_m / 1000).toFixed(2) },
    { t: "Trvanie", pri: 5, cls: "text-end text-nowrap", d: t => dur(tripMs(t)), s: tripMs, x: t => Math.round(tripMs(t) / 60000) },
    { t: "Ø km/h", pri: 10, cls: "text-end", d: t => isWalk(t) ? nf(t.avg_speed_kmh, 1) : int(t.avg_speed_kmh), s: t => t.avg_speed_kmh, x: t => Math.round(t.avg_speed_kmh) },
    { t: "Max", pri: 11, cls: "text-end", d: t => isWalk(t) ? "" : int(t.max_speed_kmh), s: t => t.max_speed_kmh, x: t => isWalk(t) ? "" : Math.round(t.max_speed_kmh) },
    { t: "Skóre", pri: 8, cls: "text-end", d: t => t.score ?? "", s: t => t.score ?? -1, x: t => t.score ?? "" },
    { t: "Náklady / kroky", pri: 4, cls: "text-end text-nowrap", d: t => isWalk(t) ? int(t.steps) + " kr." : eur(cost(t)), s: t => isWalk(t) ? t.steps / 1e6 : cost(t), x: t => isWalk(t) ? t.steps : +cost(t).toFixed(2) },
  ], {
    order: [[1, "desc"]], file: `jazdy-${S.range.title}`, title: `Jazdy a chôdza – ${S.range.title}`,
    foot: (r) => [`${r.length} záznamov`, "", "", "", nf(sum(r, t => t.distance_m) / 1000, 1), durH(sum(r, tripMs)), "", "", "", r.some(t => !isWalk(t)) ? eur(sum(r.filter(t => !isWalk(t)), cost)) : int(sum(r, t => t.steps)) + " kr."],
  });
  const redraw = () => g?.draw();
  $("tt").onchange = (e) => { TS.type = e.target.value; redraw(); };
  $("tc").onchange = (e) => { TS.cat = e.target.value; redraw(); };
}


// =====================================================================
//  Analýzy – trend 12 mesiacov, kedy jazdíte (teplotná mapa), dĺžky jázd, rýchlosť × vzdialenosť,
//  štýl po týždňoch; tabuľky po mesiacoch, cieľoch, dňoch v týždni a týždňoch (všetky s triedením)
// =====================================================================
const DOW = ["Pondelok", "Utorok", "Streda", "Štvrtok", "Piatok", "Sobota", "Nedeľa"];
const dowOf = (ts) => (new Date(ts).getDay() + 6) % 7;
/** Pondelok týždňa (ms) a označenie „t. 42 / 2026“. */
function weekOf(ts) {
  const d = new Date(sod(ts)); d.setDate(d.getDate() - dowOf(+d));
  const th = new Date(d); th.setDate(th.getDate() + 3);              // ISO: týždeň patrí roku, v ktorom je jeho štvrtok
  const y = th.getFullYear(), jan4 = new Date(y, 0, 4);
  const w = 1 + Math.round(((+th - +jan4) / DAY - 3 + (jan4.getDay() + 6) % 7) / 7);
  return { a: +d, label: `t. ${w} / ${y}`, range: dtShort(+d) + "–" + dtShort(+d + 6 * DAY) };
}
const wavg = (arr, f, w) => { const W = sum(arr, w); return W > 0 ? sum(arr, t => f(t) * w(t)) / W : null; };
const scoreAvg = (arr) => { const sc = arr.filter(t => t.score != null); const v = wavg(sc, t => t.score, t => Math.max(t.distance_m, 1)); return v == null ? null : Math.round(v); };
const scoreBadge = (v) => v == null ? "" : `<span class="badge rounded-pill ${v >= 85 ? "text-bg-success" : v >= 70 ? "text-bg-warning" : "text-bg-danger"}">${v}</span>`;
const LEN_BINS = [[0, 2, "do 2 km"], [2, 5, "2–5 km"], [5, 10, "5–10 km"], [10, 20, "10–20 km"], [20, 50, "20–50 km"], [50, 100, "50–100 km"], [100, 1e9, "nad 100 km"]];

async function pageAnalytics() {
  // 12 mesiacov končiacich mesiacom, v ktorom končí zvolené obdobie
  const endD = new Date(S.range.to - 1), mEnd = new Date(endD.getFullYear(), endD.getMonth() + 1, 1), mStart = new Date(mEnd.getFullYear(), mEnd.getMonth() - 12, 1);
  const key = +mStart + "-" + +mEnd;
  if (S.yearKey !== key) { loading(true); try { S.yearTrips = await fetchTrips(+mStart, +mEnd); S.yearKey = key; } finally { loading(false); } }
  const yCars = carTrips(S.yearTrips), yWalks = walkTrips(S.yearTrips), cars = carTrips(), walks = walkTrips();
  const months = Array.from({ length: 12 }, (_, i) => { const a = new Date(mStart.getFullYear(), mStart.getMonth() + i, 1), z = new Date(a.getFullYear(), a.getMonth() + 1, 1);
    return { a: +a, z: +z, label: a.toLocaleDateString("sk-SK", { month: "short", year: "2-digit" }), long: a.toLocaleDateString("sk-SK", { month: "long", year: "numeric" }) }; });
  const yTitle = months[0].long + " – " + months[11].long;

  $("content").innerHTML = printHead("Analýzy") + `<div class="row g-3">
    ${card("Vývoj za 12 mesiacov", `<div class="chart-box lg"><canvas id="anTrend"></canvas></div>`, { col: "col-xl-8", sub: yTitle })}
    ${card("Dĺžka jázd", cars.length ? `<div class="chart-box lg"><canvas id="anLen"></canvas></div>` : empty("rulers", "Žiadne jazdy v období"), { col: "col-xl-4", sub: "počet jázd a podiel km · " + esc(S.range.title) })}
    ${card("Kedy jazdíte", cars.length ? `<div id="anHeat"></div>` : empty("calendar3", "Žiadne jazdy v období"), { col: "col-xl-7", sub: "km podľa dňa a hodiny začiatku · " + esc(S.range.title) })}
    ${card("Rýchlosť × vzdialenosť", cars.length ? `<div class="chart-box lg"><canvas id="anScatter"></canvas></div>` : empty("graph-up", "Žiadne jazdy v období"), { col: "col-xl-5", sub: "každá bodka = jazda, ťuknite pre detail" })}
    ${card("Štýl jazdy po týždňoch", `<div class="chart-box"><canvas id="anStyle"></canvas></div>`, { sub: "skóre a udalosti na 100 km · " + yTitle })}
    ${card("Prehľad po mesiacoch", `<div id="anMonths"></div>`, { sub: yTitle })}
    ${card("Najčastejšie ciele", `<div id="anDest"></div>`, { col: "col-xl-7", sub: esc(S.range.title) })}
    ${card("Dni v týždni", `<div id="anDow"></div>`, { col: "col-xl-5", sub: esc(S.range.title) })}
    ${card("Štýl jazdy po týždňoch – tabuľka", `<div id="anWeeks"></div>`, { sub: yTitle })}
  </div>`;

  // ---- graf 1: 12 mesiacov – služobné / súkromné km, náklady, kroky
  const inM = (arr, m) => arr.filter(t => t.started_at >= m.a && t.started_at < m.z);
  chart("anTrend", { type: "bar", data: { labels: months.map(m => m.label), datasets: [
      { label: "Služobné km", data: months.map(m => +(sum(inM(yCars, m).filter(t => t.category === "business"), t => t.distance_m) / 1000).toFixed(1)), backgroundColor: C.biz, borderRadius: 6, stack: "km" },
      { label: "Súkromné km", data: months.map(m => +(sum(inM(yCars, m).filter(t => t.category !== "business"), t => t.distance_m) / 1000).toFixed(1)), backgroundColor: C.car, borderRadius: 6, stack: "km" },
      { label: "Náklady €", type: "line", data: months.map(m => +sum(inM(yCars, m), cost).toFixed(2)), borderColor: C.amber, backgroundColor: C.amber, tension: .35, yAxisID: "y2", pointRadius: 3 },
      { label: "Kroky (tis.)", type: "line", data: months.map(m => +(sum(inM(yWalks, m), t => t.steps) / 1000).toFixed(1)), borderColor: C.walk, backgroundColor: C.walk, borderDash: [5, 4], tension: .35, yAxisID: "y3", pointRadius: 2, hidden: !yWalks.length },
    ] },
    options: { interaction: { mode: "index", intersect: false }, scales: {
      x: { stacked: true, grid: { display: false } }, y: { stacked: true, title: { display: true, text: "km" } },
      y2: { position: "right", grid: { display: false }, title: { display: true, text: "€" } }, y3: { display: false } } } });

  // ---- graf 2: dĺžky jázd – počet (stĺpce) a podiel na km (čiara)
  if (cars.length) {
    const totalKm = sum(cars, t => t.distance_m) || 1;
    const bins = LEN_BINS.map(([a, z, l]) => { const g = cars.filter(t => t.distance_m / 1000 >= a && t.distance_m / 1000 < z); return { l, n: g.length, share: sum(g, t => t.distance_m) / totalKm * 100 }; });
    chart("anLen", { type: "bar", data: { labels: bins.map(b => b.l), datasets: [
        { label: "Počet jázd", data: bins.map(b => b.n), backgroundColor: C.cyan, borderRadius: 6 },
        { label: "% najazdených km", type: "line", data: bins.map(b => +b.share.toFixed(1)), borderColor: C.rose, backgroundColor: C.rose, yAxisID: "y2", tension: .3 } ] },
      options: { interaction: { mode: "index", intersect: false }, scales: { x: { grid: { display: false } }, y: { ticks: { precision: 0 }, title: { display: true, text: "jazdy" } },
        y2: { position: "right", min: 0, max: 100, grid: { display: false }, ticks: { callback: v => v + " %" } } } } });
  }

  // ---- graf 3: teplotná mapa deň × hodina (km)
  if (cars.length) {
    const H = Array.from({ length: 7 }, () => Array(24).fill(0)), N = Array.from({ length: 7 }, () => Array(24).fill(0));
    cars.forEach(t => { const d = new Date(t.started_at); H[dowOf(t.started_at)][d.getHours()] += t.distance_m / 1000; N[dowOf(t.started_at)][d.getHours()]++; });
    const max = Math.max(...H.flat(), 0.1);
    $("anHeat").innerHTML = `<div class="heat-wrap"><div class="heat">
      <div></div>${Array.from({ length: 24 }, (_, h) => `<div class="hh">${h % 3 === 0 ? h : ""}</div>`).join("")}
      ${H.map((row, d) => `<div class="hd">${DOW[d].slice(0, 2)}</div>` + row.map((v, h) => {
        const a = v > 0 ? 0.15 + 0.85 * v / max : 0;
        return `<div class="hc" style="--a:${a.toFixed(3)}" title="${DOW[d]} ${h}:00–${h}:59 · ${N[d][h]} jázd · ${nf(v, 1)} km"></div>`; }).join("")).join("")}
    </div><div class="heat-legend small text-body-secondary"><span>menej</span><span class="hl"></span><span>viac km</span>
      <span class="ms-auto">najviac: ${(() => { let b = [0, 0]; H.forEach((r, d) => r.forEach((v, h) => { if (v > H[b[0]][b[1]]) b = [d, h]; })); return `${DOW[b[0]]} ${b[1]}:00 (${nf(H[b[0]][b[1]], 1)} km)`; })()}</span></div></div>`;
  }

  // ---- graf 4: Ø rýchlosť × vzdialenosť
  if (cars.length) {
    const pt = (t) => ({ x: Math.max(0.1, +(t.distance_m / 1000).toFixed(2)), y: Math.round(t.avg_speed_kmh), t });
    chart("anScatter", { type: "scatter", data: { datasets: [
        { label: "Služobné", data: cars.filter(t => t.category === "business").map(pt), backgroundColor: C.biz + "cc", pointRadius: 5, pointHoverRadius: 8 },
        { label: "Súkromné", data: cars.filter(t => t.category !== "business").map(pt), backgroundColor: C.car + "cc", pointRadius: 5, pointHoverRadius: 8 } ] },
      options: { scales: { x: cars.some(t => t.distance_m > 50000)
          ? { type: "logarithmic", title: { display: true, text: "km (logaritmická os)" }, ticks: { callback: (v) => [0.1, 0.5, 1, 2, 5, 10, 20, 50, 100, 200, 500, 1000].includes(+v) ? v : "" } }
          : { type: "linear", min: 0, title: { display: true, text: "km" } }, y: { min: 0, title: { display: true, text: "Ø km/h" } } },
        plugins: { tooltip: { callbacks: { label: (c) => { const t = c.raw.t; return ` ${dt(t.started_at)} ${tm(t.started_at)} · ${nf(c.raw.x, 1)} km · Ø ${c.raw.y} km/h · ${routeText(t)}`; } } } },
        onClick: (_e, el, ch) => { if (el.length) openTrip(ch.data.datasets[el[0].datasetIndex].data[el[0].index].t.uuid); },
        onHover: (e, el) => { e.native.target.style.cursor = el.length ? "pointer" : "default"; } } });
  }

  // ---- týždne (spoločné pre graf 5 a tabuľku 4)
  const wk = {};
  yCars.forEach(t => { const w = weekOf(t.started_at); (wk[w.a] ||= { ...w, list: [] }).list.push(t); });
  const weeks = Object.values(wk).sort((a, b) => a.a - b.a).map(w => {
    const k = sum(w.list, t => t.distance_m) / 1000, ms = sum(w.list, tripMs), sp = sum(w.list, t => t.speeding_sec);
    return { ...w, n: w.list.length, km: k, ms, brakes: sum(w.list, t => t.hard_brakes), accels: sum(w.list, t => t.hard_accels),
      per100: k > 0 ? (sum(w.list, t => t.hard_brakes + t.hard_accels)) / k * 100 : 0, sp, spPct: ms > 0 ? sp * 1000 / ms * 100 : 0, score: scoreAvg(w.list),
      maxSpeed: Math.max(0, ...w.list.map(t => t.max_speed_kmh)) };
  });
  chart("anStyle", { type: "bar", data: { labels: weeks.map(w => w.label.replace(/ \/ \d+$/, "")), datasets: [
      { label: "Skóre", type: "line", data: weeks.map(w => w.score), borderColor: C.car, backgroundColor: C.car, tension: .3, yAxisID: "y", spanGaps: true, pointRadius: weeks.length > 20 ? 1.5 : 3 },
      { label: "Prudké udalosti / 100 km", data: weeks.map(w => +w.per100.toFixed(1)), backgroundColor: C.rose + "99", borderRadius: 4, yAxisID: "y2" },
      { label: "% času nad limitom", data: weeks.map(w => +w.spPct.toFixed(1)), backgroundColor: C.amber + "99", borderRadius: 4, yAxisID: "y2" } ] },
    options: { interaction: { mode: "index", intersect: false }, scales: { x: { grid: { display: false } }, y: { min: 0, max: 100, title: { display: true, text: "skóre" } },
      y2: { position: "right", min: 0, grid: { display: false }, title: { display: true, text: "na 100 km / %" } } } } });

  // ---- tabuľka 1: po mesiacoch
  const mRows = months.map(m => {
    const c = inM(yCars, m), w = inM(yWalks, m), k = sum(c, t => t.distance_m) / 1000, ms = sum(c, tripMs);
    return { ...m, n: c.length, km: k, biz: sum(c.filter(t => t.category === "business"), t => t.distance_m) / 1000, ms,
      avg: ms > 0 ? k / (ms / 3.6e6) : 0, per: c.length ? k / c.length : 0, l: sum(c, liters), eur: sum(c, cost), score: scoreAvg(c),
      steps: sum(w, t => t.steps), days: new Set(c.map(t => sod(t.started_at))).size };
  });
  grid("anMonths", mRows, [
    { t: "Mesiac", pri: 1, cls: "text-nowrap fw-semibold", d: r => esc(r.long), s: r => r.a, x: r => r.long },
    { t: "Jazdy", pri: 3, cls: "text-end", d: r => int(r.n), s: r => r.n, x: r => r.n },
    { t: "Km", pri: 2, cls: "text-end fw-semibold", d: r => nf(r.km, 1), s: r => r.km, x: r => +r.km.toFixed(1) },
    { t: "Služobné km", pri: 5, cls: "text-end", d: r => nf(r.biz, 1) + (r.km ? ` <span class="small text-body-secondary">${Math.round(r.biz / r.km * 100)} %</span>` : ""), s: r => r.biz, x: r => +r.biz.toFixed(1) },
    { t: "Súkromné km", pri: 9, cls: "text-end", d: r => nf(r.km - r.biz, 1), s: r => r.km - r.biz, x: r => +(r.km - r.biz).toFixed(1) },
    { t: "Dni s jazdou", pri: 12, cls: "text-end", d: r => int(r.days), s: r => r.days, x: r => r.days },
    { t: "Za volantom", pri: 7, cls: "text-end text-nowrap", d: r => durH(r.ms), s: r => r.ms, x: r => +(r.ms / 3.6e6).toFixed(1) },
    { t: "Ø km/h", pri: 10, cls: "text-end", d: r => r.ms ? int(r.avg) : "", s: r => r.avg, x: r => Math.round(r.avg) },
    { t: "Ø km / jazda", pri: 11, cls: "text-end", d: r => r.n ? nf(r.per, 1) : "", s: r => r.per, x: r => +r.per.toFixed(1) },
    { t: "Palivo l", pri: 13, cls: "text-end", d: r => nf(r.l, 1), s: r => r.l, x: r => +r.l.toFixed(1) },
    { t: "Náklady", pri: 4, cls: "text-end text-nowrap", d: r => eur(r.eur), s: r => r.eur, x: r => +r.eur.toFixed(2) },
    { t: "Ø skóre", pri: 8, cls: "text-end", d: r => scoreBadge(r.score), s: r => r.score ?? -1, x: r => r.score ?? "" },
    { t: "Kroky", pri: 6, cls: "text-end", d: r => int(r.steps), s: r => r.steps, x: r => r.steps },
  ], { order: [[1, "desc"]], pageLength: 12, rowTrip: false, file: "mesiace-" + yTitle, title: "Prehľad po mesiacoch – " + yTitle,
    foot: (r) => ["Spolu", int(sum(r, x => x.n)), nf(sum(r, x => x.km), 1), nf(sum(r, x => x.biz), 1), nf(sum(r, x => x.km - x.biz), 1), int(sum(r, x => x.days)),
      durH(sum(r, x => x.ms)), "", "", nf(sum(r, x => x.l), 1), eur(sum(r, x => x.eur)), "", int(sum(r, x => x.steps))] });

  // ---- tabuľka 2: najčastejšie ciele (podľa miesta, inak ulice a obce)
  const dest = {};
  cars.forEach(t => { const k = placeShort(t.end_place, t.end_address); if (k === "—") return; (dest[k] ||= []).push(t); });
  const dRows = Object.entries(dest).map(([name, l]) => {
    const n = l.length, k = sum(l, t => t.distance_m) / 1000, last = Math.max(...l.map(t => t.started_at));
    const hrs = l.map(t => { const d = new Date(t.ended_at || t.started_at); return d.getHours() * 60 + d.getMinutes(); });
    return { name, isPlace: !!l[0].end_place, n, km: k, per: k / n, ms: sum(l, tripMs) / n, biz: l.filter(t => t.category === "business").length / n * 100,
      eur: sum(l, cost), last, arr: Math.round(sum(hrs, x => x) / n), lastUuid: l.find(t => t.started_at === last)?.uuid };
  });
  grid("anDest", dRows, [
    { t: "Cieľ", pri: 1, d: r => `${r.isPlace ? '<i class="bi bi-geo-alt-fill text-primary me-1"></i>' : ""}<span class="fw-semibold">${esc(r.name)}</span>`, x: r => r.name },
    { t: "Návštevy", pri: 2, cls: "text-end fw-semibold", d: r => int(r.n), s: r => r.n, x: r => r.n },
    { t: "Km spolu", pri: 4, cls: "text-end", d: r => nf(r.km, 1), s: r => r.km, x: r => +r.km.toFixed(1) },
    { t: "Ø km", pri: 6, cls: "text-end", d: r => nf(r.per, 1), s: r => r.per, x: r => +r.per.toFixed(1) },
    { t: "Ø trvanie", pri: 7, cls: "text-end text-nowrap", d: r => dur(r.ms), s: r => r.ms, x: r => Math.round(r.ms / 60000) },
    { t: "Ø príchod", pri: 8, cls: "text-end", d: r => `${Math.floor(r.arr / 60)}:${String(r.arr % 60).padStart(2, "0")}`, s: r => r.arr },
    { t: "Služobne", pri: 9, cls: "text-end", d: r => Math.round(r.biz) + " %", s: r => r.biz, x: r => Math.round(r.biz) },
    { t: "Náklady", pri: 5, cls: "text-end text-nowrap", d: r => eur(r.eur), s: r => r.eur, x: r => +r.eur.toFixed(2) },
    { t: "Naposledy", pri: 3, cls: "text-end text-nowrap", d: r => `<a href="#" data-last="${r.lastUuid}">${dt(r.last)}</a>`, s: r => r.last, x: r => dt(r.last) },
  ], { order: [[2, "desc"]], pageLength: 10, rowTrip: false, file: "ciele-" + S.range.title, title: "Najčastejšie ciele – " + S.range.title,
    foot: (r) => [`${r.length} cieľov`, int(sum(r, x => x.n)), nf(sum(r, x => x.km), 1), "", "", "", "", eur(sum(r, x => x.eur)), ""] });
  $("anDest").addEventListener("click", (e) => { const a = e.target.closest("[data-last]"); if (a) { e.preventDefault(); openTrip(a.dataset.last); } });

  // ---- tabuľka 3: dni v týždni
  const nWeeks = Math.max(1, Math.round((S.range.to - S.range.from) / (7 * DAY)));
  const dRows2 = DOW.map((name, i) => {
    const c = cars.filter(t => dowOf(t.started_at) === i), w = walks.filter(t => dowOf(t.started_at) === i);
    const k = sum(c, t => t.distance_m) / 1000, first = {};
    c.forEach(t => { const d = sod(t.started_at); first[d] = Math.min(first[d] ?? 1e15, t.started_at); });
    const firsts = Object.values(first).map(ts => { const d = new Date(ts); return d.getHours() * 60 + d.getMinutes(); });
    return { i, name, n: c.length, km: k, per: c.length ? k / c.length : 0, ms: sum(c, tripMs), biz: sum(c.filter(t => t.category === "business"), t => t.distance_m) / 1000,
      dep: firsts.length ? Math.round(sum(firsts, x => x) / firsts.length) : null, score: scoreAvg(c), steps: sum(w, t => t.steps), eur: sum(c, cost) };
  });
  grid("anDow", dRows2, [
    { t: "Deň", pri: 1, cls: "fw-semibold", d: r => r.name, s: r => r.i, x: r => r.name },
    { t: "Jazdy", pri: 3, cls: "text-end", d: r => int(r.n), s: r => r.n, x: r => r.n },
    { t: "Km", pri: 2, cls: "text-end fw-semibold", d: r => nf(r.km, 1), s: r => r.km, x: r => +r.km.toFixed(1) },
    { t: "Ø km / deň", pri: 6, cls: "text-end", d: r => nf(r.km / nWeeks, 1), s: r => r.km, x: r => +(r.km / nWeeks).toFixed(1) },
    { t: "Ø jazda", pri: 7, cls: "text-end", d: r => r.n ? nf(r.per, 1) + " km" : "", s: r => r.per, x: r => +r.per.toFixed(1) },
    { t: "Za volantom", pri: 8, cls: "text-end text-nowrap", d: r => durH(r.ms), s: r => r.ms, x: r => +(r.ms / 3.6e6).toFixed(1) },
    { t: "Služobné km", pri: 10, cls: "text-end", d: r => nf(r.biz, 1), s: r => r.biz, x: r => +r.biz.toFixed(1) },
    { t: "Ø prvý odchod", pri: 5, cls: "text-end", d: r => r.dep == null ? "" : `${Math.floor(r.dep / 60)}:${String(r.dep % 60).padStart(2, "0")}`, s: r => r.dep ?? 9999 },
    { t: "Ø skóre", pri: 9, cls: "text-end", d: r => scoreBadge(r.score), s: r => r.score ?? -1, x: r => r.score ?? "" },
    { t: "Kroky", pri: 4, cls: "text-end", d: r => int(r.steps), s: r => r.steps, x: r => r.steps },
  ], { order: [[1, "asc"]], paging: false, search: false, rowTrip: false, sm: true, file: "dni-" + S.range.title, title: "Dni v týždni – " + S.range.title,
    foot: (r) => ["Spolu", int(sum(r, x => x.n)), nf(sum(r, x => x.km), 1), "", "", durH(sum(r, x => x.ms)), nf(sum(r, x => x.biz), 1), "", "", int(sum(r, x => x.steps))] });

  // ---- tabuľka 4: štýl jazdy po týždňoch
  grid("anWeeks", weeks, [
    { t: "Týždeň", pri: 1, cls: "text-nowrap", d: r => `<div class="fw-semibold">${r.label}</div><div class="small text-body-secondary">${r.range}</div>`, s: r => r.a, x: r => r.label + " " + r.range },
    { t: "Jazdy", pri: 4, cls: "text-end", d: r => int(r.n), s: r => r.n, x: r => r.n },
    { t: "Km", pri: 3, cls: "text-end fw-semibold", d: r => nf(r.km, 1), s: r => r.km, x: r => +r.km.toFixed(1) },
    { t: "Brzdenia", pri: 6, cls: "text-end", d: r => int(r.brakes), s: r => r.brakes, x: r => r.brakes },
    { t: "Zrýchlenia", pri: 7, cls: "text-end", d: r => int(r.accels), s: r => r.accels, x: r => r.accels },
    { t: "Udalosti / 100 km", pri: 5, cls: "text-end", d: r => nf(r.per100, 1), s: r => r.per100, x: r => +r.per100.toFixed(1) },
    { t: "Nad limitom", pri: 8, cls: "text-end text-nowrap", d: r => dur(r.sp * 1000) + ` <span class="small text-body-secondary">${nf(r.spPct, 1)} %</span>`, s: r => r.spPct, x: r => Math.round(r.sp / 60) },
    { t: "Max km/h", pri: 9, cls: "text-end", d: r => int(r.maxSpeed), s: r => r.maxSpeed, x: r => Math.round(r.maxSpeed) },
    { t: "Ø skóre", pri: 2, cls: "text-end", d: r => scoreBadge(r.score), s: r => r.score ?? -1, x: r => r.score ?? "" },
  ], { order: [[1, "desc"]], pageLength: 10, rowTrip: false, file: "styl-tyzdne-" + yTitle, title: "Štýl jazdy po týždňoch – " + yTitle,
    foot: (r) => [`${r.length} týždňov`, int(sum(r, x => x.n)), nf(sum(r, x => x.km), 1), int(sum(r, x => x.brakes)), int(sum(r, x => x.accels)),
      nf(sum(r, x => x.km) ? sum(r, x => x.brakes + x.accels) / sum(r, x => x.km) * 100 : 0, 1), dur(sum(r, x => x.sp) * 1000), "", ""] });
}

// =====================================================================
//  Mapa
// =====================================================================
async function pageMap() {
  $("content").innerHTML = `<div class="ct-card"><div class="ct-body pt-3">
    <div class="toggles mb-3 noprint">
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
    <div class="day-bar mb-3 noprint">
      <div class="input-group date-group"><button class="btn btn-outline-secondary" id="dPrev" aria-label="Predchádzajúci deň"><i class="bi bi-chevron-left"></i></button>
        <input type="date" class="form-control" id="dPick" value="${new Date(day - new Date(day).getTimezoneOffset() * 6e4).toISOString().slice(0, 10)}">
        <button class="btn btn-outline-secondary" id="dNext" aria-label="Nasledujúci deň" ${day >= sod(Date.now()) ? "disabled" : ""}><i class="bi bi-chevron-right"></i></button></div>
      <span class="fw-semibold d-none d-md-inline">${dayName(day)}</span>
      <div class="ms-auto actions"><button class="btn btn-outline-secondary" id="dCsv"><i class="bi bi-filetype-csv me-1"></i>CSV</button><button class="btn btn-primary" onclick="print()"><i class="bi bi-printer me-1"></i>Tlač / PDF</button></div>
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
      ${card("Mapa dňa", `<div class="map-box tall" id="dayMap"></div>`, { col: "col-xl-6" })}
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
  const list = carTrips().map((t, i) => ({ ...t, n: i + 1 }));
  const total = sum(list, t => t.distance_m), biz = sum(list.filter(t => t.category === "business"), t => t.distance_m);
  $("content").innerHTML = printHead("Kniha jázd") + `<div class="row g-3">
    ${kpi("signpost-2", "ico-blue", "Jázd", list.length)}${kpi("speedometer", "ico-green", "Spolu", km(total))}
    ${kpi("briefcase", "ico-violet", "Služobné (S)", km(biz))}${kpi("house", "ico-cyan", "Súkromné (P)", km(total - biz))}
    ${kpi("fuel-pump", "ico-amber", "Palivo", nf(sum(list, liters), 1) + " l")}${kpi("cash", "ico-rose", "Náklady", eur(sum(list, cost)))}
    ${card(`Kniha jázd · ${esc(v.name)} ${v.plate ? `<span class="plate ms-1">${esc(v.plate)}</span>` : ""}`, list.length ? `<div id="logGrid"></div>
      <div class="sign-row small text-body-secondary"><span>Dátum: ______________</span><span>Podpis vodiča: ________________________</span></div>`
      : empty("journal", "V období nie sú žiadne jazdy tohto vozidla"))}
  </div>`;
  if (!list.length) return;
  grid("logGrid", list, [
    { t: "#", pri: 12, d: t => t.n, s: t => t.n, x: t => t.n },
    { t: "Dátum", pri: 1, cls: "text-nowrap", d: t => dt(t.started_at), s: t => t.started_at },
    { t: "Čas", pri: 7, cls: "text-nowrap", d: t => `${tm(t.started_at)}–${t.ended_at ? tm(t.ended_at) : ""}`, s: t => t.started_at },
    { t: "Odkiaľ", pri: 2, d: t => esc(t.start_address || t.start_place || "—") },
    { t: "Kam", pri: 2, d: t => esc(t.end_address || t.end_place || "—") },
    { t: "Účel", pri: 5, d: t => esc(t.note || (t.category === "business" ? "služobná cesta" : "")) },
    { t: "Typ", pri: 4, d: t => `<span class="badge ${t.category === "business" ? "badge-biz" : "badge-priv"}">${t.category === "business" ? "S" : "P"}</span>`, x: t => t.category === "business" ? "S" : "P" },
    { t: "km", pri: 3, cls: "text-end fw-semibold", d: t => nf(t.distance_m / 1000, 1), s: t => t.distance_m, x: t => +(t.distance_m / 1000).toFixed(2) },
    { t: "Palivo l", pri: 8, cls: "text-end", d: t => nf(liters(t), 2), s: liters, x: t => +liters(t).toFixed(2) },
    { t: "Náklady €", pri: 6, cls: "text-end", d: t => nf(cost(t), 2), s: cost, x: t => +cost(t).toFixed(2) },
  ], {
    order: [[1, "asc"]], pageLength: 50, file: `kniha-jazd-${v.plate || v.name}-${S.range.title}`, title: `Kniha jázd – ${v.name} ${v.plate || ""} – ${S.range.title}`,
    foot: (r) => [`${r.length} jázd`, "", "", "", "", "", "", nf(sum(r, t => t.distance_m) / 1000, 1), nf(sum(r, liters), 1), nf(sum(r, cost), 2)],
  });
}

// =====================================================================
//  Cestovné náhrady
// =====================================================================
function pageReimb() {
  const v = needVehicle("Výkaz náhrad"); if (!v) return;
  const rate = v.rate_per_km;
  const rows = carTrips().filter(t => t.category === "business").map((t, i) => { const k = t.distance_m / 1000; return { ...t, n: i + 1, k, basic: k * rate, fuel: cost(t) }; });
  const tot = (arr, f) => sum(arr, f);
  $("content").innerHTML = printHead("Cestovné náhrady – použitie vlastného vozidla") + `<div class="row g-3">
    ${kpi("signpost-2", "ico-blue", "Služobných jázd", rows.length)}${kpi("speedometer", "ico-violet", "Služobné km", km(tot(rows, r => r.k * 1000)))}
    ${kpi("cash-stack", "ico-green", "Základná náhrada", eur(tot(rows, r => r.basic)))}${kpi("fuel-pump", "ico-amber", "Náhrada za PHL", eur(tot(rows, r => r.fuel)))}
    ${kpi("wallet2", "ico-rose", "Na vyplatenie", eur(tot(rows, r => r.basic + r.fuel)))}${kpi("calculator", "ico-cyan", "Sadzba", nf(rate, 4) + " €/km")}
    ${rate ? "" : `<div class="col-12"><div class="alert alert-warning mb-0"><i class="bi bi-exclamation-triangle me-1"></i>Pri vozidle nie je nastavená sadzba €/km (v aplikácii: Vozidlá → upraviť). Počíta sa iba náhrada za palivo.</div></div>`}
    ${card(`Výkaz náhrad · ${esc(v.name)}`, rows.length ? `<div class="small text-body-secondary mb-2">Spotreba ${nf(v.consumption, 1)} l/100 km · cena PHL ${nf(v.fuel_price, 3)} €</div><div id="reimbGrid"></div>
      <div class="sign-row small text-body-secondary"><span>Podpis zamestnanca: ________________</span><span>Schválil: ________________</span></div>
      <div class="small text-body-secondary mt-3">Sadzbu a spôsob výpočtu overte podľa platných predpisov a pokynov zamestnávateľa.</div>`
      : empty("briefcase", "V období nie sú žiadne služobné jazdy tohto vozidla"))}
  </div>`;
  if (!rows.length) return;
  grid("reimbGrid", rows, [
    { t: "#", pri: 12, d: r => r.n, s: r => r.n, x: r => r.n },
    { t: "Dátum", pri: 1, cls: "text-nowrap", d: r => dt(r.started_at), s: r => r.started_at },
    { t: "Trasa", pri: 2, d: r => esc(routeText(r)) },
    { t: "Účel", pri: 6, d: r => esc(r.note || "služobná cesta") },
    { t: "km", pri: 4, cls: "text-end", d: r => nf(r.k, 1), s: r => r.k, x: r => +r.k.toFixed(2) },
    { t: "Základná", pri: 7, cls: "text-end text-nowrap", d: r => eur(r.basic), s: r => r.basic, x: r => +r.basic.toFixed(2) },
    { t: "PHL", pri: 8, cls: "text-end text-nowrap", d: r => eur(r.fuel), s: r => r.fuel, x: r => +r.fuel.toFixed(2) },
    { t: "Spolu", pri: 3, cls: "text-end fw-bold text-nowrap", d: r => eur(r.basic + r.fuel), s: r => r.basic + r.fuel, x: r => +(r.basic + r.fuel).toFixed(2) },
  ], {
    order: [[2, "asc"]], pageLength: 50, file: `nahrady-${S.range.title}`, title: `Cestovné náhrady – ${v.name} – ${S.range.title}`,
    foot: (r) => ["Spolu", "", "", "", nf(sum(r, x => x.k), 1), eur(sum(r, x => x.basic)), eur(sum(r, x => x.fuel)), eur(sum(r, x => x.basic + x.fuel))],
  });
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
      <div class="ct-head"><div class="ico-box ico-green"><i class="bi bi-${v.fuel_type === "electric" ? "ev-front" : "car-front-fill"}"></i></div>
        <div class="min-w-0"><h2 class="mb-0">${esc(v.name)} ${v.plate ? `<span class="plate ms-1">${esc(v.plate)}</span>` : ""}</h2>
        <div class="sub">${v.is_owner ? (v.share_count ? `zdieľané s ${v.share_count} vodičmi` : "vlastné vozidlo") : "👥 zdieľané od " + esc(v.owner_name)}</div></div>
        <div class="tools noprint"><button class="btn btn-sm btn-outline-primary" data-shared="${v.uuid}"><i class="bi bi-people me-1"></i>Spoločná kniha</button>
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
        ${refuels.length ? `<h6 class="fw-bold small text-uppercase text-body-secondary mt-3">Tankovania</h6><div id="refGrid${i}"></div>` : `<div class="small text-body-secondary">Zatiaľ žiadne tankovania.</div>`}
      </div></div></div>`;
  }).join("") + `</div>`;
  list.forEach((v, i) => {
    const refuels = S.items.refuel.filter(r => r.vehicle_uuid === v.uuid);
    if (refuels.length) grid("refGrid" + i, refuels, [
      { t: "Dátum", pri: 1, d: r => dt(r.ts) + (r.full_tank === false ? ' <span class="badge badge-priv">čiastočne</span>' : ""), s: r => +r.ts, x: r => dt(r.ts) },
      { t: "Množstvo", pri: 2, cls: "text-end", d: r => nf(r.liters, 2), s: r => +r.liters, x: r => +r.liters },
      { t: "Suma", pri: 3, cls: "text-end text-nowrap", d: r => eur(r.total_price), s: r => +r.total_price, x: r => +r.total_price },
      { t: "€/l", pri: 4, cls: "text-end", d: r => r.liters ? nf(r.total_price / r.liters, 3) : "–", s: r => r.liters ? r.total_price / r.liters : 0 },
      { t: "Tachometer", pri: 5, cls: "text-end", d: r => r.odometer_km ? int(r.odometer_km) : "–", s: r => +r.odometer_km || 0, x: r => +r.odometer_km || "" },
    ], { sm: true, order: [[1, "desc"]], pageLength: 5, search: false, rowTrip: false, file: `tankovania-${v.plate || v.name}`, title: `Tankovania – ${v.name}` });
    const st = refuelStats(refuels);
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
      ${T.length ? `<div id="sharedGrid"></div>` : empty("journal", "Žiadne jazdy v období")}`;
    if (T.length) {
      const g = grid("sharedGrid", T.map((t, i) => ({ ...t, n: i + 1 })), [
        { t: "#", pri: 12, d: t => t.n, s: t => t.n },
        { t: "Dátum", pri: 1, cls: "text-nowrap", d: t => dt(t.started_at), s: t => t.started_at },
        { t: "Čas", pri: 7, cls: "text-nowrap", d: t => `${tm(t.started_at)}–${t.ended_at ? tm(t.ended_at) : ""}`, s: t => t.started_at },
        { t: "Vodič", pri: 2, d: t => esc(t.driver_name) },
        { t: "Odkiaľ", pri: 4, d: t => esc(t.start_address || t.start_place || "—") },
        { t: "Kam", pri: 4, d: t => esc(t.end_address || t.end_place || "—") },
        { t: "Účel", pri: 6, d: t => esc(t.note || "") },
        { t: "Typ", pri: 5, d: t => t.category === "business" ? "S" : "P" },
        { t: "km", pri: 3, cls: "text-end", d: t => nf(t.distance_m / 1000, 1), s: t => t.distance_m, x: t => +(t.distance_m / 1000).toFixed(2) },
      ], { sm: true, order: [[2, "asc"]], rowTrip: false, file: `spolocna-kniha-${v.plate || v.name}-${S.range.title}`, title: `Spoločná kniha jázd – ${v.name} – ${S.range.title}`,
        foot: (r) => [`${r.length} jázd`, "", "", "", "", "", "", "", nf(sum(r, t => t.distance_m) / 1000, 1)] });
      setTimeout(() => g?.columns.adjust().responsive.recalc(), 300);
    }
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
      .map(([l, x]) => `<div class="col-6 col-sm-4 col-xxl-2"><div class="stat-mini"><div class="v">${x}</div><div class="l">${l}</div></div></div>`).join("")}</div>
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
// ---- výpočty chôdze (rovnaké ako v aplikácii – data/WalkAnalytics.kt) ----
const addDays = (ts, n) => { const d = new Date(ts); d.setDate(d.getDate() + n); return +d; };
const pace = (ms, m) => m >= 200 && ms > 0 ? ms / 60000 / (m / 1000) : null;
const paceTxt = (p) => p == null ? "–" : Math.floor(p) + ":" + String(Math.min(59, Math.round((p - Math.floor(p)) * 60))).padStart(2, "0");
function workCfg() {
  const st = S.settings || {};
  return { days: new Set(String(st.workDays ?? "1,2,3,4,5").split(",").map(x => +x).filter(Boolean)), from: st.workFromMin ?? 420, to: st.workToMin ?? 960 };
}
/** Podiel času chôdze v pracovnom čase (pomerne, ak chôdza presahuje začiatok alebo koniec práce). */
function workShare(a, b, w) {
  if (b <= a) { const d = new Date(a), m = d.getHours() * 60 + d.getMinutes(); return w.days.has(dowOf(a) + 1) && m >= w.from && m < w.to ? 1 : 0; }
  let inside = 0;
  for (let d = sod(a); d < b; d = addDays(d, 1)) {
    if (!w.days.has(dowOf(d) + 1)) continue;
    const ws = d + w.from * 60000, we = d + w.to * 60000;
    inside += Math.max(0, Math.min(b, we) - Math.max(a, ws));
  }
  return inside / (b - a);
}
function walkDays(walks, from, to, goal, w) {
  const by = {}; walks.forEach(t => (by[sod(t.started_at)] ||= []).push(t));
  const out = [];
  for (let d = sod(from); d < to; d = addDays(d, 1)) {
    const l = by[d] || [], steps = sum(l, t => t.steps), m = sum(l, t => t.distance_m), ms = sum(l, tripMs);
    out.push({ d, steps, m, ms, n: l.length, work: Math.round(sum(l, t => t.steps * workShare(t.started_at, t.ended_at || t.started_at, w))),
      goal: goal > 0 && steps >= goal, pace: pace(ms, m) });
  }
  return out;
}
function walkGroups(days, keyOf, labelOf) {
  const g = {}; days.forEach(x => (g[keyOf(x.d)] ||= []).push(x));
  return Object.entries(g).map(([k, l]) => { const steps = sum(l, x => x.steps), m = sum(l, x => x.m), ms = sum(l, x => x.ms);
    return { k: +k, ...labelOf(+k, l), days: l.length, steps, m, ms, n: sum(l, x => x.n), goalDays: l.filter(x => x.goal).length,
      best: Math.max(0, ...l.map(x => x.steps)), work: sum(l, x => x.work), avg: steps / l.length, pace: pace(ms, m) }; }).sort((a, b) => a.k - b.k);
}
const weekKey = (d) => addDays(d, -dowOf(d));
function isoWeek(ts) { const d = new Date(ts); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() + 3 - (d.getDay() + 6) % 7);
  const w1 = new Date(d.getFullYear(), 0, 4); return { w: 1 + Math.round(((d - w1) / DAY - 3 + (w1.getDay() + 6) % 7) / 7), y: d.getFullYear() }; }
const dShort = (ts) => new Date(ts).toLocaleDateString("sk-SK", { day: "numeric", month: "numeric" });

async function pageWalk() {
  const goal = +S.settings.dailyStepGoal || 8000, w = workCfg();
  const today = sod(Date.now()), tomorrow = addDays(today, 1), yearAgo = addDays(today, -364);
  const to = Math.min(S.range.to, tomorrow), from = S.range.from;
  if (S.walkYearKey !== today) { loading(true); try { S.walkYear = await fetchTrips(yearAgo, tomorrow, "walk").catch(() => []); S.walkYearKey = today; } finally { loading(false); } }
  if (S.page !== "chodza") return;
  const walks = walkTrips().filter(t => t.started_at < to), pw = walkTrips(S.prevTrips);
  const days = walkDays(walks, from, to, goal, w), yDays = walkDays(S.walkYear.map(normTrip), yearAgo, tomorrow, goal, w);
  const steps = sum(days, x => x.steps), dist = sum(days, x => x.m), time = sum(days, x => x.ms), work = sum(days, x => x.work);
  const active = days.filter(x => x.steps > 0).length, goalDays = days.filter(x => x.goal).length, p = pace(time, dist);
  // série: z celého roka
  let best = 0, run = 0; yDays.forEach(x => { run = x.goal ? run + 1 : 0; best = Math.max(best, run); });
  let cur = 0; for (let i = yDays.length - 1; i >= 0; i--) { if (yDays[i].goal) cur++; else if (i < yDays.length - 1) break; }
  const bestDay = days.filter(x => x.steps > 0).sort((a, b) => b.steps - a.steps)[0], longest = [...walks].sort((a, b) => b.steps - a.steps)[0];
  const weeks = walkGroups(days, weekKey, (k) => { const i = isoWeek(k); return { label: `t. ${i.w} / ${i.y}`, sub: dShort(k) + "–" + dShort(addDays(k, 6)) }; });
  const months = walkGroups(yDays, (d) => +new Date(new Date(d).getFullYear(), new Date(d).getMonth(), 1),
    (k, l) => ({ label: new Date(k).toLocaleDateString("sk-SK", { month: "long", year: "numeric" }), sub: l.length + " dní" }));
  const hm = (m) => Math.floor(m / 60) + ":" + String(m % 60).padStart(2, "0");

  $("content").innerHTML = printHead("Chôdza a kroky") + `<div class="row g-3">
    ${kpi("person-walking", "ico-blue", "Kroky spolu", int(steps), steps, sum(pw, t => t.steps))}
    ${kpi("calendar-day", "ico-cyan", "Ø krokov za deň", int(days.length ? steps / days.length : 0))}
    ${kpi("bullseye", "ico-green", "Cieľ splnený", `${goalDays}× <small class="text-body-secondary fs-6">${days.length ? Math.round(goalDays / days.length * 100) : 0} % dní</small>`)}
    ${kpi("fire", "ico-amber", "Séria dní s cieľom", `${cur} <small class="text-body-secondary fs-6">rekord ${best}</small>`)}
    ${kpi("geo", "ico-violet", "Vzdialenosť", km(dist, 1), dist, sum(pw, t => t.distance_m))}
    ${kpi("stopwatch", "ico-violet", "Čas chôdze", durH(time), time, sum(pw, tripMs))}
    ${kpi("speedometer2", "ico-rose", "Ø tempo", p ? paceTxt(p) + " min/km" : "–")}
    ${kpi("briefcase", "ico-green", "V pracovnom čase", (steps ? Math.round(work / steps * 100) : 0) + " %")}
    ${kpi("trophy", "ico-amber", "Najlepší deň", bestDay ? `${int(bestDay.steps)} <small class="text-body-secondary fs-6">${dt(bestDay.d)}</small>` : "–")}
    ${kpi("signpost-2", "ico-blue", "Najdlhšia chôdza", longest ? `${int(longest.steps)} <small class="text-body-secondary fs-6">${dt(longest.started_at)}</small>` : "–")}
    <div class="col-12 small text-body-secondary">${active} z ${days.length} dní aktívnych · ${walks.length} chôdzí · denný cieľ ${int(goal)} krokov · pracovný čas ${hm(w.from)}–${hm(w.to)}</div>
    ${card("Kroky po dňoch", `<div class="chart-box lg"><canvas id="wDaysCh"></canvas></div>`, { sub: "zelené = cieľ splnený · čiarkovaná čiara = denný cieľ" })}
    ${card("Rok v krokoch", `<div id="heat"></div>`, { sub: "posledných 12 mesiacov · orámované = splnený cieľ" })}
    ${card("Kedy chodíte", walks.length ? `<div class="chart-box"><canvas id="wHourCh"></canvas></div>` : empty("clock", "Žiadna chôdza v období"), { col: "col-xl-6", sub: "Ø krokov za deň podľa hodiny" })}
    ${card("Dni v týždni", `<div class="chart-box"><canvas id="wDowCh"></canvas></div>`, { col: "col-xl-6", sub: "Ø krokov · v popise % dní so splneným cieľom" })}
    ${card("Vývoj po týždňoch", weeks.length > 1 ? `<div class="chart-box"><canvas id="wTrendCh"></canvas></div>` : empty("graph-up", "Treba aspoň 2 týždne – zvoľte Mesiac alebo Rok"), { col: "col-xl-7", sub: "Ø krokov za deň a tempo" })}
    ${card("Dĺžka chôdzí", walks.length ? `<div class="chart-box"><canvas id="wBinCh"></canvas></div>` : empty("rulers", "Žiadna chôdza v období"), { col: "col-xl-5", sub: "počet chôdzí a podiel krokov" })}
    ${card("Po dňoch", `<div id="wDays"></div>`, { sub: "kliknutím otvoríte časovú os dňa" })}
    ${card("Po týždňoch", `<div id="wWeeks"></div>`, { col: "col-xl-6" })}
    ${card("Po mesiacoch", `<div id="wMonths"></div>`, { col: "col-xl-6", sub: "posledných 12 mesiacov" })}
    ${card("Najdlhšie chôdze", `<div id="wLong"></div>`, { sub: "kliknutím otvoríte chôdzu s mapou" })}
  </div>`;

  // ---- grafy
  chart("wDaysCh", { type: "bar", data: { labels: days.map(x => days.length > 40 ? dt(x.d) : new Date(x.d).toLocaleDateString("sk-SK", { weekday: "short", day: "numeric", month: "numeric" })), datasets: [
    { label: "Kroky", data: days.map(x => x.steps), backgroundColor: days.map(x => x.goal ? C.car : C.walk), borderRadius: 4 },
    { label: "Cieľ", type: "line", data: days.map(() => goal), borderColor: C.amber, borderDash: [6, 4], pointRadius: 0 }] },
    options: { scales: { x: { grid: { display: false }, ticks: { maxTicksLimit: 12 } } } } });
  if (walks.length) {
    const hours = Array(24).fill(0);
    walks.forEach(t => { const a = t.started_at, b = t.ended_at || a;
      if (b <= a) { hours[new Date(a).getHours()] += t.steps; return; }
      for (let x = a; x < b;) { const d = new Date(x), h = d.getHours(); d.setMinutes(0, 0, 0); d.setHours(h + 1); const e = Math.min(b, +d); hours[h] += t.steps * (e - x) / (b - a); x = e; } });
    const avg = hours.map(v => Math.round(v / Math.max(1, days.length))), peak = avg.indexOf(Math.max(...avg));
    chart("wHourCh", { type: "bar", data: { labels: avg.map((_, h) => h + ":00"), datasets: [{ label: "Ø krokov", data: avg,
      backgroundColor: avg.map((_, h) => h === peak ? C.walk : C.walk + "99"), borderRadius: 4 }] },
      options: { plugins: { legend: { display: false } }, scales: { x: { grid: { display: false } } } } });
  }
  const dow = DOW.map((name, i) => { const l = days.filter(x => dowOf(x.d) === i); return { name, n: l.length, avg: l.length ? sum(l, x => x.steps) / l.length : 0, pct: l.length ? l.filter(x => x.goal).length / l.length * 100 : 0 }; });
  chart("wDowCh", { type: "bar", data: { labels: dow.map(x => x.name.slice(0, 2) + (x.n ? ` · ${Math.round(x.pct)} %` : "")), datasets: [
    { label: "Ø krokov", data: dow.map(x => Math.round(x.avg)), backgroundColor: C.biz, borderRadius: 6 },
    { label: "Cieľ", type: "line", data: dow.map(() => goal), borderColor: C.amber, borderDash: [6, 4], pointRadius: 0 }] },
    options: { scales: { x: { grid: { display: false } } } } });
  if (weeks.length > 1) chart("wTrendCh", { type: "bar", data: { labels: weeks.map(x => x.label.split(" /")[0]), datasets: [
    { label: "Ø krokov / deň", data: weeks.map(x => Math.round(x.avg)), backgroundColor: C.walk + "cc", borderRadius: 4, yAxisID: "y" },
    { label: "Tempo (min/km)", type: "line", data: weeks.map(x => x.pace ? +x.pace.toFixed(2) : null), borderColor: C.rose, backgroundColor: C.rose, tension: .3, yAxisID: "y1", spanGaps: true }] },
    options: { scales: { x: { grid: { display: false } }, y1: { position: "right", grid: { display: false }, ticks: { callback: v => paceTxt(v) } } },
      plugins: { tooltip: { callbacks: { label: (c) => c.datasetIndex === 1 ? "Tempo " + paceTxt(c.raw) + " min/km" : "Ø " + int(c.raw) + " krokov/deň" } } } } });
  if (walks.length) {
    const B = [[0, 500, "do 500"], [500, 1000, "500–1k"], [1000, 2000, "1–2k"], [2000, 5000, "2–5k"], [5000, 10000, "5–10k"], [10000, 1e12, "10k+"]];
    const tot = Math.max(1, sum(walks, t => t.steps));
    const bins = B.map(([a, z, l]) => { const g = walks.filter(t => t.steps >= a && t.steps < z); return { l, n: g.length, share: sum(g, t => t.steps) / tot * 100 }; });
    chart("wBinCh", { type: "bar", data: { labels: bins.map(x => x.l), datasets: [{ label: "Chôdzí", data: bins.map(x => x.n), backgroundColor: C.amber, borderRadius: 6 }] },
      options: { plugins: { legend: { display: false }, tooltip: { callbacks: { afterLabel: (c) => Math.round(bins[c.dataIndex].share) + " % všetkých krokov" } } }, scales: { x: { grid: { display: false } } } } });
  }

  // ---- rok v krokoch (heatmapa)
  { const cell = 13, gap = 3, lead = dowOf(yDays[0].d), max = Math.max(goal, ...yDays.map(x => x.steps));
    let svg = "";
    yDays.forEach((x, i) => { const col = Math.floor((i + lead) / 7), row = (i + lead) % 7, a = x.steps ? .18 + .82 * Math.min(1, x.steps / max) : 0;
      svg += `<rect x="${30 + col * (cell + gap)}" y="${18 + row * (cell + gap)}" width="${cell}" height="${cell}" rx="3" fill="${x.steps ? `rgba(37,99,235,${a.toFixed(2)})` : css("--ct-grid")}" ${x.goal ? `stroke="#f59e0b" stroke-width="2"` : ""}><title>${dt(x.d)}: ${int(x.steps)} krokov</title></rect>`;
      if (new Date(x.d).getDate() === 1) svg += `<text x="${30 + col * (cell + gap)}" y="12" font-size="10" fill="${css("--ct-muted")}">${new Date(x.d).toLocaleDateString("sk-SK", { month: "short" })}</text>`; });
    ["Po", "", "St", "", "Pi", "", "Ne"].forEach((n, r) => { if (n) svg += `<text x="0" y="${28 + r * (cell + gap)}" font-size="10" fill="${css("--ct-muted")}">${n}</text>`; });
    const W = 30 + (Math.ceil((yDays.length + lead) / 7) + 1) * (cell + gap);
    $("heat").innerHTML = `<div class="heat-scroll" id="heatScroll"><svg viewBox="0 0 ${W} ${18 + 7 * (cell + gap)}" width="${W}" height="${18 + 7 * (cell + gap)}">${svg}</svg></div>
      <div class="d-flex flex-wrap gap-3 mt-2 small"><span><i class="bi bi-fire text-warning"></i> aktuálna séria: <b>${cur}</b> dní</span><span><i class="bi bi-trophy text-primary"></i> najdlhšia séria: <b>${best}</b> dní</span>
      <span>dní so splneným cieľom: <b>${yDays.filter(x => x.goal).length}</b></span><span>spolu za 12 mesiacov: <b>${int(sum(yDays, x => x.steps))}</b> krokov</span></div>`;
    const hs = $("heatScroll"); hs.scrollLeft = hs.scrollWidth; }

  // ---- tabuľky
  const pct = (x) => goal > 0 ? (x.goal ? '<i class="bi bi-check-lg text-success"></i>' : Math.round(x.steps / goal * 100) + " %") : "";
  grid("wDays", [...days].reverse(), [
    { t: "Deň", pri: 1, cls: "text-nowrap fw-semibold", d: r => new Date(r.d).toLocaleDateString("sk-SK", { weekday: "short", day: "numeric", month: "numeric", year: "numeric" }), s: r => r.d, x: r => dt(r.d) },
    { t: "Kroky", pri: 2, cls: "text-end fw-semibold", d: r => `<span class="${r.goal ? "text-success" : ""}">${int(r.steps)}</span>`, s: r => r.steps, x: r => r.steps },
    { t: "Cieľ", pri: 4, cls: "text-end", d: pct, s: r => r.steps, x: r => goal ? Math.round(r.steps / goal * 100) : "" },
    { t: "Km", pri: 3, cls: "text-end", d: r => nf(r.m / 1000, 2), s: r => r.m, x: r => +(r.m / 1000).toFixed(2) },
    { t: "Čas", pri: 5, cls: "text-end text-nowrap", d: r => r.ms ? dur(r.ms) : "", s: r => r.ms, x: r => Math.round(r.ms / 60000) },
    { t: "Chôdzí", pri: 7, cls: "text-end", d: r => int(r.n), s: r => r.n, x: r => r.n },
    { t: "Tempo", pri: 6, cls: "text-end", d: r => paceTxt(r.pace), s: r => r.pace ?? 999, x: r => paceTxt(r.pace) },
    { t: "V pracovnom čase", pri: 8, cls: "text-end", d: r => int(r.work), s: r => r.work, x: r => r.work },
  ], { order: [[1, "desc"]], pageLength: 14, rowTrip: false, file: "chodza-dni-" + S.range.title, title: "Chôdza po dňoch – " + S.range.title,
    foot: (r) => ["Spolu", int(sum(r, x => x.steps)), r.filter(x => x.goal).length + "×", nf(sum(r, x => x.m) / 1000, 1), dur(sum(r, x => x.ms)), int(sum(r, x => x.n)),
      paceTxt(pace(sum(r, x => x.ms), sum(r, x => x.m))), int(sum(r, x => x.work))] });
  $("wDays").addEventListener("click", (e) => { const tr = e.target.closest("tbody tr"); if (!tr || e.target.closest("td.dtr-control")) return;
    const r = tables.wDaysT?.row(tr).data(); if (r) location.hash = "#/den?d=" + r.d; });
  const gCols = (first) => [
    { t: first, pri: 1, cls: "text-nowrap", d: r => `<span class="fw-semibold">${esc(r.label)}</span><div class="small text-body-secondary">${esc(r.sub)}</div>`, s: r => r.k, x: r => r.label },
    { t: "Kroky", pri: 2, cls: "text-end fw-semibold", d: r => int(r.steps), s: r => r.steps, x: r => r.steps },
    { t: "Ø / deň", pri: 3, cls: "text-end", d: r => `<span class="${r.avg >= goal ? "text-success fw-semibold" : ""}">${int(r.avg)}</span>`, s: r => r.avg, x: r => Math.round(r.avg) },
    { t: "Dni s cieľom", pri: 4, cls: "text-end", d: r => `${r.goalDays} / ${r.days}`, s: r => r.goalDays, x: r => r.goalDays },
    { t: "Km", pri: 5, cls: "text-end", d: r => nf(r.m / 1000, 1), s: r => r.m, x: r => +(r.m / 1000).toFixed(1) },
    { t: "Čas", pri: 7, cls: "text-end text-nowrap", d: r => dur(r.ms), s: r => r.ms, x: r => Math.round(r.ms / 60000) },
    { t: "Tempo", pri: 6, cls: "text-end", d: r => paceTxt(r.pace), s: r => r.pace ?? 999, x: r => paceTxt(r.pace) },
    { t: "Najlepší deň", pri: 8, cls: "text-end", d: r => int(r.best), s: r => r.best, x: r => r.best },
    { t: "V práci", pri: 9, cls: "text-end", d: r => r.steps ? Math.round(r.work / r.steps * 100) + " %" : "", s: r => r.steps ? r.work / r.steps : 0, x: r => r.steps ? Math.round(r.work / r.steps * 100) : "" },
  ];
  const gFoot = (r) => { const st = sum(r, x => x.steps), d = sum(r, x => x.days); return ["Spolu", int(st), int(d ? st / d : 0), `${sum(r, x => x.goalDays)} / ${d}`, nf(sum(r, x => x.m) / 1000, 1), dur(sum(r, x => x.ms)), "", "", ""]; };
  grid("wWeeks", weeks, gCols("Týždeň"), { order: [[1, "desc"]], pageLength: 12, rowTrip: false, file: "chodza-tyzdne-" + S.range.title, title: "Chôdza po týždňoch – " + S.range.title, foot: gFoot });
  grid("wMonths", months, gCols("Mesiac"), { order: [[1, "desc"]], pageLength: 12, rowTrip: false, file: "chodza-mesiace", title: "Chôdza po mesiacoch", foot: gFoot });
  const place = (t) => [t.start_place || (t.start_address || "").split(",")[0], t.end_place || (t.end_address || "").split(",")[0]].filter(Boolean).join(" → ");
  grid("wLong", [...walks].sort((a, b) => b.steps - a.steps).slice(0, 100), [
    { t: "Dátum", pri: 1, cls: "text-nowrap", d: r => `<span class="fw-semibold">${dt(r.started_at)}</span> <span class="small text-body-secondary">${tm(r.started_at)}–${tm(r.ended_at || r.started_at)}</span>`, s: r => r.started_at, x: r => dt(r.started_at) + " " + tm(r.started_at) },
    { t: "Kroky", pri: 2, cls: "text-end fw-semibold", d: r => int(r.steps), s: r => r.steps, x: r => r.steps },
    { t: "Km", pri: 3, cls: "text-end", d: r => nf(r.distance_m / 1000, 2), s: r => r.distance_m, x: r => +(r.distance_m / 1000).toFixed(2) },
    { t: "Čas", pri: 4, cls: "text-end text-nowrap", d: r => dur(tripMs(r)), s: r => tripMs(r), x: r => Math.round(tripMs(r) / 60000) },
    { t: "Tempo", pri: 5, cls: "text-end", d: r => paceTxt(pace(tripMs(r), r.distance_m)), s: r => pace(tripMs(r), r.distance_m) ?? 999 },
    { t: "Trasa", pri: 6, d: r => esc(place(r)), x: r => place(r) },
  ], { order: [[2, "desc"]], pageLength: 10, file: "chodza-najdlhsie-" + S.range.title, title: "Najdlhšie chôdze – " + S.range.title });
}

// =====================================================================
//  Miesta
// =====================================================================
function pagePlaces() {
  const places = S.items.place;
  const kinds = { home: ["house-door-fill", "Domov"], work: ["building", "Práca"], client: ["briefcase-fill", "Klient"], other: ["geo-alt-fill", "Iné"] };
  const visits = (p) => S.trips.filter(t => t.start_place === p.name || t.end_place === p.name);
  $("content").innerHTML = `<div class="row g-3">
    ${card("Mapa miest", `<div class="map-box tall" id="placeMap"></div>`, { col: "col-xl-7" })}
    ${card("Miesta", places.length ? `<div class="list-ct">${places.map((p, i) => { const v = visits(p), k = kinds[p.kind] || kinds.other;
      return `<div class="item clickable" role="button" data-pl="${i}"><i class="bi bi-${k[0]} fs-4 text-danger"></i><div class="flex-grow-1"><div class="fw-semibold">${esc(p.name)}</div>
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
$("reload").onclick = async () => { for (const k in pointCache) delete pointCache[k]; S.loadedKey = ""; S.yearKey = ""; S.walkYearKey = ""; await loadBase().catch(() => {}); render(); toast("Údaje obnovené"); };

async function start() {
  stopPairing();
  $("login").classList.add("d-none"); $("app").classList.remove("d-none");
  $("who").textContent = S.user?.name || ""; $("sideUser").textContent = S.user?.email || S.user?.name || "";
  document.querySelectorAll("#periodSeg button").forEach(x => x.classList.toggle("active", x.dataset.p === S.period));
  setTheme(document.documentElement.dataset.bsTheme || "light", false);
  loading(true);
  try { await loadBase(); } catch (e) { loading(false); $("content").innerHTML = `<div class="alert alert-danger">${esc(e.message)}</div>`; return; }
  loading(false);
  route();
}
if (S.token) start(); else { $("login").classList.remove("d-none"); startPairing(); }
