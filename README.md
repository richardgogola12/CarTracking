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
- **Webový prehľad** (`backend/web/index.html`) – jazdy a chôdza na počítači: mapa, grafy, kniha jázd, náhrady, spoločná kniha jázd, časté trasy, tlač a Excel.
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
   Webový prehľad: priečinok `backend/web` skopírujte vedľa neho (`htdocs/cartracking/web`) – otvorí sa na `https://SERVER/cartracking/web/`.
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
