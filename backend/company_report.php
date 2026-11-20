<?php
/*
 * Company report (JSON) — for your own website / admin panel.
 *
 *   company_report.php?key=<api_key>&from=2026-11-01&to=2026-11-13
 *       → every caller with Total Call, Total Duration, Connected Calls, Conn. Calls Duration,
 *         Conn. Calls Avg. Duration, Missed, Rejected, Never Attended, Not Pickup by Client,
 *         Last Sync Time, battery, network …
 *   …&caller=12             → also that caller's call list (Call Type, Call Time, Ringing Time,
 *                             Call Note, Attempt Summary, Attempts After Missed, recording link)
 *
 * Get the api_key with company_info_for_user($userId) (lib.php). Dates are IST days.
 */
require_once __DIR__ . '/config.php';

$co  = auth_company_key();
$pdo = db();
$cid = (int)$co['id'];

$today = gmdate('Y-m-d', time() + TZ_OFFSET_MIN * 60);
$from  = preg_match('/^\d{4}-\d{2}-\d{2}$/', $_GET['from'] ?? '') ? $_GET['from'] : $today;
$to    = preg_match('/^\d{4}-\d{2}-\d{2}$/', $_GET['to'] ?? '') ? $_GET['to'] : $from;
if ($to < $from) [$from, $to] = [$to, $from];
if ((strtotime($to) - strtotime($from)) / 86400 > 92) json_out(400, ['error' => 'Max 92 days']);

$off = TZ_OFFSET_MIN * 60;
$fromUtc = gmdate('Y-m-d H:i:s', strtotime($from . ' 00:00:00 UTC') - $off);
$toUtc   = gmdate('Y-m-d H:i:s', strtotime($to . ' 00:00:00 UTC') + 86400 - $off);
$onlyCaller = isset($_GET['caller']) ? (int)$_GET['caller'] : 0;

// Rows from the start of the range until now: the later rows are needed to know whether a
// missed call was ever returned ("Never Attended") and how many attempts it took.
$sql = 'SELECT id, caller_id, device_call_id, contact_number, number_key, contact_name, call_type, duration_sec,
               ring_sec, called_at, ended_at, sim_slot, sim_name, note, recording_file
        FROM call_logs WHERE company_id = ? AND called_at >= ?' . ($onlyCaller ? ' AND caller_id = ?' : '') .
       ' ORDER BY called_at ASC, id ASC';
$s = $pdo->prepare($sql);
$s->execute($onlyCaller ? [$cid, $fromUtc, $onlyCaller] : [$cid, $fromUtc]);
$rows = $s->fetchAll();

function is_conn(array $r): bool {
    return ($r['call_type'] === 'incoming' || $r['call_type'] === 'outgoing') && (int)$r['duration_sec'] > 0;
}

// ---- per number analysis ----
$groups = [];
foreach ($rows as $i => $r) $groups[$r['caller_id'] . '|' . ($r['number_key'] ?: $r['contact_number'])][] = $i;
foreach ($groups as $idx) {
    $sum = ['out' => 0, 'out_conn' => 0, 'not_picked' => 0, 'in' => 0, 'in_conn' => 0, 'missed' => 0, 'rejected' => 0];
    $n = count($idx);
    for ($k = 0; $k < $n; $k++) {
        $r = &$rows[$idx[$k]];
        $t = $r['call_type'];
        if ($t === 'outgoing') { $sum['out']++; is_conn($r) ? $sum['out_conn']++ : $sum['not_picked']++; }
        elseif ($t === 'incoming') { $sum['in']++; if (is_conn($r)) $sum['in_conn']++; }
        else $sum[$t]++;

        $r['never_attended'] = false;
        $r['attempts_after_missed'] = null;
        $r['callback_after_sec'] = null;
        if ($t === 'missed' || $t === 'rejected') {
            $att = 0; $back = null;
            for ($j = $k + 1; $j < $n; $j++) {
                $x = $rows[$idx[$j]];
                if ($x['call_type'] === 'outgoing') $att++;
                if (is_conn($x)) { $back = $x; break; }
            }
            $r['attempts_after_missed'] = $att;
            $r['never_attended'] = $back === null;
            if ($back) $r['callback_after_sec'] = strtotime($back['called_at'] . ' UTC') - strtotime($r['called_at'] . ' UTC');
        }
        unset($r);
    }
    $txt = [];
    if ($sum['out']) $txt[] = "Out {$sum['out']} ({$sum['out_conn']} connected, {$sum['not_picked']} not picked)";
    if ($sum['in'] + $sum['missed'] + $sum['rejected']) {
        $txt[] = 'In ' . ($sum['in'] + $sum['missed'] + $sum['rejected']) . " ({$sum['in_conn']} connected, {$sum['missed']} missed, {$sum['rejected']} rejected)";
    }
    foreach ($idx as $i) { $rows[$i]['attempt_summary'] = implode(' · ', $txt); }
}

