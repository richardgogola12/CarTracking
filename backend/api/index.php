<?php
declare(strict_types=1);

/*
 * Car Tracking REST API (PHP 8.1+, PDO MySQL)
 *
 *  POST   /register        {name,email,password,device_name?}  -> {token,user}
 *  POST   /login           {email,password,device_name?}       -> {token,user}
 *  POST   /logout          (Bearer)                            -> {ok}
 *  GET    /me              (Bearer)                            -> {user}
 *  POST   /trips/sync      (Bearer) {trips:[...]}              -> {synced:[uuid...]}
 *  GET    /trips           (Bearer) ?limit=&offset=            -> {trips:[...]}
 *  GET    /trips/{uuid}    (Bearer)                            -> {trip, points}
 *  DELETE /trips/{uuid}    (Bearer)                            -> {ok}
 */

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Headers: Content-Type, Authorization');
header('Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS');
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
    $stmt = $pdo->prepare('INSERT INTO api_tokens (user_id, token_hash, device_name, expires_at)
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

function requireUser(PDO $pdo): array {
    $token = bearer();
    if (!$token) fail(401, 'Chýba prihlasovací token');
    $stmt = $pdo->prepare('SELECT u.id, u.name, u.email, t.id AS token_id
                           FROM api_tokens t JOIN users u ON u.id = t.user_id
                           WHERE t.token_hash = ? AND t.expires_at > NOW()');
    $stmt->execute([hash('sha256', $token)]);
    $user = $stmt->fetch();
    if (!$user) fail(401, 'Token je neplatný alebo vypršal');
    $pdo->prepare('UPDATE api_tokens SET last_used_at = NOW() WHERE id = ?')->execute([$user['token_id']]);
    return $user;
}

function publicUser(array $u): array {
    return ['id' => (int)$u['id'], 'name' => $u['name'], 'email' => $u['email']];
}

function num($v): ?float { return is_numeric($v) ? (float)$v : null; }

// ---------- Routing ----------
$method = $_SERVER['REQUEST_METHOD'];
$path   = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH) ?? '/';
$base   = rtrim(str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'])), '/');
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

    $exists = $pdo->prepare('SELECT 1 FROM users WHERE email = ?');
    $exists->execute([$email]);
    if ($exists->fetch()) fail(409, 'Účet s týmto e-mailom už existuje');

    $pdo->prepare('INSERT INTO users (name, email, password_hash) VALUES (?, ?, ?)')
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
    $stmt = $pdo->prepare('SELECT * FROM users WHERE email = ?');
    $stmt->execute([$email]);
    $user = $stmt->fetch();
    if (!$user || !password_verify($pass, $user['password_hash'])) fail(401, 'Nesprávny e-mail alebo heslo');
    if (password_needs_rehash($user['password_hash'], PASSWORD_DEFAULT)) {
        $pdo->prepare('UPDATE users SET password_hash = ? WHERE id = ?')
            ->execute([password_hash($pass, PASSWORD_DEFAULT), $user['id']]);
    }
    $token = issueToken($pdo, (int)$user['id'], $b['device_name'] ?? null, $config['token_days']);
    respond(200, ['token' => $token, 'user' => publicUser($user)]);
}

// Všetko ďalej vyžaduje prihlásenie
$user = requireUser($pdo);
$uid  = (int)$user['id'];

if ($method === 'POST' && $path === '/logout') {
    $pdo->prepare('DELETE FROM api_tokens WHERE id = ?')->execute([$user['token_id']]);
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
        'INSERT INTO trips (user_id, uuid, started_at, ended_at, distance_m, max_speed_kmh, avg_speed_kmh,
                            start_lat, start_lng, end_lat, end_lng, trigger_source)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE ended_at = VALUES(ended_at), distance_m = VALUES(distance_m),
             max_speed_kmh = VALUES(max_speed_kmh), avg_speed_kmh = VALUES(avg_speed_kmh),
             end_lat = VALUES(end_lat), end_lng = VALUES(end_lng)');
    $findTrip = $pdo->prepare('SELECT id, user_id FROM trips WHERE uuid = ?');
    $insPoint = $pdo->prepare(
        'INSERT IGNORE INTO trip_points (trip_id, ts, lat, lng, speed_kmh, accuracy_m, bearing, altitude_m)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)');

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
            ]);
            $findTrip->execute([$uuid]);
            $tripId = (int)$findTrip->fetch()['id'];

            foreach (($t['points'] ?? []) as $p) {
                if (!is_numeric($p['ts'] ?? null) || !is_numeric($p['lat'] ?? null) || !is_numeric($p['lng'] ?? null)) continue;
                $insPoint->execute([
                    $tripId, (int)$p['ts'], (float)$p['lat'], (float)$p['lng'],
                    num($p['speed_kmh'] ?? null), num($p['accuracy_m'] ?? null),
                    num($p['bearing'] ?? null), num($p['altitude_m'] ?? null),
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

if ($method === 'GET' && $path === '/trips') {
    $limit  = max(1, min(200, (int)($_GET['limit'] ?? 50)));
    $offset = max(0, (int)($_GET['offset'] ?? 0));
    $stmt = $pdo->prepare("SELECT uuid, started_at, ended_at, distance_m, max_speed_kmh, avg_speed_kmh,
                                  start_lat, start_lng, end_lat, end_lng, trigger_source
                           FROM trips WHERE user_id = ? ORDER BY started_at DESC LIMIT $limit OFFSET $offset");
    $stmt->execute([$uid]);
    respond(200, ['trips' => $stmt->fetchAll()]);
}

if (preg_match('#^/trips/([0-9a-fA-F-]{36})$#', $path, $m)) {
    $stmt = $pdo->prepare('SELECT * FROM trips WHERE uuid = ? AND user_id = ?');
    $stmt->execute([$m[1], $uid]);
    $trip = $stmt->fetch();
    if (!$trip) fail(404, 'Jazda neexistuje');

    if ($method === 'GET') {
        $p = $pdo->prepare('SELECT ts, lat, lng, speed_kmh, accuracy_m, bearing, altitude_m
                            FROM trip_points WHERE trip_id = ? ORDER BY ts');
        $p->execute([$trip['id']]);
        unset($trip['id'], $trip['user_id']);
        respond(200, ['trip' => $trip, 'points' => $p->fetchAll()]);
    }
    if ($method === 'DELETE') {
        $pdo->prepare('DELETE FROM trips WHERE id = ?')->execute([$trip['id']]);
        respond(200, ['ok' => true]);
    }
}

fail(404, 'Neznámy endpoint');
