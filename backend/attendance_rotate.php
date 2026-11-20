<?php
// Cron job: প্রতি রাত ১২টায় (IST) সব active company-র জন্য আজকের নতুন QR তৈরি করে।
// CLI থেকে চালান। Hostinger-এ server সময় UTC হলে রাত ১২টা IST = 18:30 UTC.
// Cron: 30 18 * * *  php /home/<user>/domains/linkmy.in/public_html/backend/attendance_rotate.php
if (PHP_SAPI !== 'cli') { http_response_code(403); exit('CLI only'); }
require_once __DIR__ . '/config.php';

$ids = db()->query("SELECT id FROM companies WHERE status = 'active'")->fetchAll(PDO::FETCH_COLUMN);
foreach ($ids as $id) {
    att_daily_qr((int)$id);
}
echo 'Rotated attendance QR for ' . count($ids) . ' companies on ' . att_today() . PHP_EOL;