// ---- per caller totals (only rows inside the range) ----
$blank = ['total_calls' => 0, 'total_duration' => 0, 'connected_calls' => 0, 'connected_duration' => 0,
          'connected_avg_duration' => 0, 'incoming' => 0, 'outgoing' => 0, 'missed' => 0, 'rejected' => 0,
          'never_attended' => 0, 'not_picked_by_client' => 0, 'unique_clients' => 0, 'avg_ring_sec' => null];
$tot = []; $uniq = []; $ring = [];
$list = [];
foreach ($rows as $r) {
    if ($r['called_at'] >= $toUtc) continue;
    $c = (int)$r['caller_id'];
    $m = &$tot[$c];
    if ($m === null) $m = $blank;
    $m['total_calls']++;
    $m['total_duration'] += (int)$r['duration_sec'];
    if (is_conn($r)) { $m['connected_calls']++; $m['connected_duration'] += (int)$r['duration_sec']; }
    $m[$r['call_type']]++;
    if ($r['never_attended']) $m['never_attended']++;
    if ($r['call_type'] === 'outgoing' && (int)$r['duration_sec'] === 0) $m['not_picked_by_client']++;
    $uniq[$c][$r['number_key'] ?: $r['contact_number']] = 1;
    if ($r['ring_sec'] !== null) $ring[$c][] = (int)$r['ring_sec'];
    unset($m);

    if ($onlyCaller) {
        $list[] = [
            'id'                    => (int)$r['id'],
            'call_type'             => $r['call_type'],
            'contact_number'        => $r['contact_number'],
            'contact_name'          => $r['contact_name'],
            'call_time_ist'         => gmdate('Y-m-d H:i:s', strtotime($r['called_at'] . ' UTC') + $off),
            'duration_sec'          => (int)$r['duration_sec'],
            'ring_sec'              => $r['ring_sec'] === null ? null : (int)$r['ring_sec'],
            'sim'                   => $r['sim_slot'] === null ? null : ('SIM ' . ((int)$r['sim_slot'] + 1) . ($r['sim_name'] ? ' · ' . $r['sim_name'] : '')),
            'note'                  => $r['note'],
            'attempt_summary'       => $r['attempt_summary'],
            'attempts_after_missed' => $r['attempts_after_missed'],
            'never_attended'        => $r['never_attended'],
            'callback_after_sec'    => $r['callback_after_sec'],
            'recording_url'         => $r['recording_file'] ? 'play.php?key=' . $co['api_key'] . '&id=' . (int)$r['id'] : null,
        ];
    }
}

// ---- callers ----
$s = $pdo->prepare('SELECT * FROM callers WHERE company_id = ?' . ($onlyCaller ? ' AND id = ?' : '') . ' ORDER BY name');
$s->execute($onlyCaller ? [$cid, $onlyCaller] : [$cid]);
$ist = function ($utc) use ($off) { return $utc ? gmdate('Y-m-d H:i:s', strtotime($utc . ' UTC') + $off) : null; };
$callers = [];
foreach ($s->fetchAll() as $c) {
    $id = (int)$c['id'];
    $m = $tot[$id] ?? $blank;
    $m['connected_avg_duration'] = $m['connected_calls'] ? (int)round($m['connected_duration'] / $m['connected_calls']) : 0;
    $m['unique_clients'] = isset($uniq[$id]) ? count($uniq[$id]) : 0;
    $m['avg_ring_sec'] = !empty($ring[$id]) ? (int)round(array_sum($ring[$id]) / count($ring[$id])) : null;
    $callers[] = [
        'caller_id'      => $id,
        'employee_id'    => $c['employee_id'],
        'name'           => $c['name'],
        'phone'          => $c['phone'],
        'status'         => $c['status'],
        'sim_mode'       => ['SIM 1', 'SIM 2', 'Both SIMs'][(int)$c['sim_mode']] ?? 'SIM 1',
        'device'         => trim(($c['device_model'] ?? '') . ' · Android ' . ($c['android_version'] ?? '?'), ' ·'),
        'app_version'    => $c['app_version'],
        'battery_pct'    => $c['battery_pct'] === null ? null : (int)$c['battery_pct'],
        'is_charging'    => $c['is_charging'] === null ? null : (bool)$c['is_charging'],
        'network_type'   => $c['network_type'],
        'recording'      => (bool)$c['recording_enabled'],
        'last_sync_ist'  => $ist($c['last_sync_at']),
        'is_online'      => $c['last_sync_at'] && strtotime($c['last_sync_at'] . ' UTC') > time() - 1200,
        'stats'          => $m,
    ];
}

$out = [
    'company'     => ['id' => $cid, 'name' => $co['name'], 'invite_code' => $co['invite_code']],
    'from'        => $from,
    'to'          => $to,
    'callers'     => $callers,
];
if ($onlyCaller) $out['calls'] = array_reverse($list);
json_out(200, $out);
