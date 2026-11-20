<?php
// App: connects a phone to a company. The invite code decides which company.
require_once __DIR__ . '/config.php';

$in    = read_json();
$emp   = trim((string)($in['employee_id'] ?? ''));
$name  = trim((string)($in['name'] ?? ''));
$phone = preg_replace('/[^0-9+]/', '', (string)($in['phone'] ?? ''));
$mode  = (int)($in['sim_mode'] ?? 0);
$dev   = is_array($in['device'] ?? null) ? $in['device'] : [];

try {
    $co = find_company_by_code((string)($in['invite_code'] ?? ''));
} catch (Throwable $e) {
    error_log($e->getMessage());
    json_out(500, ['error' => 'Server error']);
}
if (!$co) {
    usleep(700000);
    json_out(403, ['error' => 'Invalid invite code']);
}
if ($co['status'] !== 'active') json_out(403, ['error' => 'Company is blocked']);

if ($emp === '' || mb_strlen($emp) > 64 || $name === '' || mb_strlen($name) > 128 || strlen($phone) > 20) {
    json_out(400, ['error' => 'Invalid employee_id or name']);
}
if (!in_array($mode, [0, 1, 2], true)) $mode = 0;

$pdo = db();
$companyId = (int)$co['id'];

// Was this employee already registered (new phone / reinstall)?
$s = $pdo->prepare('SELECT id, status FROM callers WHERE company_id = ? AND employee_id = ?');
$s->execute([$companyId, $emp]);
$existing = $s->fetch();
if ($existing && $existing['status'] !== 'active') json_out(403, ['error' => 'Caller is blocked']);

if (!$existing && (int)$co['max_callers'] > 0) {
    $s = $pdo->prepare('SELECT COUNT(*) FROM callers WHERE company_id = ?');
    $s->execute([$companyId]);
    if ((int)$s->fetchColumn() >= (int)$co['max_callers']) json_out(409, ['error' => 'Caller limit reached']);
}

$token = bin2hex(random_bytes(32));
$hash  = hash('sha256', $token);
$str = function ($k, $len) use ($dev) { return isset($dev[$k]) ? mb_substr((string)$dev[$k], 0, $len) : null; };

// Re-registering the same employee_id replaces the old token (old phone stops syncing).
$stmt = $pdo->prepare(
    'INSERT INTO callers (company_id, employee_id, name, phone, token_hash, sim_mode, sim1_name, sim2_name,
                          device_model, android_version, app_version, registered_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())
     ON DUPLICATE KEY UPDATE name = VALUES(name), phone = VALUES(phone), token_hash = VALUES(token_hash),
       sim_mode = VALUES(sim_mode), sim1_name = VALUES(sim1_name), sim2_name = VALUES(sim2_name),
       device_model = VALUES(device_model), android_version = VALUES(android_version),
       app_version = VALUES(app_version), registered_at = UTC_TIMESTAMP()'
);
$stmt->execute([$companyId, $emp, $name, $phone ?: null, $hash, $mode,
    $str('sim1', 64), $str('sim2', 64), $str('model', 100), $str('android', 20), $str('app', 30)]);

json_out(200, [
    'token'        => $token,
    'company_name' => $co['name'],
    'record_calls' => (bool)$co['record_calls'],
]);
