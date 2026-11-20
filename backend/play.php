<?php
// Company: listen to / download a call recording.
//   play.php?key=<company api_key>&id=<call_logs.id>
require_once __DIR__ . '/config.php';

$co = auth_company_key();
$id = (int)($_GET['id'] ?? 0);
$s = db()->prepare('SELECT recording_file FROM call_logs WHERE id = ? AND company_id = ?');
$s->execute([$id, (int)$co['id']]);
$rel = $s->fetchColumn();
if (!$rel) json_out(404, ['error' => 'No recording']);

$path = realpath(RECORDINGS_DIR . '/' . $rel);
$root = realpath(RECORDINGS_DIR);
if (!$path || !$root || strpos($path, $root . DIRECTORY_SEPARATOR) !== 0 || !is_file($path)) {
    json_out(404, ['error' => 'File missing']);
}

$mime = [
    'm4a' => 'audio/mp4', 'mp4' => 'audio/mp4', 'mp3' => 'audio/mpeg', 'amr' => 'audio/amr', 'awb' => 'audio/amr-wb',
    'aac' => 'audio/aac', '3gp' => 'audio/3gpp', '3gpp' => 'audio/3gpp', 'wav' => 'audio/wav',
    'ogg' => 'audio/ogg', 'opus' => 'audio/ogg',
][strtolower(pathinfo($path, PATHINFO_EXTENSION))] ?? 'application/octet-stream';

header('Content-Type: ' . $mime);
header('Content-Length: ' . filesize($path));
header('Content-Disposition: ' . (isset($_GET['download']) ? 'attachment' : 'inline') . '; filename="call-' . $id . '.' . pathinfo($path, PATHINFO_EXTENSION) . '"');
header('Cache-Control: private, max-age=3600');
readfile($path);
