# Trasník – jazdy · chôdza · kniha jázd (Android + PHP/MySQL)

<img src="backend/web/icon.svg" width="96" alt="Ikona Trasník">

Aplikácia sa od verzie 2.0 volá **Trasník** (predtým „Car Tracking“). Interné názvy ostávajú kvôli kompatibilite: balík `sk.cartracker`, repozitár `CarTracking`, cesta na serveri `…/cartracking/api/`, tabuľky `CarTracking_*` a formát záloh – aktualizácia sa nainštaluje cez starú verziu bez straty údajov.

**Ikona:** `android/app/src/main/res/drawable/ic_launcher_bg.xml` (pozadie), `ic_launcher_fg.xml` (trasa a cieľ), `ic_launcher_mono.xml` (tematická ikona Android 13+); web: `backend/web/icon.svg`, `icon-180.png`.

## Čo aplikácia robí
- **Login / registrácia** e-mailom a heslom, **prihlásenie odtlačkom prsta** (kľúč v Android Keystore, odomkne ho iba odtlačok).
- **Automatická detekcia jazdy autom** – spúšťa sa sama, aj keď je aplikácia zatvorená, aj po reštarte telefónu.
- **Chôdza, beh a bicykel sa ignorujú** (detekcia aktivity + potvrdenie rýchlosťou GPS).
- **Notifikácia + vibrácia** pri zapnutí aj vypnutí režimu jazdy (so súhrnom km / čas / priemer).
- Záznam trasy cez GPS, zoznam jázd, detail s mapou (OpenStreetMap, bez API kľúča).
- Dáta sa ukladajú lokálne (funguje aj offline) a synchronizujú sa na **server s MySQL**.

## Ako funguje detekcia (prečo len auto)
```
Google Activity Recognition: "IN_VEHICLE – začiatok"   (alebo pripojenie Bluetooth auta)
        │   – systém to doručí aj keď appka nebeží, takmer bez spotreby batérie
        ▼
Služba v popredí – stav OVERUJEM (tichá notifikácia)
        │   3 presné GPS body s rýchlosťou ≥ 25 km/h (nastaviteľné)?
        ├── nie / začal chodiť / bicykel → zahodí sa, nič sa neuloží, žiadna notifikácia
        ▼ áno
REŽIM JAZDY ZAPNUTÝ  → notifikácia + vibrácia, záznam GPS
        │   státie X min, alebo vystúpenie z auta + státie 1 min
        ▼
REŽIM JAZDY VYPNUTÝ  → notifikácia + vibrácia so súhrnom, uloženie, odoslanie na server
```
Autobus či vlak systém tiež hlási ako „vo vozidle“. Ak to vadí, v Nastaveniach vyberte **Bluetooth auta**
a zapnite **„Sledovať iba keď je pripojené auto“**.

