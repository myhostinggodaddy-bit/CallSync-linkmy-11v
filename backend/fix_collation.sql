-- =====================================================================
--  Fix for: "Illegal mix of collations (utf8mb4_uca1400_ai_ci) and (utf8mb4_unicode_ci)"
--  phpMyAdmin → banksysphp → SQL → পুরোটা paste করে Go।
--  সব CallSync টেবিল আর trigger একই collation (utf8mb4_unicode_ci) তে আনা হচ্ছে।
-- =====================================================================

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- নতুন টেবিলগুলোও যেন এই collation পায়
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

ALTER TABLE `users`             CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE `companies`         CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE `callers`           CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE `call_logs`         CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE `device_heartbeats` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- trigger আবার বানানো (variable-এর collation নির্দিষ্ট করে)
-- No trigger: PHP (Register.php / lib.php) creates the company + invite code.
-- (A trigger caused "Illegal mix of collations" on MariaDB 11.)
DROP TRIGGER IF EXISTS `trg_users_company`;
