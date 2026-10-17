<?php
declare(strict_types=1);

/*
 * Trasník REST API (PHP 8.1+, PDO MySQL)
 *
 *  POST   /register        {name,email,password,device_name?}  -> {token,user}
 *  POST   /login           {email,password,device_name?}       -> {token,user}
 *  POST   /logout          (Bearer)                            -> {ok}
 *  GET    /me              (Bearer)                            -> {user}
 *  POST   /trips/sync      (Bearer) {trips:[...]}              -> {synced:[uuid...]}
 *  GET    /trips           (Bearer) ?limit=&offset=            -> {trips:[...]}
 *  GET    /trips/{uuid}    (Bearer)                            -> {trip, points}
 *  DELETE /trips/{uuid}    (Bearer)                            -> {ok}
 *  POST   /vehicles/sync   (Bearer) {vehicles:[...]}           -> {synced:[uuid...]}
 *  GET    /vehicles        (Bearer)                            -> {vehicles:[...]}
 *  POST   /items/sync      (Bearer) {items:[{kind,uuid,updated_at,deleted,data}]} -> {synced:[uuid...]}
 *  GET    /items?kind=     (Bearer)                            -> {items:[...]}   (miesta, tankovania, pripomienky)
 *  GET    /vehicles/{uuid}/shares            (Bearer, vlastník)  -> {shares:[{user_id,name,email}]}
 *  POST   /vehicles/{uuid}/shares {email}    (Bearer, vlastník)  -> {share}
 *  DELETE /vehicles/{uuid}/shares/{user_id}  (Bearer, vlastník alebo ten, s kým je zdieľané) -> {ok}
 *  GET    /vehicles/{uuid}/trips?from=&to=   (Bearer, vlastník/zdieľané) -> {trips:[... + driver_name]}
 *
 *  GET    /settings                          (Bearer)            -> {settings, updated_at}  (nastavenia aplikácie)
 *  PUT    /settings {settings, updated_at}   (Bearer)            -> {ok, updated_at}        (novšie vyhráva)
 *  GET    /backup/status                     (Bearer)            -> {trips, vehicles, shared_vehicles, items, settings}
 *  POST   /trips/points {uuids:[...]}        (Bearer, max 50)    -> {points:{uuid:[...]}}   (obnova na novom telefóne)
 *
 *  Webový prehľad (priečinok web/) používa tie isté adresy.
 */

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Headers: Content-Type, Authorization');
header('Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS');
// bezpečnostné hlavičky – odpovede API sa neukladajú do cache a nedajú sa vložiť do cudzej stránky
header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');
header('Referrer-Policy: no-referrer');
header('Cache-Control: no-store');
header_remove('X-Powered-By');
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') { http_response_code(204); exit; }

$config = require __DIR__ . '/config.php';