## Novinky vo verzii 2.0 (zostavenia 2.0.N)
- **Verzia 2.0.N** = číslo zostavenia z GitHub Actions; APK sa volá `Trasnik-N.apk` (vydanie `build-N`).
- **Aktualizácia v aplikácii** – raz denne kontrola `api.github.com/repos/richardgogola12/CarTracking/releases/latest`, notifikácia, stiahnutie APK a systémová inštalácia (`sync/AppUpdate.kt`, povolenie `REQUEST_INSTALL_PACKAGES`). Nastavenia → Pomoc a aktualizácie.
- **Bez Esri** – mapy iba OpenStreetMap (svetlá, tmavá = prefarbená cez ColorMatrix) a OpenTopoMap, v aplikácii aj na webe. Hromadné sťahovanie oblastí je vypnuté (pravidlá OSM); prezreté dlaždice sa ukladajú na 30 dní.
- **Limity z mapy na disku** – štvorce z Overpass API sa ukladajú do `cacheDir/speedlimits` na 30 dní (opakované trasy aj bez internetu).
- **Prepočet po oprave limitu** – uloženie „Môjho limitu“ zruší falošné prekročenia v úseku v jazdách za 120 dní, zníži čas prekračovania a prepočíta skóre (aj na serveri).
- **Šablóny účelu jazdy** – ťukacie návrhy (účely do rovnakého cieľa, šablóny, naposledy použité).
- **Kniha jázd za minulý mesiac** jedným ťuknutím – PDF / Excel z mesačnej notifikácie alebo zo Štatistík, všetky vozidlá.
- **Náklady na auto** – výdavky (servis, poistenie, známka, parkovanie…), súčet s palivom, cena za km a za mesiac. Ukladajú sa ako položky `kind = expense`.
- **Preriedenie starých trás** – body jázd starších ako N mesiacov (už na serveri) sa v telefóne preriedia na ~60 m.
- **Automatické testy** – unit testy (`android/app/src/test`, JUnit) bežia v CI pred zostavením APK: vzdialenosť a filter km auta, limity z OSM, moje limity, učenie účelu, prepočet prekročení, náklady, preriedenie, šablóny účelu.
- **Bezpečnosť servera** – obmedzenie pokusov o prihlásenie (10 / e-mail, 30 / IP za 15 min → HTTP 429), registrácie (10 / IP / h), predlžovanie tokenu používaním, bezpečnostné hlavičky, oprava udalostí bodov pri opätovnom odoslaní. Databáza: `backend/migrate_v8.sql`.
- **Zvuky** – 28 syntetizovaných zvukov (`res/raw/sounds.json`, `service/Sounds.kt`) + systémový / vlastný z telefónu; zvlášť pre začiatok a koniec jazdy autom, chôdze, prekročenie rýchlosti a parkovací lístok; hlasitosť; prehrávanie aj v tichom režime (kanál budíka).
- **Rýchlostné limity** – voľba jednotný / podľa typu cesty / podľa skutočných značiek z mapy (Overpass, priradenie cesty pri každom bode GPS), **Moje limity** – opravy z detailu jazdy.
- **Účel jazdy z notifikácie** – po skončení jazdy tlačidlá *Služobná / Súkromná* s navrhnutým účelom a dôvodom.
- **Automatické zaradenie** – naučené trasy (rovnaký začiatok a cieľ zaradený 2× rovnako), miesta s účelom, voliteľne pravidlá *pracovný čas → služobná* a *deň voľna → súkromná*. Naučené trasy sa ukladajú v nastaveniach (aj na server).
- **Úspora batérie** – adaptívny interval GPS (diaľnica redšie, státie úsporne); Diagnostika ukazuje čas GPS a pokles batérie po dňoch.
- **Kontrola jázd** – zabudnutý koniec, čakanie pred odjazdom, dlhá zastávka uprostred, rozdelená jazda, výpadok GPS, nereálna rýchlosť; oprava jedným ťuknutím (orezanie, rozdelenie, zlúčenie, zmazanie).
- **Skratky na ikone** – Začať jazdu, Začať chôdzu, Kde je auto, Tankovanie.
- Webový dashboard 2.0 (Bootstrap 5) – pozri nižšie.

## Novinky vo verzii 1.9
- **Diagnostika detekcie** – kontrola podmienok (povolenia, batéria, Bluetooth) a záznam udalostí: čo hlásil systém, rýchlosti GPS pri overovaní, prečo sa jazda spustila / zahodila / ukončila; zdieľanie súboru.
- **Ručné pridanie** jazdy / chôdze a **rozdelenie jazdy** (predvolene v najdlhšom státí, prepočet km, rýchlostí, krokov a udalostí; na serveri sa nahradí).
- **Kalibrácia km podľa tachometra** (z tankovaní, pre nové aj staršie jazdy) a **kalibrácia dĺžky kroku** z chôdze vonku s GPS.
- **Časová os dňa** – jazdy, chôdza a pobyty za sebou, súhrn dňa, tlač/PDF a CSV.
- **Prehľadnejšie nastavenia** v rozbaľovacích skupinách so súhrnom; všetky nastavenia (aj nové) sa ukladajú na server a obnovia sa na novom telefóne.
- **Prepínanie záložiek potiahnutím** doľava / doprava.

