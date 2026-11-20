-- =====================================================================
--  CallSync v5 — FULL & FINAL schema (multi-company)
--  phpMyAdmin → আপনার database → SQL ট্যাবে পুরোটা paste করে Go চাপুন।
--
--  ধারণা:
--    users      = company account (আপনার আগের users টেবিল, একদম অপরিবর্তিত)
--    companies  = প্রতিটি users row-এর জন্য ১টা company + ইউনিক invite code
--    callers    = company-র অধীনে কর্মী/ফোন (invite code দিয়ে নিজে register হয়)
--    call_logs  = সব call (SIM, ring time, note, recording সহ)
--
--  নতুন users row insert হলেই trigger নিজে company + invite code বানায়।
--  (trigger না চললেও PHP নিজে বানিয়ে নেয় — lib.php → ensure_companies())
--
--  সব সময় UTC-তে রাখা হয়। Report view গুলো IST (+05:30) ধরে দিন ভাগ করে।
-- =====================================================================

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- 1) users  (company account) — আগে থেকে থাকলে কিছুই বদলাবে না
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `users` (
  `id`            INT          NOT NULL AUTO_INCREMENT,
  `name`          VARCHAR(255) NULL,
  `email`         VARCHAR(255) NULL,
  `gender`        VARCHAR(20)  NULL,
  `balance`       DECIMAL(12,2) NULL DEFAULT 0.00,
  `mobileNo`      VARCHAR(20)  NULL,
  `city`          VARCHAR(100) NULL,
  `password`      VARCHAR(255) NULL,
  `refername`     VARCHAR(100) NULL,
  `img_name`      VARCHAR(255) NULL,
  `datetime`      VARCHAR(50)  NULL,
  `user_status`   VARCHAR(20)  NULL,
  `pay_status`    VARCHAR(20)  NULL,
  `username`      VARCHAR(100) NULL,
  `user_UID`      VARCHAR(100) NULL,
  `total_Visitor` INT          NULL DEFAULT 0,
  `total_Counter` INT          NULL DEFAULT 0,
  `verify_status` VARCHAR(20)  NULL,
  `update_date`   VARCHAR(50)  NULL,
  `DOB`           VARCHAR(20)  NULL,
  `MotherName`    VARCHAR(255) NULL,
  `FatherName`    VARCHAR(255) NULL,
  `AddresS`       TEXT         NULL,
  `PANCard`       VARCHAR(20)  NULL,
  `BankIFSC`      VARCHAR(20)  NULL,
  `PinCode`       VARCHAR(10)  NULL,
  `MonthlyIncome` VARCHAR(50)  NULL,
  `LoanAmount`    VARCHAR(50)  NULL,
  `Aadhaar`       VARCHAR(20)  NULL,
  `BankNumber`    VARCHAR(30)  NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- 2) companies  — ১ user = ১ company, প্রত্যেকের নিজের invite code
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `companies` (
  `id`           INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`      INT          NULL,                 -- users.id (owner)
  `name`         VARCHAR(191) NOT NULL,
  `invite_code`  VARCHAR(16)  NOT NULL,             -- caller এটা দিয়ে join করে (যেমন K7P2QX9M)
  `api_key`      CHAR(64)     NULL,                 -- company_report.php / play.php এর জন্য (PHP বানায়)
  `status`       ENUM('active','blocked') NOT NULL DEFAULT 'active',
  `max_callers`  INT UNSIGNED NOT NULL DEFAULT 0,   -- 0 = যত খুশি caller
  `record_calls` TINYINT(1)   NOT NULL DEFAULT 1,   -- 0 হলে recording upload বন্ধ
  `created_at`   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_company_invite` (`invite_code`),
  UNIQUE KEY `uq_company_user`   (`user_id`),
  UNIQUE KEY `uq_company_api`    (`api_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- 3) callers  — company-র অধীনে প্রতিটি কর্মী/ফোন
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `callers` (
  `id`                INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `company_id`        INT UNSIGNED NOT NULL,
  `employee_id`       VARCHAR(64)  NOT NULL,
  `name`              VARCHAR(128) NOT NULL,
  `phone`             VARCHAR(20)  NULL,
  `token_hash`        CHAR(64)     NOT NULL,
  `status`            ENUM('active','blocked') NOT NULL DEFAULT 'active',
  `sim_mode`          TINYINT UNSIGNED NOT NULL DEFAULT 0,   -- 0 = SIM 1, 1 = SIM 2, 2 = দুটোই
  `sim1_name`         VARCHAR(64)  NULL,
  `sim2_name`         VARCHAR(64)  NULL,
  `device_model`      VARCHAR(100) NULL,
  `android_version`   VARCHAR(20)  NULL,
  `app_version`       VARCHAR(30)  NULL,
  `battery_pct`       TINYINT UNSIGNED NULL,
  `is_charging`       TINYINT(1)   NULL,
  `network_type`      VARCHAR(20)  NULL,                     -- wifi / 5G / 4G / 3G / 2G / mobile / none
  `perm_call_log`     TINYINT(1)   NULL,
  `perm_contacts`     TINYINT(1)   NULL,
  `perm_recording`    TINYINT(1)   NULL,
  `recording_enabled` TINYINT(1)   NOT NULL DEFAULT 0,
  `last_sync_at`      DATETIME     NULL,                     -- শেষ সফল sync (UTC)
  `last_call_at`      DATETIME     NULL,
  `registered_at`     DATETIME     NULL,
  `created_at`        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_company_employee` (`company_id`, `employee_id`),
  KEY `idx_token` (`token_hash`),
  CONSTRAINT `fk_callers_company` FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- 4) call_logs
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `call_logs` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `company_id`     INT UNSIGNED NOT NULL,
  `caller_id`      INT UNSIGNED NOT NULL,
  `device_call_id` VARCHAR(64)  NOT NULL,
  `contact_number` VARCHAR(32)  NOT NULL,
  `number_key`     VARCHAR(16)  NOT NULL DEFAULT '',          -- নম্বরের শেষ ১০ digit (match করার জন্য)
  `contact_name`   VARCHAR(128) NULL,
  `contact_saved`  TINYINT(1)   NULL,                          -- ফোনে contact save করা ছিল কি না
  `call_type`      ENUM('incoming','outgoing','missed','rejected') NOT NULL,
  `duration_sec`   INT UNSIGNED NOT NULL DEFAULT 0,            -- কথা বলার সময়
  `ring_sec`       INT UNSIGNED NULL,                          -- কতক্ষণ ring হয়েছে
  `called_at`      DATETIME     NOT NULL,                      -- call শুরু (UTC)
  `ended_at`       DATETIME     NULL,                          -- call শেষ (UTC)
  `sim_slot`       TINYINT UNSIGNED NULL,                      -- 0 = SIM 1, 1 = SIM 2
  `sim_name`       VARCHAR(64)  NULL,                          -- Jio / Airtel …
  `note`           VARCHAR(1000) NULL,
  `note_at`        DATETIME     NULL,
  `recording_file` VARCHAR(255) NULL,                          -- recordings/ ফোল্ডারের ভেতরের path
  `recording_size` INT UNSIGNED NULL,
  `recording_at`   DATETIME     NULL,
  `created_at`     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_caller_call` (`caller_id`, `device_call_id`),
  KEY `idx_caller_time`   (`caller_id`, `called_at`),
  KEY `idx_company_time`  (`company_id`, `called_at`),
  KEY `idx_caller_number` (`caller_id`, `number_key`, `called_at`),
  CONSTRAINT `fk_logs_caller`  FOREIGN KEY (`caller_id`)  REFERENCES `callers`(`id`)   ON DELETE CASCADE,
  CONSTRAINT `fk_logs_company` FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- 5) device_heartbeats — battery / network ইতিহাস (প্রতি caller ৩০ মিনিটে ১টা)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `device_heartbeats` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `caller_id`    INT UNSIGNED NOT NULL,
  `battery_pct`  TINYINT UNSIGNED NULL,
  `is_charging`  TINYINT(1)  NULL,
  `network_type` VARCHAR(20) NULL,
  `created_at`   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_hb_caller_time` (`caller_id`, `created_at`),
  CONSTRAINT `fk_hb_caller` FOREIGN KEY (`caller_id`) REFERENCES `callers`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- 6) Trigger: নতুন user (company) তৈরি হলেই ইউনিক invite code সহ company row
