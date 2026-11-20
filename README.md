# CallSync

Ek repo-te duita part:

- `android/`: employee Android app (call sync, recordings, attendance: QR + face + GPS). Build: GitHub Actions → **Build APK** → artifact `CallSync-apk`.
- `backend/`: PHP 8 + MySQL server (`https://linkmy.in/backend/`) and live UI (`backend/ui/`).

## Backend setup
1. `backend/config.example.php` copy kore server-e `config.php` banan, database details bhorun. `config.php` kokhono commit korben na.
2. Database: `schema.sql` (new install) ba `migrate_v4_to_v5.sql` (purono install), tarpor `attendance_migration.sql` ebong `attendance_migration_v2.sql` ekbar kore.
3. `backend/` er PHP files server-e upload korun. `backend/ui/index.html` ebong `version.txt` `backend/ui/` e upload korun.
4. APK `backend/ui/apk/CallSync-apk/CallSync.apk` e, `version.json` `backend/ui/apk/` e rakhun (git-e thakbe na).

## Secrets
Database password, `config.php`, APK ebong `recordings/` repo-te push korben na (`.gitignore` ei niyom dhore).

## APK build (GitHub Actions)
- Workflow ফাইল: [.github/workflows/build.yml](.github/workflows/build.yml)
- Workflow ফোল্ডার: [.github/workflows](.github/workflows)
- Build চালাতে: repo-তে **Actions** → **Build APK** → **Run workflow**।