## Novinky vo verzii 1.8
- **4 nové widgety**: tlačidlá (Jazda/Chôdza), kroky (krúžok, séria, týždeň), parkovanie (miesto, čas, odpočet lístka, Nájsť auto), mesiac (km, jazdy, náklady, kroky, porovnanie, graf).
- **Posuvníky od 0** a **vlastná hodnota** ťuknutím na číslo (aj nad koniec posuvníka, čas ako 7:30).
- Oprava obnovy pri prázdnych nastaveniach; ak účet na serveri nič nemá, nič sa nesťahuje (`GET /backup/status`).

## Novinky vo verzii 1.7
- **Obnova po prihlásení** – na novom telefóne alebo po preinštalovaní sa po prihlásení samy stiahnu nastavenia, vozidlá (aj Bluetooth), miesta, tankovania, pripomienky a všetky jazdy s trasami. Priebeh na Prehľade, beží na pozadí, nič sa nezdvojí.
- Nastavenia sa ukladajú na server pri každej zmene (`GET/PUT /settings`).
- **Automatická záloha na server** – raz po aktualizácii/prihlásení a potom týždenne sa porovná telefón so serverom; čo na serveri ešte nikdy nebolo (jazdy s trasami, vozidlá, miesta, tankovania, pripomienky, nastavenia), sa odošle.
- Voľba merania vzdialenosti chôdze: z krokov alebo z GPS.
- Databáza: `backend/migrate_v7.sql` (tabuľka `CarTracking_settings`, stĺpec `bt_address`).

## Novinky vo verzii 1.6
- **Webový dashboard** (`backend/web/` – `index.html`, `app.css`, `app.js`; Bootstrap 5, DataTables 2 s Responsive a Buttons, Chart.js, Leaflet; responzívny – počítač, tablet, mobil so spodnou navigáciou; tabuľky s vyhľadávaním, exportom do Excelu/CSV a výberom stĺpcov) – bočné menu s 11 stránkami: Dashboard s trendmi oproti minulému obdobiu, Jazdy (vyhľadávanie, filtre, triedenie, CSV), detail jazdy s grafom rýchlosti, Mapa, Časová os dňa, Kniha jázd, Cestovné náhrady, Vozidlá a tankovanie, Časté trasy, Chôdza (ročná heatmapa krokov), Miesta, Účet; svetlý / tmavý režim, tlač.
- **Zdieľané vozidlo** – vlastník ho zdieľa e-mailom ďalším vodičom, spoločná kniha jázd všetkých vodičov.
- **Cestovné náhrady** – sadzba €/km pri vozidle, výkaz za služobné jazdy (základná náhrada + PHL), tlač/PDF a CSV.
- **Časté trasy** – opakované cesty, priemerný/najrýchlejší čas, najlepšia hodina odchodu.
- **Tlačidlá v rýchlej lište** (Jazda, Chôdza), **parkovací lístok** s upozornením 10 min pred koncom.
- **Kalendár aktivity, rekordy a série krokov**, **pracovný výkaz chôdze**, **mesačné zhrnutie** (notifikácia).
- **Offline mapy** – uloženie oblasti, režim offline, limit veľkosti.
- Databáza: `backend/migrate_v6.sql` (sadzba `rate_per_km`, tabuľka `CarTracking_vehicle_shares`).

## Graf rýchlosti
- V detaile jazdy autom graf rýchlosti farbený podľa rýchlosti, os podľa času alebo km, ťuknutím hodnota v bode, priemer, maximum, udalosti, čas v pohybe/státia a rozdelenie času podľa rýchlosti.
- Vo veľkom widgete sa ťuknutím na graf prepína: km za 7 dní → rýchlosť (aktuálna/posledná jazda) → kroky za 7 dní.

## Nový widget
- Farba podľa stavu (čakám / overujem / jazda / chôdza), počas jazdy rýchlosť, značka s limitom, bežiaci čas, km a náklady; počas chôdze kroky.
- Dlaždice dňa (km, jazdy, kroky, náklady), tlačidlá Jazda / Chôdza / obnoviť, pri overovaní „✓ Je to jazda / ✕ Nie je“.
- Veľký widget: graf km za 7 dní, krúžok krokov k dennému cieľu (Nastavenia → Chôdza), najbližšia pripomienka a miesto parkovania.

