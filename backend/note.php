<?php
// App: saves call notes written after the call was already uploaded.
require_once __DIR__ . '/config.php';

$in     = read_json();
$caller = auth_caller();
$notes  = $in['notes'] ?? null;
if (!is_array($notes) || count($notes) > 200) json_out(400, ['error' => 'notes must be an array (max 200)']);

$up = db()->prepare(
    'UPDATE call_logs SET note = ?, note_at = UTC_TIMESTAMP() WHERE caller_id = ? AND device_call_id = ?'
);
$saved = 0;
foreach ($notes as $n) {
    if (!is_array($n)) continue;
    $id = (string)($n['device_call_id'] ?? '');
    if ($id === '' || strlen($id) > 64) continue;
    $txt = trim((string)($n['note'] ?? ''));
    $up->execute([$txt === '' ? null : mb_substr($txt, 0, 1000), (int)$caller['id'], $id]);
    $saved += $up->rowCount();
}
json_out(200, ['saved' => $saved]);
