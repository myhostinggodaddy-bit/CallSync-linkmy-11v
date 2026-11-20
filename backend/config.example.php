<?php
// ===== শুধু এই অংশটা পূরণ করুন। আসল পাসওয়ার্ড GitHub এ দেবেন না। =====
const DB_HOST = 'localhost';
const DB_NAME = 'CHANGE_ME';
const DB_USER = 'CHANGE_ME';
const DB_PASS = 'CHANGE_ME';

// Call recording কোথায় রাখা হবে (এই ফোল্ডারে PHP এর write permission লাগবে)
const RECORDINGS_DIR = __DIR__ . '/recordings';
const RECORDING_MAX_MB = 30;

// Report এর দিন ভাগ করার timezone (IST = +330 মিনিট)
const TZ_OFFSET_MIN = 330;

// আর কোনো INVITE_CODE লাগবে না — প্রতিটি company-র নিজের code companies টেবিলে থাকে।

require_once __DIR__ . '/lib.php';