## Novinky vo verzii 1.5
- **Miesta** (Domov, Práca, Klient…) – názvy v jazdách, automatický účel a typ jazdy (súkromná/služobná); výber polohy GPS, na mape alebo z jazdy.
- **Tankovanie** – skutočná spotreba (metóda plná–plná), cena/km, skutočné náklady v štatistikách.
- **Pripomienky** – STK, EK, olej, servis, známka, PZP, pneumatiky; podľa dátumu aj km, opakovanie, notifikácie.
- **Záloha a obnova** všetkých údajov do súboru `.ctbak`; **widget** na ploche.
- **Iba HTTPS** (cleartext zakázaný), plynulé posuvníky v nastaveniach.
- Databáza: `backend/migrate_v5.sql` (tabuľka `CarTracking_items`, názvy miest pri jazdách).

## Novinky vo verzii 1.4
- **Zlúčenie jázd** – automaticky po krátkej prestávke (auto 10 min, chôdza 5 min, do 1 km, rovnaké vozidlo), ručne výberom v zozname (podržať → označiť → Zlúčiť) alebo „Zlúčiť s nasledujúcou“ v detaile.
- Mazanie jázd na serveri sa pri výpadku siete dokončí pri ďalšej synchronizácii.

## Novinky vo verzii 1.3
- **Max. rýchlosť v obci / mimo obce / na diaľnici** pri každom vozidle (osobné auto 50/90/130).
- Typ cesty sa počas jazdy rozpozná z OpenStreetMap (Nominatim, bez kľúča); limit zo značky v mape má prednosť. Na Prehľade značka s aktuálnym limitom a ručné prepnutie typu cesty. Tolerancia nastaviteľná.
- Databáza: `backend/migrate_v4.sql`.

## Novinky vo verzii 1.2
- **Viac vozidiel** – každé s vlastnou EČV, palivom, spotrebou, cenou, tachometrom a Bluetooth. Prepínanie na Prehľade (aj počas jazdy), automaticky podľa Bluetooth, dodatočne v detaile jazdy. Kniha jázd a štatistiky po vozidlách.
- **Sledovanie chôdze** – automatická detekcia, kroky z krokomeru (funguje aj v budove), trasa, voliteľne iba v pracovnom čase; vlastné štatistiky.
- **Mapa bez API kľúča** – CARTO začal vyžadovať kľúč, nahradené Esri / OpenStreetMap / OpenTopoMap (od 2.0 už bez Esri).
- **Test backendu** v GitHub Actions proti reálnej MySQL (`backend/tests/api_test.sh`). Databázu aktualizuje `backend/migrate_v3.sql`.

## Novinky vo verzii 1.1
- **Kniha jázd** – súkromná/služobná jazda, účel, adresy odkiaľ → kam, stav tachometra; tlač/PDF a export do Excelu (CSV).
- **Štatistiky** – týždeň/mesiac/rok, graf km, súkromné vs. služobné, mapa všetkých jázd obdobia.
- **Štýl jazdy** – prudké brzdenie/zrýchlenie, čas nad limitom, skóre 0–100, voliteľné upozornenie vibráciou.
- **Náklady na palivo** – podľa spotreby a ceny, pri každej jazde aj za obdobie.
- **Kde som zaparkoval** – miesto konca jazdy s tlačidlom „Nájsť auto“.
- **Nová mapa** – CARTO/OSM/terén/satelit, trasa farbená podľa rýchlosti, značky A/B a udalostí, legenda, celá obrazovka.
- **Návod v aplikácii** (Nastavenia → Pomoc) s tlačou; PDF verzia v `docs/CarTracking-Navod.pdf`.
- APK je podpísané pevným kľúčom → nové verzie sa inštalujú ako aktualizácia. Databázu servera aktualizuje `backend/migrate_v2.sql`.

