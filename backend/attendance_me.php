<?php
// App: এই phone-এর নিজের attendance (day / week / month). X-Api-Token লাগে।
require_once __DIR__ . '/config.php';

$in     = read_json();
$caller = auth_caller();
$period = (string)($in['period'] ?? 'week');
$days   = ['day' => 1, 'week' => 7, 'month' => 30][$period] ?? 7;
$period = isset(['day' => 1, 'week' => 1, 'month' => 1][$period]) ? $period : 'week';

$today = att_today();
$from  = (new DateTime($today, new DateTimeZone('Asia/Kolkata')))->modify('-' . ($days - 1) . ' days')->format('Y-m-d');

$s = db()->prepare(
    'SELECT work_date, check_in, check_out FROM attendance
      WHERE caller_id = ? AND work_date BETWEEN ? AND ? ORDER BY work_date DESC'
);
$s->execute([(int)$caller['id'], $from, $today]);

$rows = [];
$total = 0;
$present = 0;
foreach ($s->fetchAll() as $r) {
    $mins = null;
    if ($r['check_in'] && $r['check_out']) {
        $mins = (int)round((strtotime($r['check_out'] . ' UTC') - strtotime($r['check_in'] . ' UTC')) / 60);
        $total += $mins;
    }
    if ($r['check_in']) $present++;
    $rows[] = [
        'date'    => $r['work_date'],
        'in'      => att_ist_time($r['check_in']),
        'out'     => att_ist_time($r['check_out']),
        'minutes' => $mins,
    ];
}

json_out(200, [
    'ok' => true, 'period' => $period, 'from' => $from, 'to' => $today,
    'present' => $present, 'total_minutes' => $total, 'rows' => $rows,
]);
