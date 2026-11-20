-- =====================================================================
--  শুধু তাদের জন্য যাদের database-এ আগের (v4) callers / call_logs টেবিল আছে।
--  নতুন install হলে এই ফাইল লাগবে না — সরাসরি schema.sql চালান।
--
--  ধাপ:  ১) নিচের @old_invite এ পুরনো config.php এর INVITE_CODE বসান
--        ২) এই ফাইল চালান
--        ৩) তারপর schema.sql চালান (trigger + view + বাকি company তৈরি হবে)
--
--  আগের সব caller "Default Company" তে যাবে, পুরনো invite code-ই সেই company-র code থাকবে।
-- =====================================================================

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET @old_invite = 'CHANGE_ME_TO_A_LONG_RANDOM_STRING';

CREATE TABLE IF NOT EXISTS `companies` (
  `id`           INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`      INT          NULL,
  `name`         VARCHAR(191) NOT NULL,
  `invite_code`  VARCHAR(16)  NOT NULL,
  `api_key`      CHAR(64)     NULL,
  `status`       ENUM('active','blocked') NOT NULL DEFAULT 'active',
  `max_callers`  INT UNSIGNED NOT NULL DEFAULT 0,
  `record_calls` TINYINT(1)   NOT NULL DEFAULT 1,
  `created_at`   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_company_invite` (`invite_code`),
  UNIQUE KEY `uq_company_user`   (`user_id`),
  UNIQUE KEY `uq_company_api`    (`api_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `companies` (`user_id`, `name`, `invite_code`)
VALUES (NULL, 'Default Company', UPPER(LEFT(TRIM(@old_invite), 16)));
SET @def_company = LAST_INSERT_ID();

-- ---------- callers ----------
ALTER TABLE `callers`
  DROP INDEX `employee_id`,
  ADD COLUMN `company_id`        INT UNSIGNED NULL AFTER `id`,
  ADD COLUMN `phone`             VARCHAR(20)  NULL AFTER `name`,
  ADD COLUMN `status`            ENUM('active','blocked') NOT NULL DEFAULT 'active' AFTER `token_hash`,
  ADD COLUMN `sim_mode`          TINYINT UNSIGNED NOT NULL DEFAULT 0 AFTER `status`,
  ADD COLUMN `sim1_name`         VARCHAR(64)  NULL AFTER `sim_mode`,
  ADD COLUMN `sim2_name`         VARCHAR(64)  NULL AFTER `sim1_name`,
  ADD COLUMN `device_model`      VARCHAR(100) NULL AFTER `sim2_name`,
  ADD COLUMN `android_version`   VARCHAR(20)  NULL AFTER `device_model`,
  ADD COLUMN `app_version`       VARCHAR(30)  NULL AFTER `android_version`,
  ADD COLUMN `battery_pct`       TINYINT UNSIGNED NULL AFTER `app_version`,
  ADD COLUMN `is_charging`       TINYINT(1)   NULL AFTER `battery_pct`,
  ADD COLUMN `network_type`      VARCHAR(20)  NULL AFTER `is_charging`,
  ADD COLUMN `perm_call_log`     TINYINT(1)   NULL AFTER `network_type`,
  ADD COLUMN `perm_contacts`     TINYINT(1)   NULL AFTER `perm_call_log`,
  ADD COLUMN `perm_recording`    TINYINT(1)   NULL AFTER `perm_contacts`,
  ADD COLUMN `recording_enabled` TINYINT(1)   NOT NULL DEFAULT 0 AFTER `perm_recording`,
  ADD COLUMN `last_sync_at`      DATETIME     NULL AFTER `recording_enabled`,
  ADD COLUMN `last_call_at`      DATETIME     NULL AFTER `last_sync_at`,
  ADD COLUMN `registered_at`     DATETIME     NULL AFTER `last_call_at`;

UPDATE `callers` SET `company_id` = @def_company, `registered_at` = `created_at`;

ALTER TABLE `callers`
  MODIFY `company_id` INT UNSIGNED NOT NULL,
  ADD UNIQUE KEY `uq_company_employee` (`company_id`, `employee_id`),
  ADD KEY `idx_token` (`token_hash`),
  ADD CONSTRAINT `fk_callers_company` FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`) ON DELETE CASCADE;

-- ---------- call_logs ----------
ALTER TABLE `call_logs`
  ADD COLUMN `company_id`     INT UNSIGNED NULL AFTER `id`,
  ADD COLUMN `number_key`     VARCHAR(16)  NOT NULL DEFAULT '' AFTER `contact_number`,
  ADD COLUMN `contact_saved`  TINYINT(1)   NULL AFTER `contact_name`,
  ADD COLUMN `ring_sec`       INT UNSIGNED NULL AFTER `duration_sec`,
  ADD COLUMN `ended_at`       DATETIME     NULL AFTER `called_at`,
  ADD COLUMN `sim_slot`       TINYINT UNSIGNED NULL AFTER `ended_at`,
  ADD COLUMN `sim_name`       VARCHAR(64)  NULL AFTER `sim_slot`,
  ADD COLUMN `note`           VARCHAR(1000) NULL AFTER `sim_name`,
  ADD COLUMN `note_at`        DATETIME     NULL AFTER `note`,
  ADD COLUMN `recording_file` VARCHAR(255) NULL AFTER `note_at`,
  ADD COLUMN `recording_size` INT UNSIGNED NULL AFTER `recording_file`,
  ADD COLUMN `recording_at`   DATETIME     NULL AFTER `recording_size`,
  ADD COLUMN `updated_at`     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `created_at`;

UPDATE `call_logs` cl JOIN `callers` c ON c.`id` = cl.`caller_id`
SET cl.`company_id` = c.`company_id`,
    cl.`number_key` = RIGHT(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(cl.`contact_number`, '+', ''), ' ', ''), '-', ''), '(', ''), ')', ''), 10);

ALTER TABLE `call_logs`
  MODIFY `company_id` INT UNSIGNED NOT NULL,
  ADD KEY `idx_company_time`  (`company_id`, `called_at`),
  ADD KEY `idx_caller_number` (`caller_id`, `number_key`, `called_at`),
  ADD CONSTRAINT `fk_logs_company` FOREIGN KEY (`company_id`) REFERENCES `companies`(`id`) ON DELETE CASCADE;

-- এবার schema.sql চালান।
