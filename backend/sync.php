<?php
// App: uploads new calls + phone status (battery, network, SIM) every 5 minutes.
require_once __DIR__ . '/config.php';

$in     = read_json();
$caller = auth_caller();
$pdo    = db();
$callerId  = (int)$caller['id'];
$companyId = (int)$caller['company_id'];

$logs = $in['logs'] ?? [];
if (!is_array($logs) || count($logs) > 500) json_out(400, ['error' => 'logs must be an array (max 500)']);

$types = ['incoming', 'outgoing', 'missed', 'rejected'];
$ins = $pdo->prepare(
    'INSERT INTO call_logs (company_id, caller_id, device_call_id, contact_number, number_key, contact_name, contact_saved,
                            call_type, duration_sec, ring_sec, called_at, ended_at, sim_slot, sim_name, note, note_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, IF(? IS NULL, NULL, UTC_TIMESTAMP()))
     ON DUPLICATE KEY UPDATE
       contact_number = VALUES(contact_number), number_key = VALUES(number_key),
       contact_name = COALESCE(VALUES(contact_name), contact_name),
       contact_saved = COALESCE(VALUES(contact_saved), contact_saved),
       call_type = VALUES(call_type), duration_sec = VALUES(duration_sec),
       ring_sec = COALESCE(VALUES(ring_sec), ring_sec),
       called_at = VALUES(called_at), ended_at = COALESCE(VALUES(ended_at), ended_at),
       sim_slot = COALESCE(VALUES(sim_slot), sim_slot), sim_name = COALESCE(VALUES(sim_name), sim_name),
       note = COALESCE(VALUES(note), note), note_at = COALESCE(VALUES(note_at), note_at)'
);

$inserted = 0; $updated = 0; $newest = null;
$pdo->beginTransaction();
try {
    foreach ($logs as $l) {
        if (!is_array($l)) throw new InvalidArgumentException('bad row');
        $id   = (string)($l['device_call_id'] ?? '');
        $num  = (string)($l['contact_number'] ?? '');
        $nm   = isset($l['contact_name']) && $l['contact_name'] !== '' ? mb_substr((string)$l['contact_name'], 0, 128) : null;
        $type = (string)($l['call_type'] ?? '');
        $dur  = $l['duration_sec'] ?? null;
        $at   = (string)($l['called_at'] ?? '');

        if ($id === '' || strlen($id) > 64 || $num === '' || strlen($num) > 32
            || !in_array($type, $types, true) || !is_int($dur) || $dur < 0 || !valid_utc($at)) {
            throw new InvalidArgumentException('bad row');
        }
        // optional v5 fields (older apps simply do not send them)
        $ring  = (isset($l['ring_sec']) && is_int($l['ring_sec']) && $l['ring_sec'] >= 0) ? min($l['ring_sec'], 3600) : null;
        $end   = valid_utc($l['ended_at'] ?? null) ? $l['ended_at'] : null;
        $slot  = (isset($l['sim_slot']) && in_array($l['sim_slot'], [0, 1], true)) ? $l['sim_slot'] : null;
        $simNm = isset($l['sim_name']) && $l['sim_name'] !== '' ? mb_substr((string)$l['sim_name'], 0, 64) : null;
        $saved = isset($l['contact_saved']) && is_bool($l['contact_saved']) ? (int)$l['contact_saved'] : null;
        $note  = isset($l['note']) && trim((string)$l['note']) !== '' ? mb_substr(trim((string)$l['note']), 0, 1000) : null;

        $ins->execute([$companyId, $callerId, $id, $num, number_key($num), $nm, $saved,
                       $type, $dur, $ring, $at, $end, $slot, $simNm, $note, $note]);
        $rc = $ins->rowCount(); // 1 = inserted, 2 = updated, 0 = unchanged
        if ($rc === 1) $inserted++; elseif ($rc === 2) $updated++;
        if ($newest === null || $at > $newest) $newest = $at;
    }

    // ---- phone status ----
    $d = is_array($in['device'] ?? null) ? $in['device'] : [];
    $bat  = isset($d['battery']) && is_int($d['battery']) ? max(0, min(100, $d['battery'])) : null;
    $chg  = isset($d['charging']) && is_bool($d['charging']) ? (int)$d['charging'] : null;
    $net  = isset($d['network']) ? mb_substr((string)$d['network'], 0, 20) : null;
    $bool = function ($k) use ($d) { return isset($d[$k]) && is_bool($d[$k]) ? (int)$d[$k] : null; };
    $str  = function ($k, $n) use ($d) { return isset($d[$k]) && $d[$k] !== '' ? mb_substr((string)$d[$k], 0, $n) : null; };

    $pdo->prepare(
        'UPDATE callers SET last_sync_at = UTC_TIMESTAMP(),
           last_call_at = IF(? IS NULL, last_call_at, GREATEST(COALESCE(last_call_at, ?), ?)),
           battery_pct = COALESCE(?, battery_pct), is_charging = COALESCE(?, is_charging),
           network_type = COALESCE(?, network_type),
           sim1_name = COALESCE(?, sim1_name), sim2_name = COALESCE(?, sim2_name),
           device_model = COALESCE(?, device_model), android_version = COALESCE(?, android_version),
           app_version = COALESCE(?, app_version),
           perm_call_log = COALESCE(?, perm_call_log), perm_contacts = COALESCE(?, perm_contacts),
           perm_recording = COALESCE(?, perm_recording), recording_enabled = COALESCE(?, recording_enabled)
         WHERE id = ?'
    )->execute([
        $newest, $newest, $newest,
        $bat, $chg, $net, $str('sim1', 64), $str('sim2', 64), $str('model', 100), $str('android', 20), $str('app', 30),
        $bool('perm_call_log'), $bool('perm_contacts'), $bool('perm_recording'), $bool('rec_enabled'), $callerId,
    ]);

    // battery / network history: at most one row per 30 minutes
    if ($bat !== null || $net !== null) {
        $s = $pdo->prepare('SELECT 1 FROM device_heartbeats WHERE caller_id = ? AND created_at > UTC_TIMESTAMP() - INTERVAL 30 MINUTE LIMIT 1');
        $s->execute([$callerId]);
        if (!$s->fetch()) {
            $pdo->prepare('INSERT INTO device_heartbeats (caller_id, battery_pct, is_charging, network_type) VALUES (?, ?, ?, ?)')
                ->execute([$callerId, $bat, $chg, $net]);
        }
    }
    $pdo->commit();
} catch (InvalidArgumentException $e) {
    $pdo->rollBack();
    json_out(422, ['error' => 'Invalid log row']);
} catch (Throwable $e) {
    $pdo->rollBack();
    error_log($e->getMessage());
    json_out(500, ['error' => 'Server error']);
}

json_out(200, [
    'inserted'     => $inserted,
    'updated'      => $updated,
    'company_name' => $caller['company_name'],
    'record_calls' => (bool)$caller['record_calls'],
]);
