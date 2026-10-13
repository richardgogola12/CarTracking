-- Car Tracking 1.6: cestovné náhrady (sadzba €/km) a zdieľané vozidlá
SET NAMES utf8mb4;
USE car_tracking;

ALTER TABLE CarTracking_vehicles
    ADD COLUMN rate_per_km DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT 'sadzba základnej náhrady €/km';

CREATE TABLE IF NOT EXISTS CarTracking_vehicle_shares (
    vehicle_uuid CHAR(36)     NOT NULL,
    user_id      INT UNSIGNED NOT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (vehicle_uuid, user_id),
    INDEX idx_shares_user (user_id),
    CONSTRAINT CarTracking_fk_shares_vehicle FOREIGN KEY (vehicle_uuid) REFERENCES CarTracking_vehicles(uuid) ON DELETE CASCADE,
    CONSTRAINT CarTracking_fk_shares_user FOREIGN KEY (user_id) REFERENCES CarTracking_users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
