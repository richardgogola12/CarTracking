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

## Nastavenia v aplikácii
Automatická detekcia (zap/vyp), štart po reštarte, rýchlosť potvrdzujúca auto, čas na potvrdenie,
ukončenie po státí, minimálna dĺžka jazdy · GPS interval a presnosť · notifikácia pri zapnutí/vypnutí,
zvuk, vibrácie a ich typ, rýchlosť v notifikácii, test notifikácie · Bluetooth auta (spúšťač / povinné) ·
adresa servera, automatická synchronizácia, iba cez Wi-Fi · odtlačok prsta · téma · povolenia · účet/odhlásenie.

## 1. Server (PHP 8.1+ a MySQL 8 / MariaDB)
1. Vytvorte databázu: `mysql -u root -p < backend/schema.sql`
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
