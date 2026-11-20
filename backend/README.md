# CallSync backend (PHP 8 + MySQL)

Server folder for `https://linkmy.in/backend/`.

## Setup
1. Copy `config.example.php` to `config.php` on the server and fill in the database details. Do **not** commit `config.php`.
2. Import `schema.sql` (new install), or `migrate_v4_to_v5.sql` for old installs.
3. Attendance: run `attendance_migration.sql`, then `attendance_migration_v2.sql` (once each).
4. Upload `ui/index.html` and `ui/version.txt` (live UI). APK goes to `ui/apk/`, outside git.

## Main endpoints
- `check_code.php`, `register.php`, `sync.php`, `note.php`, `upload_recording.php`: app.
- `attendance_submit.php`, `attendance_me.php`: attendance from the app.
- `attendance_qr.php?key=...`: today's attendance QR for the admin panel.
- `company_report.php`, `play.php`: website/report (company key).
