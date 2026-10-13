-- Car Tracking 1.7: obnova údajov a nastavení po prihlásení na novom telefóne
SET NAMES utf8mb4;
USE car_tracking;

ALTER TABLE CarTracking_vehicles
    ADD COLUMN bt_address VARCHAR(17) NOT NULL DEFAULT '' COMMENT 'MAC adresa Bluetooth vozidla';

CREATE TABLE IF NOT EXISTS CarTracking_settings (
    user_id    INT UNSIGNED NOT NULL PRIMARY KEY,
    data       JSON         NOT NULL,
    updated_at BIGINT       NOT NULL DEFAULT 0 COMMENT 'epoch ms',
    CONSTRAINT CarTracking_fk_settings_user FOREIGN KEY (user_id) REFERENCES CarTracking_users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