## Nastavenia v aplikácii
Automatická detekcia (zap/vyp), štart po reštarte, rýchlosť potvrdzujúca auto, čas na potvrdenie,
ukončenie po státí, minimálna dĺžka jazdy · GPS interval a presnosť · notifikácia pri zapnutí/vypnutí,
zvuk, vibrácie a ich typ, rýchlosť v notifikácii, test notifikácie · Bluetooth auta (spúšťač / povinné) ·
adresa servera, automatická synchronizácia, iba cez Wi-Fi · odtlačok prsta · téma · povolenia · účet/odhlásenie.

## 1. Server (PHP 8.1+ a MySQL 8 / MariaDB)
1. Vytvorte databázu: `mysql -u root -p --default-character-set=utf8mb4 < backend/schema.sql`
   Všetky tabuľky majú prefix `CarTracking_` (`CarTracking_users`, `CarTracking_api_tokens`, `CarTracking_trips`, `CarTracking_trip_points`),
   takže môžu bežať aj v zdieľanej databáze s inými aplikáciami. Existujúcu DB so starými názvami premenuje `backend/migrate_prefix.sql`.
2. Vytvorte používateľa DB, napr.:
   ```sql
   CREATE USER 'car_tracking'@'localhost' IDENTIFIED BY 'silne_heslo';
   GRANT SELECT, INSERT, UPDATE, DELETE ON car_tracking.* TO 'car_tracking'@'localhost';
   ```
3. Skopírujte priečinok `backend/api` na web server (napr. `htdocs/cartracking/api`) a upravte `config.php`.
   Webový dashboard: celý priečinok `backend/web` (3 súbory) skopírujte vedľa neho (`htdocs/cartracking/web`) – otvorí sa na `https://SERVER/cartracking/web/`.
4. Apache potrebuje `mod_rewrite` (súbor `.htaccess` je priložený). Pre nginx:
   `location /cartracking/api/ { try_files $uri /cartracking/api/index.php$is_args$args; }`
5. Test: `curl -X POST http://SERVER/cartracking/api/login -d '{"email":"a@b.sk","password":"x"}'` → JSON s chybou = API beží.

> Aplikácia aj webový prehľad vyžadujú **HTTPS** (nešifrované spojenie aplikácia odmietne).

Aplikácia sa k MySQL nepripája priamo (to by bolo nebezpečné – heslo k DB by bolo v APK); komunikuje cez toto REST API.

## 2. Android aplikácia
1. Otvorte priečinok `android` v **Android Studio** (Ladybug alebo novšie), nechajte prebehnúť Gradle sync.
2. Spustite na telefóne (Android 8.0+, odporúčané reálne zariadenie – detekcia aktivity v emulátore nefunguje).
3. V aplikácii: **Nastavenia → Server → Adresa API servera** (emulátor: `http://10.0.2.2/cartracking/api/`,
   telefón: `http://IP_POCITACA/cartracking/api/` alebo vaša doména).
4. Zaregistrujte sa a na obrazovke **Povolenia** povoľte všetko:
   presná poloha → poloha **„Povoliť vždy“** → fyzická aktivita → notifikácie → **bez obmedzenia batérie**.

### Dôležité pre spoľahlivosť
- Bez „Poloha vždy“ a výnimky z optimalizácie batérie Android **nedovolí** spustiť sledovanie na pozadí.
- Xiaomi/Huawei/Oppo/Samsung: v systéme zapnite aj „Automatické spustenie“ a batériu „Bez obmedzení“.
- Rozpoznanie nástupu do auta trvá zvyčajne 30 s – 2 min od rozbehnutia; body z tohto času sa doplnia do jazdy.

## Externé servery (firewall)
Okrem vášho servera (HTTPS 443). IP sú snímka z 14. 10. 2026 – za CDN sa menia, vo firewalle povoľujte **domény**. Podrobne v príručke, kapitola 33.

