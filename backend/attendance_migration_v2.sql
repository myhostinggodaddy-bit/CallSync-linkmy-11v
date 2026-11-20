-- Attendance v2 (দৈনিক QR + face check). আগে attendance_migration.sql একবার চালানো থাকতে হবে।
-- এটা একবারই চালান।

CREATE TABLE IF NOT EXISTS attendance_qr_daily (
  id          INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  company_id  INT UNSIGNED NOT NULL,
  qr_date     DATE NOT NULL,                 -- IST তারিখ
  token       CHAR(32) NOT NULL,
  created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_qr_day (company_id, qr_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS attendance_faces (
  caller_id    INT UNSIGNED PRIMARY KEY,
  company_id   INT UNSIGNED NOT NULL,
  signature    TEXT NOT NULL,               -- 45টি normalised face distance (JSON)
  enrolled_at  DATETIME NOT NULL             -- UTC
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE attendance
  ADD COLUMN in_face_diff  DECIMAL(6,4) NULL,
  ADD COLUMN out_face_diff DECIMAL(6,4) NULL;
