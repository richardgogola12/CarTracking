-- Migrácia na verziu 1.1 (kniha jázd, štýl jazdy). Spustite raz na existujúcej databáze.
SET NAMES utf8mb4;
USE car_tracking;
ALTER TABLE CarTracking_trips
    ADD COLUMN category      VARCHAR(10)  NOT NULL DEFAULT 'private' AFTER trigger_source,
    ADD COLUMN note          VARCHAR(500) NOT NULL DEFAULT '' AFTER category,
    ADD COLUMN start_address VARCHAR(255) NULL AFTER note,
    ADD COLUMN end_address   VARCHAR(255) NULL AFTER start_address,
    ADD COLUMN score         TINYINT UNSIGNED NULL AFTER end_address,
    ADD COLUMN hard_brakes   INT UNSIGNED NOT NULL DEFAULT 0 AFTER score,
    ADD COLUMN hard_accels   INT UNSIGNED NOT NULL DEFAULT 0 AFTER hard_brakes,
    ADD COLUMN speeding_sec  INT UNSIGNED NOT NULL DEFAULT 0 AFTER hard_accels;
ALTER TABLE CarTracking_trip_points
    ADD COLUMN event VARCHAR(10) NULL AFTER altitude_m;
