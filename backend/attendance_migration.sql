-- Attendance module: একবার চালান (দুই বার চালালে ALTER error দেবে, তখন শুধু CREATE অংশ চালান)

ALTER TABLE companies
  ADD COLUMN attendance_qr VARCHAR(64) NULL,
  ADD COLUMN office_lat    DECIMAL(10,7) NULL,
  ADD COLUMN office_lng    DECIMAL(10,7) NULL,
  ADD COLUMN geo_radius_m  INT NOT NULL DEFAULT 150;

CREATE TABLE IF NOT EXISTS attendance (
  id          INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  company_id  INT NOT NULL,
  caller_id   INT NOT NULL,
  work_date   DATE NOT NULL,              -- IST তারিখ
  check_in    DATETIME NULL,              -- UTC
  check_out   DATETIME NULL,              -- UTC
  in_lat      DECIMAL(10,7) NULL,
  in_lng      DECIMAL(10,7) NULL,
  in_dist_m   INT NULL,
  out_lat     DECIMAL(10,7) NULL,
  out_lng     DECIMAL(10,7) NULL,
  out_dist_m  INT NULL,
  created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_company_caller_day (company_id, caller_id, work_date),
  KEY idx_company_date (company_id, work_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
