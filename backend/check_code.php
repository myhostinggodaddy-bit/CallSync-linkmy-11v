<?php
// App: "Verify" button on the first screen. Returns the company name for an invite code.
require_once __DIR__ . '/config.php';

$in = read_json();
try {
    $co = find_company_by_code((string)($in['invite_code'] ?? ''));
} catch (Throwable $e) {
    error_log($e->getMessage());
    json_out(500, ['error' => 'Server error']);
}
if (!$co) {
    usleep(700000); // slow down guessing
    json_out(404, ['error' => 'Invalid invite code']);
}
if ($co['status'] !== 'active') json_out(403, ['error' => 'Company is blocked']);

json_out(200, [
    'company_name' => $co['name'],
    'record_calls' => (bool)$co['record_calls'],
]);