-- ---------------------------------------------------------------------
-- No trigger: PHP (Register.php / lib.php) creates the company + invite code.
-- (A trigger caused "Illegal mix of collations" on MariaDB 11.)
DROP TRIGGER IF EXISTS `trg_users_company`;

-- আগে থেকে থাকা users-দের জন্যও company + code বানিয়ে দাও
INSERT INTO `companies` (`user_id`, `name`, `invite_code`)
SELECT u.`id`,
       COALESCE(NULLIF(TRIM(u.`name`), ''), NULLIF(TRIM(u.`username`), ''), CONCAT('Company ', u.`id`)),
       UPPER(SUBSTRING(SHA2(CONCAT(u.`id`, '-', UUID(), '-', RAND()), 256), 1, 8))
FROM `users` u
LEFT JOIN `companies` c ON c.`user_id` = u.`id`
WHERE c.`id` IS NULL;

-- ---------------------------------------------------------------------
-- 7) Report views (IST দিন ধরে)
-- ---------------------------------------------------------------------
-- প্রতিটি call + হিসাব করা কলাম
CREATE OR REPLACE VIEW `v_call_logs` AS
SELECT
  cl.*,
  cr.`name`        AS caller_name,
  cr.`employee_id` AS employee_id,
  co.`name`        AS company_name,
  (cl.`called_at` + INTERVAL 330 MINUTE)          AS called_at_ist,
  DATE(cl.`called_at` + INTERVAL 330 MINUTE)      AS call_date_ist,
  (cl.`call_type` IN ('incoming','outgoing') AND cl.`duration_sec` > 0) AS is_connected,
  (cl.`call_type` = 'outgoing' AND cl.`duration_sec` = 0)               AS not_picked_by_client,
  -- Never Attended: missed/rejected, পরে আর কখনো ওই নম্বরের সাথে কথা হয়নি
  (cl.`call_type` IN ('missed','rejected') AND NOT EXISTS (
      SELECT 1 FROM `call_logs` x
      WHERE x.`caller_id` = cl.`caller_id` AND x.`number_key` = cl.`number_key`
        AND x.`called_at` > cl.`called_at` AND x.`duration_sec` > 0
        AND x.`call_type` IN ('incoming','outgoing'))) AS never_attended,
  -- Attempts After Missed: missed এর পর কথা হওয়া পর্যন্ত কতবার outgoing call করা হয়েছে
  CASE WHEN cl.`call_type` IN ('missed','rejected') THEN (
      SELECT COUNT(*) FROM `call_logs` x
      WHERE x.`caller_id` = cl.`caller_id` AND x.`number_key` = cl.`number_key`
        AND x.`call_type` = 'outgoing' AND x.`called_at` > cl.`called_at`
        AND x.`called_at` <= COALESCE((
            SELECT MIN(y.`called_at`) FROM `call_logs` y
            WHERE y.`caller_id` = cl.`caller_id` AND y.`number_key` = cl.`number_key`
              AND y.`called_at` > cl.`called_at` AND y.`duration_sec` > 0
              AND y.`call_type` IN ('incoming','outgoing')), '9999-12-31'))
  END AS attempts_after_missed
