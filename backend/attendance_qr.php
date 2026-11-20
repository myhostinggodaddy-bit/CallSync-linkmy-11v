<?php
// Admin panel: আজকের attendance QR (প্রতি IST দিনে নতুন). company key লাগে।
require_once __DIR__ . '/config.php';

$co = auth_company_key();
json_out(200, [
    'date'    => att_today(),
    'payload' => 'CSATT:' . att_daily_qr((int)$co['id']),
]);
