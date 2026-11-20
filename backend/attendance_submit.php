<?php
// App: attendance. আজকের QR + GPS (geofence) + face check. Check-in/out নিজে থেকে ঠিক হয়।
require_once __DIR__ . '/config.php';

const ATT_FACE_MAX_DIFF = 0.10;   // গড় পার্থক্য; আসল phone-এ test করে বাড়ানো/কমানো যাবে

$in       = read_json();
$caller   = auth_caller();
$pdo      = db();
$cid      = (int)$caller['company_id'];
$callerId = (int)$caller['id'];

// ১. QR
$qr    = trim((string)($in['qr'] ?? ''));
$token = preg_replace('/^CSATT:/', '', $qr);
if (!preg_match('/^[a-f0-9]{32}$/', $token)) json_out(400, ['error' => 'QR code is not valid']);

$s = $pdo->prepare('SELECT token FROM attendance_qr_daily WHERE company_id = ? AND qr_date = ?');
$s->execute([$cid, att_today()]);
$real = (string)($s->fetchColumn() ?: '');
if ($real === '' || !hash_equals($real, $token)) {
    json_out(403, ['error' => "This QR is old or belongs to another company. Scan today's QR from your admin panel."]);
}

// ২. GPS
if (!is_numeric($in['lat'] ?? null) || !is_numeric($in['lng'] ?? null)) {
    json_out(400, ['error' => 'Location not available. Turn on GPS.']);
}
$lat = (float)$in['lat'];
$lng = (float)$in['lng'];
if (abs($lat) > 90 || abs($lng) > 180) json_out(400, ['error' => 'Location is not valid']);
$acc = is_numeric($in['accuracy'] ?? null) ? (float)$in['accuracy'] : null;
if ($acc !== null && $acc > 150) json_out(422, ['error' => 'GPS signal is weak. Move near a window and try again.']);

// ৩. geofence (office set থাকলে)
$s = $pdo->prepare('SELECT office_lat, office_lng, geo_radius_m FROM companies WHERE id = ?');
$s->execute([$cid]);
$co = $s->fetch() ?: [];
$dist = null;
if (($co['office_lat'] ?? null) !== null && ($co['office_lng'] ?? null) !== null) {
    $dist = att_distance_m((float)$co['office_lat'], (float)$co['office_lng'], $lat, $lng);
    if ($dist > (int)$co['geo_radius_m']) {
        json_out(403, ['error' => 'You are ' . $dist . ' m away from the office.', 'distance_m' => $dist]);
    }
}

// ৪. face
$face = $in['face'] ?? null;
if (!is_array($face) || count($face) !== 45) json_out(422, ['error' => 'Face not captured. Try again.']);
$face = array_map(fn($v) => is_numeric($v) ? (float)$v : -1.0, $face);
foreach ($face as $v) {
    if ($v < 0 || $v > 50) json_out(422, ['error' => 'Face data is not valid']);
}

$s = $pdo->prepare('SELECT signature FROM attendance_faces WHERE caller_id = ?');
$s->execute([$callerId]);
$ref = $s->fetchColumn();
$enrolled = false;
$faceDiff = null;
if ($ref === false) {
    $enrolled = true;                       // প্রথমবার: face save হবে
} else {
    $refArr = json_decode((string)$ref, true);
    if (!is_array($refArr) || count($refArr) !== 45) {
        $enrolled = true;                   // আগের data খারাপ: নতুন করে save
    } else {
        $sum = 0.0;
        foreach ($face as $i => $v) $sum += abs($v - (float)$refArr[$i]);
        $faceDiff = $sum / 45;
        if ($faceDiff > ATT_FACE_MAX_DIFF) {
            json_out(403, ['error' => 'Face does not match. Attendance not allowed.', 'face_diff' => round($faceDiff, 4)]);
        }
    }
}

// ৫. action ঠিক করা
$workDate = att_today();
$nowUtc   = gmdate('Y-m-d H:i:s');