| Doména | Kto | Účel | IPv4 (14. 10. 2026) |
|---|---|---|---|
| `nominatim.openstreetmap.org` | aplikácia | obec v adrese, typ cesty a limit (posiela súradnice) | 151.101.1.91, .65.91, .129.91, .193.91 (Fastly) |
| `overpass-api.de`, `overpass.kumi.systems` | aplikácia | skutočné rýchlostné limity ciest (oblasť ~1 km) | 162.55.144.139, 65.109.112.52; 193.219.97.30 |
| `tile.openstreetmap.org` (a./b./c.) | aplikácia, web | mapové dlaždice (svetlá aj tmavá mapa) | 151.101.1.91, .65.91, .129.91, .193.91 (Fastly) |
| `a./b./c.tile.opentopomap.org` | aplikácia | „Turistická“ mapa | 159.195.68.179 |
| `api.github.com` | aplikácia | kontrola novej verzie (raz denne, dá sa vypnúť) | 140.82.112.0/20 (GitHub) |
| `github.com`, `objects.githubusercontent.com` | aplikácia | stiahnutie `Trasnik-N.apk` pri aktualizácii | 140.82.112.0/20; 185.199.108–111.133 |
| servery Google (Play služby) | aplikácia | geokóder (ulica), sieťová poloha, rozpoznanie aktivity | rôzne (Google) |
| `cdnjs.cloudflare.com` | web | Bootstrap, Icons, Chart.js, Leaflet | 104.17.24.14, 104.17.25.14 (Cloudflare) |
| `cdn.jsdelivr.net` | web | DataTables, jQuery, JSZip | 104.17.207.5, 104.17.208.5 (Cloudflare) |
| `fonts.googleapis.com`, `fonts.gstatic.com` | web | písmo Inter | 172.253.122.95, 192.178.210.94 (Google) |

Bez analytiky a reklamy (žiadny Firebase/Crashlytics). Esri (`server.arcgisonline.com`) sa od verzie 2.0 nepoužíva.

## Štruktúra
```
backend/  schema.sql, migrate_*.sql, api/index.php (REST), api/config.php, api/.htaccess, web/index.html (webový prehľad)
android/app/src/main/java/sk/cartracker/
  CarTrackerApp.kt              – inicializácia, kanály notifikácií, registrácia detekcie
  detection/                    – Activity Recognition, Bluetooth auta, štart po reštarte
  service/TrackingService.kt    – služba v popredí (stavový automat jazdy, GPS, ukladanie)
  service/Notifications.kt      – notifikácie a vibrácie
  data/                         – nastavenia (DataStore), Room DB, REST API, repozitáre
  security/                     – Keystore šifrovanie tokenu, prihlásenie odtlačkom
  sync/SyncWorker.kt            – odosielanie jázd na server (WorkManager)
  ui/                           – Compose obrazovky: login, registrácia, zámok, prehľad, jazdy, mapa, nastavenia, povolenia
```

## REST API
| Metóda | Cesta | Popis |
|---|---|---|
| POST | /register | `{name,email,password}` → `{token,user}` |
| POST | /login | `{email,password}` → `{token,user}` |
| POST | /logout | zruší token |
| GET | /me | aktuálny používateľ |
| POST | /trips/sync | idempotentný upsert jázd + bodov |
| GET | /trips, /trips/{uuid} | zoznam / detail s bodmi |
| DELETE | /trips/{uuid} | zmazanie jazdy |
| GET | /trips?from=&to=&type= | jazdy za obdobie |
| POST | /vehicles/sync, GET /vehicles | vozidlá (vlastné aj zdieľané) |
| GET/POST | /vehicles/{uuid}/shares | zdieľanie vozidla (`{email}`) |
| DELETE | /vehicles/{uuid}/shares/{user_id} | zrušenie zdieľania |
| GET | /vehicles/{uuid}/trips?from=&to= | spoločná kniha jázd |
| GET/PUT | /settings | nastavenia aplikácie (obnova na novom telefóne) |
| POST | /trips/points | body až 50 jázd naraz (obnova) |
| GET | /backup/status | čo má používateľ zálohované (ak nič, aplikácia nič nesťahuje) |
| POST/GET | /items/sync, /items?kind= | miesta, tankovania, pripomienky |
