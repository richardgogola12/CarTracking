-- Migrácia na verziu 1.3 (rýchlostné limity vozidla v obci / mimo obce / na diaľnici). Spustite raz po migrate_v3.sql.
SET NAMES utf8mb4;
USE car_tracking;
ALTER TABLE CarTracking_vehicles
    ADD COLUMN limit_urban   SMALLINT UNSIGNED NOT NULL DEFAULT 50  AFTER updated_at,
    ADD COLUMN limit_rural   SMALLINT UNSIGNED NOT NULL DEFAULT 90  AFTER limit_urban,
    ADD COLUMN limit_highway SMALLINT UNSIGNED NOT NULL DEFAULT 130 AFTER limit_rural;
