-- Migrácia na verziu 1.2 (viac vozidiel, chôdza). Spustite raz na existujúcej databáze (po migrate_v2.sql).
SET NAMES utf8mb4;
USE car_tracking;
CREATE TABLE IF NOT EXISTS CarTracking_vehicles (
    id            INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id       INT UNSIGNED NOT NULL,
    uuid          CHAR(36)     NOT NULL UNIQUE,
    name          VARCHAR(100) NOT NULL,
    plate         VARCHAR(20)  NOT NULL DEFAULT '',
    fuel_type     VARCHAR(10)  NOT NULL DEFAULT 'petrol',
    consumption   DECIMAL(5,2) NOT NULL DEFAULT 6.5,
    fuel_price    DECIMAL(6,3) NOT NULL DEFAULT 1.6,
    odometer_km   INT UNSIGNED NOT NULL DEFAULT 0,
    odometer_date BIGINT       NOT NULL DEFAULT 0,
    bt_name       VARCHAR(100) NOT NULL DEFAULT '',
    archived      TINYINT(1)   NOT NULL DEFAULT 0,
    updated_at    BIGINT       NOT NULL DEFAULT 0,
    INDEX idx_vehicles_user (user_id),
    CONSTRAINT CarTracking_fk_vehicles_user FOREIGN KEY (user_id) REFERENCES CarTracking_users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
ALTER TABLE CarTracking_trips
    ADD COLUMN type         VARCHAR(10)  NOT NULL DEFAULT 'car' AFTER speeding_sec,
    ADD COLUMN steps        INT UNSIGNED NOT NULL DEFAULT 0 AFTER type,
    ADD COLUMN vehicle_uuid CHAR(36)     NULL AFTER steps,
    ADD INDEX idx_trips_vehicle (vehicle_uuid);
