<?php
// Shared helpers. Loaded by config.php — do not open this file directly.
if (!defined('DB_HOST')) { http_response_code(404); exit; }

function db(): PDO {
    static $pdo = null;
    if ($pdo === null) {
        $pdo = new PDO(
            'mysql:host=' . DB_HOST . ';dbname=' . DB_NAME . ';charset=utf8mb4',
            DB_USER, DB_PASS,
            [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
             PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
             PDO::ATTR_EMULATE_PREPARES => false]
        );
        $pdo->exec("SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci, time_zone = '+00:00'");
    }
    return $pdo;
}

function json_out(int $code, array $data) {
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}

function read_json(): array {
    if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') json_out(405, ['error' => 'POST only']);
    $raw = file_get_contents('php://input');
    if (strlen($raw) > 4 * 1024 * 1024) json_out(413, ['error' => 'Too large']);
    $data = json_decode($raw, true);
    if (!is_array($data)) json_out(400, ['error' => 'Invalid JSON']);
    return $data;
}

/** Last 10 digits of a phone number — used to match the same client across +91 / 0 / spaces. */
function number_key(string $num): string {
    $d = preg_replace('/\D+/', '', $num);
    return strlen($d) > 10 ? substr($d, -10) : $d;
}

function clean_code(string $c): string {
    return strtoupper(preg_replace('/[^A-Za-z0-9]/', '', $c));
}

/** 8 characters, no look-alike letters (0/O, 1/I). */
function new_invite_code(): string {
    $abc = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    for ($try = 0; $try < 20; $try++) {
        $c = '';
        for ($i = 0; $i < 8; $i++) $c .= $abc[random_int(0, 31)];
        $s = db()->prepare('SELECT 1 FROM companies WHERE invite_code = ?');
        $s->execute([$c]);
        if (!$s->fetch()) return $c;
    }
    throw new RuntimeException('Could not create a unique invite code');
}

/**
 * Makes sure every row of `users` has a company with an invite code.
 * Works even when the MySQL trigger could not be created on the hosting.
 */
function ensure_companies(): void {
    static $done = false;
    if ($done) return;
    $done = true;
    try {
        $rows = db()->query(
            'SELECT u.id, u.name, u.username FROM users u
             LEFT JOIN companies c ON c.user_id = u.id
             WHERE c.id IS NULL LIMIT 500'
        )->fetchAll();
        $ins = db()->prepare('INSERT IGNORE INTO companies (user_id, name, invite_code) VALUES (?, ?, ?)');
        foreach ($rows as $u) {
            $name = trim((string)($u['name'] ?? '')) ?: trim((string)($u['username'] ?? '')) ?: ('Company ' . $u['id']);
            $ins->execute([(int)$u['id'], mb_substr($name, 0, 191), new_invite_code()]);
        }
    } catch (Throwable $e) {
        error_log('ensure_companies: ' . $e->getMessage());
    }
}

/**
 * For your own website: show the logged-in company its invite code and report key.
 *   require 'backend/config.php';
 *   $c = company_info_for_user($_SESSION['user_id']);
 *   echo $c['invite_code'];
 */
function company_info_for_user(int $userId): ?array {
    ensure_companies();
    $s = db()->prepare('SELECT * FROM companies WHERE user_id = ?');
    $s->execute([$userId]);
    $c = $s->fetch();
    if (!$c) return null;
    if (empty($c['api_key'])) {
        $c['api_key'] = bin2hex(random_bytes(32));
        db()->prepare('UPDATE companies SET api_key = ? WHERE id = ?')->execute([$c['api_key'], $c['id']]);
    }
    return $c;
}

function find_company_by_code(string $code): ?array {
    $code = clean_code($code);
    if ($code === '' || strlen($code) > 16) return null;
    ensure_companies();
    $s = db()->prepare('SELECT * FROM companies WHERE invite_code = ?');
    $s->execute([$code]);
    return $s->fetch() ?: null;
}

/** Caller from the X-Api-Token header (or 401/403). */
function auth_caller(): array {
    $token = $_SERVER['HTTP_X_API_TOKEN'] ?? '';
    if (!preg_match('/^[a-f0-9]{64}$/', $token)) json_out(401, ['error' => 'Unauthorized']);
    $s = db()->prepare(
        'SELECT cr.*, co.name AS company_name, co.status AS company_status, co.record_calls
         FROM callers cr JOIN companies co ON co.id = cr.company_id
         WHERE cr.token_hash = ?'
    );
    $s->execute([hash('sha256', $token)]);
    $c = $s->fetch();
    if (!$c) json_out(401, ['error' => 'Unauthorized']);
    if ($c['company_status'] !== 'active') json_out(403, ['error' => 'Company is blocked']);
    if ($c['status'] !== 'active') json_out(403, ['error' => 'Caller is blocked']);
    return $c;
}

/** Company from ?key=<api_key> (report / playback endpoints). */
function auth_company_key(): array {
    $key = (string)($_GET['key'] ?? $_SERVER['HTTP_X_COMPANY_KEY'] ?? '');
    if (!preg_match('/^[a-f0-9]{64}$/', $key)) json_out(401, ['error' => 'Unauthorized']);
    $s = db()->prepare('SELECT * FROM companies WHERE api_key = ?');
    $s->execute([$key]);
    $c = $s->fetch();
    if (!$c) { usleep(500000); json_out(401, ['error' => 'Unauthorized']); }
    return $c;
}

function valid_utc(?string $s): bool {
    return is_string($s) && preg_match('/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/', $s) === 1;
}

/* ---------- Attendance helpers ---------- */

/** আজকের তারিখ IST-তে (YYYY-MM-DD). */
function att_today(): string {
    return (new DateTime('now', new DateTimeZone('Asia/Kolkata')))->format('Y-m-d');
}

/** UTC টাইমস্ট্যাম্প -> IST সময় (HH:MM). */
function att_ist_time(?string $utc): ?string {
    if (!$utc) return null;
    return (new DateTime($utc, new DateTimeZone('UTC')))->setTimezone(new DateTimeZone('Asia/Kolkata'))->format('H:i');
}

/** প্রতিদিন নতুন QR token. প্রথমবার চাওয়া হলে আজকের জন্য তৈরি হয়। */
function att_daily_qr(int $companyId): string {
    $pdo = db();
    $day = att_today();
    $s = $pdo->prepare('SELECT token FROM attendance_qr_daily WHERE company_id = ? AND qr_date = ?');
    $s->execute([$companyId, $day]);
    $t = $s->fetchColumn();
    if ($t === false) {
        $pdo->prepare('INSERT IGNORE INTO attendance_qr_daily (company_id, qr_date, token) VALUES (?, ?, ?)')
            ->execute([$companyId, $day, bin2hex(random_bytes(16))]);
        $s->execute([$companyId, $day]);
        $t = $s->fetchColumn();
    }
    return (string)$t;
}

/** দুটি GPS point-এর দূরত্ব (মিটার). */
function att_distance_m(float $la1, float $lo1, float $la2, float $lo2): int {
    $R = 6371000;
    $dLa = deg2rad($la2 - $la1);
    $dLo = deg2rad($lo2 - $lo1);
    $a = sin($dLa / 2) ** 2 + cos(deg2rad($la1)) * cos(deg2rad($la2)) * sin($dLo / 2) ** 2;
    return (int)round(2 * $R * asin(min(1.0, sqrt($a))));
}