// আগের দিনের check-in খোলা থাকলে (রাত পেরিয়ে check-out) সেটাই বন্ধ হবে, নতুন row বানাবে না।
// ২০ ঘণ্টার বেশি পুরনো হলে ধরা হবে না, admin panel থেকে ঠিক করতে হবে।
$s = $pdo->prepare('SELECT id, work_date, check_in, check_out FROM attendance
                    WHERE company_id = ? AND caller_id = ? AND check_in IS NOT NULL AND check_out IS NULL
                      AND work_date < ? AND TIMESTAMPDIFF(HOUR, check_in, UTC_TIMESTAMP()) < 20
                    ORDER BY work_date DESC LIMIT 1');
$s->execute([$cid, $callerId, $workDate]);
$openOld = $s->fetch() ?: null;

if ($openOld) {
    $action = 'out';
    $row    = $openOld;
} else {
    $s = $pdo->prepare('SELECT id, check_in, check_out FROM attendance WHERE company_id = ? AND caller_id = ? AND work_date = ?');
    $s->execute([$cid, $callerId, $workDate]);
    $row = $s->fetch() ?: null;

    if (!$row || !$row['check_in']) {
        $action = 'in';
    } elseif (!$row['check_out']) {
        $action = 'out';
    } else {
        json_out(200, [
            'ok' => true, 'action' => 'done', 'already' => true,
            'message' => 'Today attendance is already complete',
            'check_in_ist' => att_ist_time($row['check_in']), 'check_out_ist' => att_ist_time($row['check_out']),
        ]);
    }
}

$faceRounded = $faceDiff === null ? null : round($faceDiff, 4);

try {
    $pdo->beginTransaction();
    if ($action === 'in') {
        if ($row) {
            $pdo->prepare('UPDATE attendance SET check_in = ?, in_lat = ?, in_lng = ?, in_dist_m = ?, in_face_diff = ? WHERE id = ?')
                ->execute([$nowUtc, $lat, $lng, $dist, $faceRounded, $row['id']]);
        } else {
            $pdo->prepare('INSERT INTO attendance (company_id, caller_id, work_date, check_in, in_lat, in_lng, in_dist_m, in_face_diff)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?)')
                ->execute([$cid, $callerId, $workDate, $nowUtc, $lat, $lng, $dist, $faceRounded]);
        }
    } else {
        $pdo->prepare('UPDATE attendance SET check_out = ?, out_lat = ?, out_lng = ?, out_dist_m = ?, out_face_diff = ? WHERE id = ?')
            ->execute([$nowUtc, $lat, $lng, $dist, $faceRounded, $row['id']]);
    }
    if ($enrolled) {
        $pdo->prepare('INSERT INTO attendance_faces (caller_id, company_id, signature, enrolled_at) VALUES (?, ?, ?, ?)
                       ON DUPLICATE KEY UPDATE signature = VALUES(signature), enrolled_at = VALUES(enrolled_at)')
            ->execute([$callerId, $cid, json_encode($face), $nowUtc]);
    }
    $pdo->commit();
} catch (Throwable $e) {
    $pdo->rollBack();
    error_log('attendance_submit: ' . $e->getMessage());
    json_out(500, ['error' => 'Server error']);
}

$s = $pdo->prepare('SELECT check_in, check_out FROM attendance WHERE company_id = ? AND caller_id = ? AND work_date = ?');
$s->execute([$cid, $callerId, $workDate]);
$now = $s->fetch();

json_out(200, [
    'ok'           => true,
    'action'       => $action,
    'message'      => 'Welcome to Dreandesk',
    'time_ist'     => att_ist_time($nowUtc),
    'check_in_ist' => att_ist_time($now['check_in'] ?? null),
    'check_out_ist'=> att_ist_time($now['check_out'] ?? null),
    'enrolled'     => $enrolled,
    'face_diff'    => $faceRounded,
    'distance_m'   => $dist,
]);