function respond(int $code, array $data): never {
    http_response_code($code);
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function fail(int $code, string $message): never {
    respond($code, ['error' => $message]);
}

function body(): array {
    $raw = file_get_contents('php://input');
    if ($raw === '' || $raw === false) return [];
    $data = json_decode($raw, true);
    if (!is_array($data)) fail(400, 'Neplatný JSON');
    return $data;
}

function db(array $c): PDO {
    static $pdo = null;
    if ($pdo) return $pdo;
    try {
        $pdo = new PDO(
            "mysql:host={$c['db_host']};port={$c['db_port']};dbname={$c['db_name']};charset=utf8mb4",
            $c['db_user'], $c['db_pass'],
            [
                PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
                PDO::ATTR_EMULATE_PREPARES => false,
            ]
        );
    } catch (PDOException $e) {
        fail(500, 'Nepodarilo sa pripojiť k databáze');
    }
    return $pdo;
}

function issueToken(PDO $pdo, int $userId, ?string $device, int $days): string {
    $token = bin2hex(random_bytes(32));
    $stmt = $pdo->prepare('INSERT INTO CarTracking_api_tokens (user_id, token_hash, device_name, expires_at)
                           VALUES (?, ?, ?, DATE_ADD(NOW(), INTERVAL ? DAY))');
    $stmt->execute([$userId, hash('sha256', $token), $device ? mb_substr($device, 0, 100) : null, $days]);
    return $token;
}

function bearer(): ?string {
    $h = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if ($h === '' && function_exists('apache_request_headers')) {
        $headers = array_change_key_case(apache_request_headers(), CASE_LOWER);
        $h = $headers['authorization'] ?? '';
    }
    return preg_match('/Bearer\s+([a-f0-9]{64})/i', $h, $m) ? strtolower($m[1]) : null;
}

/** IP adresa klienta (pre obmedzenie pokusov o prihlásenie). */
function clientIp(): string { return substr((string)($_SERVER['REMOTE_ADDR'] ?? ''), 0, 45); }

/**
 * Ochrana pred hádaním hesiel: najviac 10 neúspešných prihlásení na e-mail a 30 z jednej IP adresy za 15 minút,
 * registrácia najviac 10 účtov z IP za hodinu. Ak tabuľka CarTracking_login_attempts chýba (stará databáza bez migrácie v8),
 * obmedzenie sa preskočí.
 */
function tooManyAttempts(PDO $pdo, string $kind, string $email): bool {
    try {
        if ($kind === 'register') {
            $st = $pdo->prepare("SELECT COUNT(*) FROM CarTracking_login_attempts WHERE kind = 'register' AND ip = ? AND created_at > NOW() - INTERVAL 1 HOUR");
            $st->execute([clientIp()]);
            return (int)$st->fetchColumn() >= 10;
        }
        $st = $pdo->prepare("SELECT SUM(email = ?), SUM(ip = ?) FROM CarTracking_login_attempts
                             WHERE kind = 'fail' AND created_at > NOW() - INTERVAL 15 MINUTE AND (email = ? OR ip = ?)");
        $st->execute([$email, clientIp(), $email, clientIp()]);
        [$byEmail, $byIp] = array_map('intval', $st->fetch(PDO::FETCH_NUM));
        return $byEmail >= 10 || $byIp >= 30;
    } catch (Throwable $e) { return false; }
}

function recordAttempt(PDO $pdo, string $kind, string $email): void {
    try {
        $pdo->prepare('INSERT INTO CarTracking_login_attempts (kind, email, ip) VALUES (?, ?, ?)')->execute([$kind, mb_substr($email, 0, 190), clientIp()]);
        if (random_int(1, 50) === 1) {   // upratovanie: staré pokusy a vypršané tokeny
            $pdo->exec('DELETE FROM CarTracking_login_attempts WHERE created_at < NOW() - INTERVAL 1 DAY');
            $pdo->exec('DELETE FROM CarTracking_api_tokens WHERE expires_at < NOW()');
        }
    } catch (Throwable $e) { /* bez tabuľky sa nič nezaznamená */ }
}

function clearFailures(PDO $pdo, string $email): void {
    try { $pdo->prepare("DELETE FROM CarTracking_login_attempts WHERE kind = 'fail' AND email = ?")->execute([$email]); } catch (Throwable $e) {}
}

function requireUser(PDO $pdo): array {
    $token = bearer();
    if (!$token) fail(401, 'Chýba prihlasovací token');
    $stmt = $pdo->prepare('SELECT u.id, u.name, u.email, t.id AS token_id, DATEDIFF(t.expires_at, NOW()) AS days_left
                           FROM CarTracking_api_tokens t JOIN CarTracking_users u ON u.id = t.user_id
                           WHERE t.token_hash = ? AND t.expires_at > NOW()');
    $stmt->execute([hash('sha256', $token)]);
    $user = $stmt->fetch();
    if (!$user) fail(401, 'Token je neplatný alebo vypršal');
    // token sa používaním predlžuje (používaný telefón sa neodhlási); nepoužívaný vyprší po token_days dňoch
    global $config;
    $days = (int)($config['token_days'] ?? 90);
    if ((int)$user['days_left'] < intdiv($days, 2)) {
        $pdo->prepare('UPDATE CarTracking_api_tokens SET last_used_at = NOW(), expires_at = DATE_ADD(NOW(), INTERVAL ? DAY) WHERE id = ?')
            ->execute([$days, $user['token_id']]);
    } else {
        $pdo->prepare('UPDATE CarTracking_api_tokens SET last_used_at = NOW() WHERE id = ?')->execute([$user['token_id']]);
    }
    return $user;
}

function publicUser(array $u): array {
    return ['id' => (int)$u['id'], 'name' => $u['name'], 'email' => $u['email']];
}

function num($v): ?float { return is_numeric($v) ? (float)$v : null; }

/** Prístup k vozidlu: 'owner', 'shared' alebo null. */
function vehicleAccess(PDO $pdo, string $uuid, int $uid): ?string {
    $st = $pdo->prepare('SELECT v.user_id, (SELECT 1 FROM CarTracking_vehicle_shares s WHERE s.vehicle_uuid = v.uuid AND s.user_id = ?) AS sh
                         FROM CarTracking_vehicles v WHERE v.uuid = ?');
    $st->execute([$uid, $uuid]);
    $r = $st->fetch();
    if (!$r) return null;
    if ((int)$r['user_id'] === $uid) return 'owner';
    return $r['sh'] ? 'shared' : null;
}

const TRIP_COLS = 'uuid, started_at, ended_at, distance_m, max_speed_kmh, avg_speed_kmh,
                   start_lat, start_lng, end_lat, end_lng, trigger_source,
                   category, note, start_address, end_address, score, hard_brakes, hard_accels, speeding_sec,
                   type, steps, vehicle_uuid, start_place, end_place';

// ---------- Routing ----------
$method = $_SERVER['REQUEST_METHOD'];
$path   = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH) ?? '/';
$script = str_replace('\\', '/', $_SERVER['SCRIPT_NAME'] ?? '');
$base   = str_ends_with($script, '.php') ? rtrim(dirname($script), '/') : '';
if ($base !== '' && str_starts_with($path, $base)) $path = substr($path, strlen($base));
$path = '/' . trim(preg_replace('#^/index\.php#', '', $path), '/');

$pdo = db($config);

// POST /register
if ($method === 'POST' && $path === '/register') {
    $b = body();
    $name  = trim((string)($b['name'] ?? ''));
    $email = strtolower(trim((string)($b['email'] ?? '')));
    $pass  = (string)($b['password'] ?? '');
    if ($name === '' || mb_strlen($name) > 100) fail(422, 'Zadajte meno');
    if (!filter_var($email, FILTER_VALIDATE_EMAIL)) fail(422, 'Neplatný e-mail');
    if (strlen($pass) < 8) fail(422, 'Heslo musí mať aspoň 8 znakov');
    if (strlen($pass) > 200) fail(422, 'Heslo je príliš dlhé');
    if (tooManyAttempts($pdo, 'register', $email)) fail(429, 'Príliš veľa registrácií z tejto siete. Skúste to neskôr.');
    recordAttempt($pdo, 'register', $email);

    $exists = $pdo->prepare('SELECT 1 FROM CarTracking_users WHERE email = ?');
    $exists->execute([$email]);
    if ($exists->fetch()) fail(409, 'Účet s týmto e-mailom už existuje');

    $pdo->prepare('INSERT INTO CarTracking_users (name, email, password_hash) VALUES (?, ?, ?)')
        ->execute([$name, $email, password_hash($pass, PASSWORD_DEFAULT)]);
    $id = (int)$pdo->lastInsertId();
    $token = issueToken($pdo, $id, $b['device_name'] ?? null, $config['token_days']);
    respond(201, ['token' => $token, 'user' => ['id' => $id, 'name' => $name, 'email' => $email]]);
}

// POST /login
if ($method === 'POST' && $path === '/login') {
    $b = body();
    $email = strtolower(trim((string)($b['email'] ?? '')));
    $pass  = (string)($b['password'] ?? '');
    if ($email === '' || strlen($pass) > 200) fail(401, 'Nesprávny e-mail alebo heslo');
    if (tooManyAttempts($pdo, 'fail', $email)) fail(429, 'Príliš veľa neúspešných pokusov. Skúste to znova o 15 minút.');
    $stmt = $pdo->prepare('SELECT * FROM CarTracking_users WHERE email = ?');
    $stmt->execute([$email]);
    $user = $stmt->fetch();
    if (!$user || !password_verify($pass, $user['password_hash'])) {
        recordAttempt($pdo, 'fail', $email);
        fail(401, 'Nesprávny e-mail alebo heslo');
    }
    clearFailures($pdo, $email);
    if (password_needs_rehash($user['password_hash'], PASSWORD_DEFAULT)) {
        $pdo->prepare('UPDATE CarTracking_users SET password_hash = ? WHERE id = ?')
            ->execute([password_hash($pass, PASSWORD_DEFAULT), $user['id']]);
    }
    $token = issueToken($pdo, (int)$user['id'], $b['device_name'] ?? null, $config['token_days']);
    respond(200, ['token' => $token, 'user' => publicUser($user)]);
}

// Všetko ďalej vyžaduje prihlásenie
$user = requireUser($pdo);
$uid  = (int)$user['id'];

if ($method === 'POST' && $path === '/logout') {
    $pdo->prepare('DELETE FROM CarTracking_api_tokens WHERE id = ?')->execute([$user['token_id']]);
    respond(200, ['ok' => true]);
}

if ($method === 'GET' && $path === '/me') {
    respond(200, ['user' => publicUser($user)]);
}

// POST /trips/sync – idempotentný upsert jázd + bodov
if ($method === 'POST' && $path === '/trips/sync') {
    $b = body();
    $trips = $b['trips'] ?? null;
    if (!is_array($trips)) fail(422, 'Chýba pole trips');

    $totalPoints = array_sum(array_map(fn($t) => is_array($t['points'] ?? null) ? count($t['points']) : 0, $trips));
    if ($totalPoints > $config['max_points_per_request']) fail(413, 'Príliš veľa bodov v jednej požiadavke');

    $upsert = $pdo->prepare(
        'INSERT INTO CarTracking_trips (user_id, uuid, started_at, ended_at, distance_m, max_speed_kmh, avg_speed_kmh,
                            start_lat, start_lng, end_lat, end_lng, trigger_source,
                            category, note, start_address, end_address, score, hard_brakes, hard_accels, speeding_sec,
                            type, steps, vehicle_uuid, start_place, end_place)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE started_at = VALUES(started_at), ended_at = VALUES(ended_at), distance_m = VALUES(distance_m),
             start_lat = VALUES(start_lat), start_lng = VALUES(start_lng), trigger_source = VALUES(trigger_source),
             max_speed_kmh = VALUES(max_speed_kmh), avg_speed_kmh = VALUES(avg_speed_kmh),
             end_lat = VALUES(end_lat), end_lng = VALUES(end_lng),
             category = VALUES(category), note = VALUES(note),
             start_address = COALESCE(VALUES(start_address), start_address),
             end_address = COALESCE(VALUES(end_address), end_address),
             score = VALUES(score), hard_brakes = VALUES(hard_brakes),
             hard_accels = VALUES(hard_accels), speeding_sec = VALUES(speeding_sec),
             type = VALUES(type), steps = VALUES(steps), vehicle_uuid = VALUES(vehicle_uuid),
             start_place = VALUES(start_place), end_place = VALUES(end_place)');
    $findTrip = $pdo->prepare('SELECT id, user_id FROM CarTracking_trips WHERE uuid = ?');
    $insPoint = $pdo->prepare(
        'INSERT INTO CarTracking_trip_points (trip_id, ts, lat, lng, speed_kmh, accuracy_m, bearing, altitude_m, event)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE event = VALUES(event)');   // opravený limit môže zrušiť udalosť „speeding“

    $synced = [];
    $pdo->beginTransaction();
    try {
        foreach ($trips as $t) {
            $uuid = (string)($t['uuid'] ?? '');
            if (!preg_match('/^[0-9a-f-]{36}$/i', $uuid) || !is_numeric($t['started_at'] ?? null)) continue;

            $findTrip->execute([$uuid]);
            $existing = $findTrip->fetch();
            if ($existing && (int)$existing['user_id'] !== $uid) continue; // cudzia jazda

            $source = in_array($t['trigger_source'] ?? 'auto', ['auto', 'bluetooth', 'manual'], true) ? $t['trigger_source'] : 'auto';
            $upsert->execute([
                $uid, $uuid, (int)$t['started_at'],
                is_numeric($t['ended_at'] ?? null) ? (int)$t['ended_at'] : null,
                num($t['distance_m'] ?? 0) ?? 0, num($t['max_speed_kmh'] ?? 0) ?? 0, num($t['avg_speed_kmh'] ?? 0) ?? 0,
                num($t['start_lat'] ?? null), num($t['start_lng'] ?? null),
                num($t['end_lat'] ?? null), num($t['end_lng'] ?? null), $source,
                ($t['category'] ?? '') === 'business' ? 'business' : 'private',
                mb_substr((string)($t['note'] ?? ''), 0, 500),
                isset($t['start_address']) ? mb_substr((string)$t['start_address'], 0, 255) : null,
                isset($t['end_address']) ? mb_substr((string)$t['end_address'], 0, 255) : null,
                is_numeric($t['score'] ?? null) ? max(0, min(100, (int)$t['score'])) : null,
                max(0, (int)($t['hard_brakes'] ?? 0)), max(0, (int)($t['hard_accels'] ?? 0)),
                max(0, (int)($t['speeding_sec'] ?? 0)),
                ($t['type'] ?? '') === 'walk' ? 'walk' : 'car',
                max(0, (int)($t['steps'] ?? 0)),
                preg_match('/^[0-9a-f-]{36}$/i', (string)($t['vehicle_uuid'] ?? '')) ? $t['vehicle_uuid'] : null,
                isset($t['start_place']) ? mb_substr((string)$t['start_place'], 0, 100) : null,
                isset($t['end_place']) ? mb_substr((string)$t['end_place'], 0, 100) : null,
            ]);
            $findTrip->execute([$uuid]);
            $tripId = (int)$findTrip->fetch()['id'];

            foreach (($t['points'] ?? []) as $p) {
                if (!is_numeric($p['ts'] ?? null) || !is_numeric($p['lat'] ?? null) || !is_numeric($p['lng'] ?? null)) continue;
                $insPoint->execute([
                    $tripId, (int)$p['ts'], (float)$p['lat'], (float)$p['lng'],
                    num($p['speed_kmh'] ?? null), num($p['accuracy_m'] ?? null),
                    num($p['bearing'] ?? null), num($p['altitude_m'] ?? null),
                    in_array($p['event'] ?? null, ['brake', 'accel', 'speeding'], true) ? $p['event'] : null,
                ]);
            }
            $synced[] = $uuid;
        }
        $pdo->commit();
    } catch (Throwable $e) {
        $pdo->rollBack();
        fail(500, 'Chyba pri ukladaní jázd');
    }
    respond(200, ['synced' => $synced]);
}

// Nastavenia aplikácie (JSON) – po prihlásení na novom telefóne sa stiahnu
if ($path === '/settings' && $method === 'GET') {
    $st = $pdo->prepare('SELECT data, updated_at FROM CarTracking_settings WHERE user_id = ?');
    $st->execute([$uid]);
    $r = $st->fetch();
    respond(200, ['settings' => $r ? json_decode($r['data'], true) : null, 'updated_at' => $r ? (int)$r['updated_at'] : 0]);
}
if ($path === '/settings' && ($method === 'PUT' || $method === 'POST')) {
    $b = body();
    if (!is_array($b['settings'] ?? null)) fail(422, 'Chýba objekt settings');
    $data = json_encode($b['settings'], JSON_UNESCAPED_UNICODE);
    if ($data === false || strlen($data) > 50000) fail(413, 'Nastavenia sú príliš veľké');
    $ts = max(0, (int)($b['updated_at'] ?? 0));
    $pdo->prepare('INSERT INTO CarTracking_settings (user_id, data, updated_at) VALUES (?, ?, ?)
                   ON DUPLICATE KEY UPDATE data = IF(VALUES(updated_at) >= updated_at, VALUES(data), data),
                                           updated_at = GREATEST(updated_at, VALUES(updated_at))')
        ->execute([$uid, $data, $ts]);
    respond(200, ['ok' => true, 'updated_at' => $ts]);
}

// GET /backup/status – čo má používateľ zálohované na serveri (aplikácia podľa toho rozhodne, či má čo sťahovať)
if ($method === 'GET' && $path === '/backup/status') {
    $count = function (string $sql) use ($pdo, $uid): int {
        try { $st = $pdo->prepare($sql); $st->execute([$uid]); return (int)$st->fetchColumn(); } catch (Throwable $e) { return 0; }
    };
    respond(200, [
        'trips'    => $count('SELECT COUNT(*) FROM CarTracking_trips WHERE user_id = ?'),
        'vehicles' => $count('SELECT COUNT(*) FROM CarTracking_vehicles WHERE user_id = ?'),
        'shared_vehicles' => $count('SELECT COUNT(*) FROM CarTracking_vehicle_shares WHERE user_id = ?'),
        'items'    => $count('SELECT COUNT(*) FROM CarTracking_items WHERE user_id = ? AND deleted = 0'),
        'settings' => $count('SELECT COUNT(*) FROM CarTracking_settings WHERE user_id = ?') > 0,
    ]);
}

// POST /trips/points – body viacerých vlastných jázd naraz (obnova údajov po prihlásení)
if ($method === 'POST' && $path === '/trips/points') {
    $uuids = array_values(array_filter((array)(body()['uuids'] ?? []), fn($u) => is_string($u) && preg_match('/^[0-9a-f-]{36}$/i', $u)));
    if (count($uuids) > 50) fail(413, 'Najviac 50 jázd naraz');
    $out = [];
    if ($uuids) {
        $in = implode(',', array_fill(0, count($uuids), '?'));
        $st = $pdo->prepare("SELECT t.uuid, p.ts, p.lat, p.lng, p.speed_kmh, p.accuracy_m, p.bearing, p.altitude_m, p.event
                             FROM CarTracking_trips t JOIN CarTracking_trip_points p ON p.trip_id = t.id
                             WHERE t.user_id = ? AND t.uuid IN ($in) ORDER BY t.uuid, p.ts");
        $st->execute(array_merge([$uid], $uuids));
        foreach ($uuids as $u) $out[$u] = [];
        while ($r = $st->fetch()) { $u = $r['uuid']; unset($r['uuid']); $out[$u][] = $r; }
    }
    respond(200, ['points' => (object)$out]);
}

// POST /vehicles/sync – upsert vozidiel
if ($method === 'POST' && $path === '/vehicles/sync') {
    $b = body();
    $vehicles = $b['vehicles'] ?? null;
    if (!is_array($vehicles)) fail(422, 'Chýba pole vehicles');
    $find = $pdo->prepare('SELECT user_id FROM CarTracking_vehicles WHERE uuid = ?');
    $upsert = $pdo->prepare(
        'INSERT INTO CarTracking_vehicles (user_id, uuid, name, plate, fuel_type, consumption, fuel_price,
                                          odometer_km, odometer_date, bt_name, archived, updated_at,
                                          limit_urban, limit_rural, limit_highway, rate_per_km, bt_address)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE name = VALUES(name), plate = VALUES(plate), fuel_type = VALUES(fuel_type),
             consumption = VALUES(consumption), fuel_price = VALUES(fuel_price), odometer_km = VALUES(odometer_km),
             odometer_date = VALUES(odometer_date), bt_name = VALUES(bt_name), archived = VALUES(archived),
             updated_at = VALUES(updated_at), limit_urban = VALUES(limit_urban),
             limit_rural = VALUES(limit_rural), limit_highway = VALUES(limit_highway), rate_per_km = VALUES(rate_per_km),
             bt_address = VALUES(bt_address)');
    $synced = [];
    foreach ($vehicles as $v) {
        $uuid = (string)($v['uuid'] ?? '');
        $name = trim((string)($v['name'] ?? ''));
        if (!preg_match('/^[0-9a-f-]{36}$/i', $uuid) || $name === '') continue;
        $find->execute([$uuid]);
        $owner = $find->fetchColumn();
        if ($owner !== false && (int)$owner !== $uid) {
            // zdieľané vozidlo upravuje iba vlastník – zmenu potvrdíme, aby ju aplikácia neposielala stále dookola
            if (vehicleAccess($pdo, $uuid, $uid) === 'shared') $synced[] = $uuid;
            continue;
        }
        $fuel = in_array($v['fuel_type'] ?? '', ['petrol', 'diesel', 'lpg', 'hybrid', 'electric'], true) ? $v['fuel_type'] : 'petrol';
        $upsert->execute([
            $uid, $uuid, mb_substr($name, 0, 100), mb_substr((string)($v['plate'] ?? ''), 0, 20), $fuel,
            max(0, min(999, (float)($v['consumption'] ?? 0))), max(0, min(99, (float)($v['fuel_price'] ?? 0))),
            max(0, (int)($v['odometer_km'] ?? 0)), max(0, (int)($v['odometer_date'] ?? 0)),
            mb_substr((string)($v['bt_name'] ?? ''), 0, 100), !empty($v['archived']) ? 1 : 0,
            max(0, (int)($v['updated_at'] ?? 0)),
            max(10, min(150, (int)($v['limit_urban'] ?? 50))),
            max(10, min(150, (int)($v['limit_rural'] ?? 90))),
            max(10, min(200, (int)($v['limit_highway'] ?? 130))),
            max(0, min(9.9999, (float)($v['rate_per_km'] ?? 0))),
            preg_match('/^[0-9A-Fa-f:]{0,17}$/', (string)($v['bt_address'] ?? '')) ? (string)($v['bt_address'] ?? '') : '',
        ]);
        $synced[] = $uuid;
    }
    respond(200, ['synced' => $synced]);
}

// POST /items/sync – miesta, tankovania, pripomienky (novšia verzia vyhráva)
if ($method === 'POST' && $path === '/items/sync') {
    $b = body();
    $items = $b['items'] ?? null;
    if (!is_array($items)) fail(422, 'Chýba pole items');
    if (count($items) > 500) fail(413, 'Príliš veľa položiek v jednej požiadavke');
    $find = $pdo->prepare('SELECT user_id, updated_at FROM CarTracking_items WHERE uuid = ?');
    $ins = $pdo->prepare('INSERT INTO CarTracking_items (user_id, kind, uuid, data, updated_at, deleted) VALUES (?, ?, ?, ?, ?, ?)');
    $upd = $pdo->prepare('UPDATE CarTracking_items SET kind = ?, data = ?, updated_at = ?, deleted = ? WHERE uuid = ? AND user_id = ?');
    $synced = [];
    foreach ($items as $it) {
        $uuid = (string)($it['uuid'] ?? '');
        $kind = (string)($it['kind'] ?? '');
        if (!preg_match('/^[0-9a-f-]{36}$/i', $uuid) || !in_array($kind, ['place', 'refuel', 'reminder', 'expense'], true)) continue;
        $data = json_encode($it['data'] ?? new stdClass(), JSON_UNESCAPED_UNICODE);
        if ($data === false || strlen($data) > 20000) continue;
        $ts = max(0, (int)($it['updated_at'] ?? 0));
        $del = !empty($it['deleted']) ? 1 : 0;
        $find->execute([$uuid]);
        $row = $find->fetch();
        if (!$row) $ins->execute([$uid, $kind, $uuid, $data, $ts, $del]);
        elseif ((int)$row['user_id'] !== $uid) continue;
        elseif ($ts >= (int)$row['updated_at']) $upd->execute([$kind, $data, $ts, $del, $uuid, $uid]);
        $synced[] = $uuid;
    }
    respond(200, ['synced' => $synced]);
}

if ($method === 'GET' && $path === '/items') {
    $kind = (string)($_GET['kind'] ?? '');
    $sql = 'SELECT kind, uuid, data, updated_at, deleted FROM CarTracking_items WHERE user_id = ?';
    $args = [$uid];
    if ($kind !== '') { $sql .= ' AND kind = ?'; $args[] = $kind; }
    $stmt = $pdo->prepare($sql . ' ORDER BY updated_at DESC');
    $stmt->execute($args);
    $rows = array_map(function ($r) { $r['data'] = json_decode($r['data'], true); return $r; }, $stmt->fetchAll());
    respond(200, ['items' => $rows]);
}

if ($method === 'GET' && $path === '/vehicles') {
    $stmt = $pdo->prepare('SELECT v.uuid, v.name, v.plate, v.fuel_type, v.consumption, v.fuel_price, v.odometer_km, v.odometer_date,
                                  v.bt_name, v.archived, v.updated_at, v.limit_urban, v.limit_rural, v.limit_highway, v.rate_per_km,
                                  IF(v.user_id = ?, v.bt_address, \'\') AS bt_address,
                                  (v.user_id = ?) AS is_owner, u.name AS owner_name,
                                  (SELECT COUNT(*) FROM CarTracking_vehicle_shares s2 WHERE s2.vehicle_uuid = v.uuid) AS share_count
                           FROM CarTracking_vehicles v JOIN CarTracking_users u ON u.id = v.user_id
                           WHERE v.user_id = ? OR v.uuid IN (SELECT vehicle_uuid FROM CarTracking_vehicle_shares WHERE user_id = ?)
                           ORDER BY v.name');
    $stmt->execute([$uid, $uid, $uid, $uid]);
    $rows = array_map(function ($r) {
        $r['is_owner'] = (bool)$r['is_owner']; $r['archived'] = (bool)$r['archived'];
        foreach (['consumption', 'fuel_price', 'rate_per_km'] as $k) $r[$k] = (float)$r[$k];
        foreach (['odometer_km', 'odometer_date', 'updated_at', 'limit_urban', 'limit_rural', 'limit_highway', 'share_count'] as $k) $r[$k] = (int)$r[$k];
        return $r;
    }, $stmt->fetchAll());
    respond(200, ['vehicles' => $rows]);
}

// Zdieľanie vozidla s iným používateľom (podľa e-mailu)
if (preg_match('#^/vehicles/([0-9a-fA-F-]{36})/shares(?:/(\d+))?$#', $path, $m)) {
    $vuuid = $m[1];
    $access = vehicleAccess($pdo, $vuuid, $uid);
    if ($access === null) fail(404, 'Vozidlo neexistuje');
    if ($method === 'GET' && !isset($m[2])) {
        if ($access !== 'owner') fail(403, 'Zdieľanie spravuje iba vlastník vozidla');
        $st = $pdo->prepare('SELECT u.id AS user_id, u.name, u.email FROM CarTracking_vehicle_shares s
                             JOIN CarTracking_users u ON u.id = s.user_id WHERE s.vehicle_uuid = ? ORDER BY u.name');
        $st->execute([$vuuid]);
        respond(200, ['shares' => array_map(fn($r) => ['user_id' => (int)$r['user_id'], 'name' => $r['name'], 'email' => $r['email']], $st->fetchAll())]);
    }
    if ($method === 'POST' && !isset($m[2])) {
        if ($access !== 'owner') fail(403, 'Zdieľanie spravuje iba vlastník vozidla');
        $email = strtolower(trim((string)(body()['email'] ?? '')));
        $st = $pdo->prepare('SELECT id, name, email FROM CarTracking_users WHERE email = ?');
        $st->execute([$email]);
        $other = $st->fetch();
        if (!$other) fail(404, 'Používateľ s týmto e-mailom nemá účet na tomto serveri');
        if ((int)$other['id'] === $uid) fail(422, 'Vozidlo je už vaše');
        $pdo->prepare('INSERT IGNORE INTO CarTracking_vehicle_shares (vehicle_uuid, user_id) VALUES (?, ?)')->execute([$vuuid, $other['id']]);
        respond(201, ['share' => ['user_id' => (int)$other['id'], 'name' => $other['name'], 'email' => $other['email']]]);
    }
    if ($method === 'DELETE' && isset($m[2])) {
        $target = (int)$m[2];
        if ($access !== 'owner' && $target !== $uid) fail(403, 'Zdieľanie spravuje iba vlastník vozidla');
        $pdo->prepare('DELETE FROM CarTracking_vehicle_shares WHERE vehicle_uuid = ? AND user_id = ?')->execute([$vuuid, $target]);
        respond(200, ['ok' => true]);
    }
}

// Spoločná kniha jázd vozidla – jazdy všetkých vodičov
if ($method === 'GET' && preg_match('#^/vehicles/([0-9a-fA-F-]{36})/trips$#', $path, $m)) {
    if (vehicleAccess($pdo, $m[1], $uid) === null) fail(404, 'Vozidlo neexistuje');
    $from = (int)($_GET['from'] ?? 0);
    $to   = (int)($_GET['to'] ?? PHP_INT_MAX);
    $cols = implode(', ', array_map(fn($c) => 't.' . trim($c), explode(',', TRIP_COLS)));
    $st = $pdo->prepare("SELECT $cols, u.name AS driver_name, (t.user_id = ?) AS mine
                         FROM CarTracking_trips t JOIN CarTracking_users u ON u.id = t.user_id
                         WHERE t.vehicle_uuid = ? AND t.type = 'car' AND t.started_at >= ? AND t.started_at < ?
                         ORDER BY t.started_at LIMIT 5000");
    $st->execute([$uid, $m[1], $from, $to]);
    respond(200, ['trips' => $st->fetchAll()]);
}

if ($method === 'GET' && $path === '/trips') {
    $limit  = max(1, min(2000, (int)($_GET['limit'] ?? 50)));
    $offset = max(0, (int)($_GET['offset'] ?? 0));
    $where = 'user_id = ?';
    $args = [$uid];
    if (isset($_GET['from'])) { $where .= ' AND started_at >= ?'; $args[] = (int)$_GET['from']; }
    if (isset($_GET['to']))   { $where .= ' AND started_at < ?';  $args[] = (int)$_GET['to']; }
    if (in_array($_GET['type'] ?? '', ['car', 'walk'], true)) { $where .= ' AND type = ?'; $args[] = $_GET['type']; }
    $stmt = $pdo->prepare('SELECT ' . TRIP_COLS . " FROM CarTracking_trips WHERE $where ORDER BY started_at DESC LIMIT $limit OFFSET $offset");
    $stmt->execute($args);
    respond(200, ['trips' => $stmt->fetchAll()]);
}

if (preg_match('#^/trips/([0-9a-fA-F-]{36})$#', $path, $m)) {
    $stmt = $pdo->prepare('SELECT * FROM CarTracking_trips WHERE uuid = ?');
    $stmt->execute([$m[1]]);
    $trip = $stmt->fetch();
    $own = $trip && (int)$trip['user_id'] === $uid;
    // jazdu iného vodiča vidí len ten, kto má prístup k jej vozidlu (zdieľané vozidlo); zmazať ju nemôže
    if (!$trip || (!$own && ($method !== 'GET' || !$trip['vehicle_uuid'] || vehicleAccess($pdo, $trip['vehicle_uuid'], $uid) === null))) {
        fail(404, 'Jazda neexistuje');
    }

    if ($method === 'GET') {
        $p = $pdo->prepare('SELECT ts, lat, lng, speed_kmh, accuracy_m, bearing, altitude_m, event
                            FROM CarTracking_trip_points WHERE trip_id = ? ORDER BY ts');
        $p->execute([$trip['id']]);
        unset($trip['id'], $trip['user_id']);
        respond(200, ['trip' => $trip, 'points' => $p->fetchAll()]);
    }
    if ($method === 'DELETE') {
        $pdo->prepare('DELETE FROM CarTracking_trips WHERE id = ?')->execute([$trip['id']]);
        respond(200, ['ok' => true]);
    }
}

fail(404, 'Neznámy endpoint');
