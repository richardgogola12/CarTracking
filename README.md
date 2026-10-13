# Car Tracking – automatické sledovanie jázd autom (Android + PHP/MySQL)

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
- **Mapa bez API kľúča** – CARTO začal vyžadovať kľúč, nahradené Esri / OpenStreetMap / OpenTopoMap (overené).
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
4. Apache potrebuje `mod_rewrite` (súbor `.htaccess` je priložený). Pre nginx:
   `location /cartracking/api/ { try_files $uri /cartracking/api/index.php$is_args$args; }`
5. Test: `curl -X POST http://SERVER/cartracking/api/login -d '{"email":"a@b.sk","password":"x"}'` → JSON s chybou = API beží.

> Na produkciu použite **HTTPS**. `usesCleartextTraffic="true"` v manifeste je len pre lokálny vývoj.

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

## Štruktúra
```
backend/  schema.sql, api/index.php (REST), api/config.php, api/.htaccess
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
