-- Migrácia na verziu 1.5 (miesta, tankovanie, pripomienky). Spustite raz po migrate_v4.sql.
SET NAMES utf8mb4;
USE car_tracking;
ALTER TABLE CarTracking_trips
    ADD COLUMN start_place VARCHAR(100) NULL AFTER vehicle_uuid,
    ADD COLUMN end_place   VARCHAR(100) NULL AFTER start_place;
CREATE TABLE IF NOT EXISTS CarTracking_items (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id    INT UNSIGNED NOT NULL,
    kind       VARCHAR(20)  NOT NULL COMMENT 'place | refuel | reminder',
    uuid       CHAR(36)     NOT NULL UNIQUE,
    data       JSON         NOT NULL,
    updated_at BIGINT       NOT NULL DEFAULT 0 COMMENT 'epoch ms',
    deleted    TINYINT(1)   NOT NULL DEFAULT 0,
    INDEX idx_items_user_kind (user_id, kind),
    CONSTRAINT CarTracking_fk_items_user FOREIGN KEY (user_id) REFERENCES CarTracking_users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
