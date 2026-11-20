<?php
// App: QR scan-এর সাথেই যাচাই (face check-এর আগে)। আজকের, এই company-র QR কি না দেখে।
require_once __DIR__ . '/config.php';

$in     = read_json();
$caller = auth_caller();
$cid    = (int)$caller['company_id'];

$qr    = trim((string)($in['qr'] ?? ''));
$token = preg_replace('/^CSATT:/', '', $qr);
if (!preg_match('/^[a-f0-9]{32}$/', $token)) {
    json_out(400, ['error' => 'This is not an attendance QR code.']);
}

$s = db()->prepare('SELECT token FROM attendance_qr_daily WHERE company_id = ? AND qr_date = ?');
$s->execute([$cid, att_today()]);
$real = (string)($s->fetchColumn() ?: '');

if ($real === '') {
    json_out(403, ['error' => "Today's QR is not ready yet. Ask your admin to open the attendance page."]);
}
if (!hash_equals($real, $token)) {
    json_out(403, ['error' => "This QR is old or belongs to another company. Scan today's QR from your admin panel."]);
}
json_out(200, ['ok' => true, 'message' => 'QR accepted']);