FROM `call_logs` cl
JOIN `callers`   cr ON cr.`id` = cl.`caller_id`
JOIN `companies` co ON co.`id` = cl.`company_id`;

-- প্রতিদিন প্রতি caller এর হিসাব (Callyzer এর মতো)
CREATE OR REPLACE VIEW `v_caller_daily` AS
SELECT
  `company_id`, `caller_id`, `caller_name`, `employee_id`, `call_date_ist`,
  COUNT(*)                                                     AS total_calls,
  SUM(`duration_sec`)                                          AS total_duration,
  SUM(`is_connected`)                                          AS connected_calls,
  SUM(IF(`is_connected`, `duration_sec`, 0))                   AS connected_duration,
  ROUND(SUM(IF(`is_connected`, `duration_sec`, 0)) / NULLIF(SUM(`is_connected`), 0)) AS connected_avg_duration,
  SUM(`call_type` = 'incoming')                                AS incoming,
  SUM(`call_type` = 'outgoing')                                AS outgoing,
  SUM(`call_type` = 'missed')                                  AS missed,
  SUM(`call_type` = 'rejected')                                AS rejected,
  SUM(`never_attended`)                                        AS never_attended,
  SUM(`not_picked_by_client`)                                  AS not_picked_by_client,
  COUNT(DISTINCT `number_key`)                                 AS unique_clients,
  ROUND(AVG(`ring_sec`))                                       AS avg_ring_sec
FROM `v_call_logs`
GROUP BY `company_id`, `caller_id`, `caller_name`, `employee_id`, `call_date_ist`;

-- প্রতি caller এর বর্তমান অবস্থা (Last Sync Time, battery, network)
CREATE OR REPLACE VIEW `v_callers` AS
SELECT
  cr.`id` AS caller_id, cr.`company_id`, co.`name` AS company_name, co.`invite_code`,
  cr.`employee_id`, cr.`name`, cr.`phone`, cr.`status`, cr.`sim_mode`, cr.`sim1_name`, cr.`sim2_name`,
  cr.`device_model`, cr.`android_version`, cr.`app_version`,
  cr.`battery_pct`, cr.`is_charging`, cr.`network_type`,
  cr.`perm_call_log`, cr.`perm_contacts`, cr.`perm_recording`, cr.`recording_enabled`,
  (cr.`last_sync_at` + INTERVAL 330 MINUTE) AS last_sync_ist,
  (cr.`last_call_at` + INTERVAL 330 MINUTE) AS last_call_ist,
  (cr.`last_sync_at` IS NOT NULL AND cr.`last_sync_at` > UTC_TIMESTAMP() - INTERVAL 20 MINUTE) AS is_online
FROM `callers` cr
JOIN `companies` co ON co.`id` = cr.`company_id`;

-- =====================================================================
-- দরকারি query:
--   company-র invite code দেখা:   SELECT invite_code FROM companies WHERE user_id = 5;
--   নতুন code দেওয়া:             UPDATE companies SET invite_code = 'NEWCODE1' WHERE user_id = 5;
--   caller block করা:             UPDATE callers SET status = 'blocked' WHERE id = 12;
--   আজকের report:                 SELECT * FROM v_caller_daily WHERE company_id = 1 AND call_date_ist = CURDATE();
-- =====================================================================
