<?php
// App: uploads the phone's own call recording (multipart: device_call_id + file).
require_once __DIR__ . '/config.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') json_out(405, ['error' => 'POST only']);
$caller = auth_caller();
if (!(int)$caller['record_calls']) json_out(403, ['error' => 'Recording upload is off for this company']);

$id = (string)($_POST['device_call_id'] ?? '');
if ($id === '' || strlen($id) > 64) json_out(400, ['error' => 'device_call_id missing']);

$f = $_FILES['file'] ?? null;
if (!$f || ($f['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK) {
    $tooBig = in_array($f['error'] ?? 0, [UPLOAD_ERR_INI_SIZE, UPLOAD_ERR_FORM_SIZE], true);
    json_out($tooBig ? 413 : 400, ['error' => $tooBig ? 'File too large' : 'File missing']);
}
if ($f['size'] <= 0 || $f['size'] > RECORDING_MAX_MB * 1024 * 1024) json_out(413, ['error' => 'File too large']);

$ext = strtolower(pathinfo((string)$f['name'], PATHINFO_EXTENSION));
$allowed = ['m4a', 'mp3', 'amr', 'aac', '3gp', '3gpp', 'wav', 'ogg', 'opus', 'awb', 'mp4'];
if (!in_array($ext, $allowed, true)) json_out(415, ['error' => 'Unsupported file type']);

$pdo = db();
$s = $pdo->prepare('SELECT id, recording_file FROM call_logs WHERE caller_id = ? AND device_call_id = ?');
$s->execute([(int)$caller['id'], $id]);
$row = $s->fetch();
if (!$row) json_out(404, ['error' => 'Call not synced yet']);

$rel = (int)$caller['company_id'] . '/' . (int)$caller['id'] . '/' . preg_replace('/[^A-Za-z0-9_-]/', '_', $id) . '.' . $ext;
$dest = RECORDINGS_DIR . '/' . $rel;
if (!is_dir(dirname($dest)) && !mkdir(dirname($dest), 0750, true)) {
    error_log('Cannot create ' . dirname($dest));
    json_out(500, ['error' => 'Server cannot store recordings']);
}
if (!move_uploaded_file($f['tmp_name'], $dest)) json_out(500, ['error' => 'Could not save file']);

if ($row['recording_file'] && $row['recording_file'] !== $rel) @unlink(RECORDINGS_DIR . '/' . $row['recording_file']);
$pdo->prepare('UPDATE call_logs SET recording_file = ?, recording_size = ?, recording_at = UTC_TIMESTAMP() WHERE id = ?')
    ->execute([$rel, (int)$f['size'], (int)$row['id']]);

json_out(200, ['ok' => true]);
