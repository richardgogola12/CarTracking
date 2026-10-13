-- Car Tracking – MySQL schéma (MySQL 8.0+ / MariaDB 10.5+)
CREATE DATABASE IF NOT EXISTS car_tracking CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE car_tracking;

CREATE TABLE IF NOT EXISTS CarTracking_users (
    id            INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(100)  NOT NULL,
    email         VARCHAR(190)  NOT NULL UNIQUE,
    password_hash VARCHAR(255)  NOT NULL,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- Prihlasovacie tokeny (ukladá sa iba SHA-256 hash tokenu)
CREATE TABLE IF NOT EXISTS CarTracking_api_tokens (
    id           INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id      INT UNSIGNED NOT NULL,
    token_hash   CHAR(64)     NOT NULL UNIQUE,
    device_name  VARCHAR(100) NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at DATETIME     NULL,
    expires_at   DATETIME     NOT NULL,
    CONSTRAINT CarTracking_fk_tokens_user FOREIGN KEY (user_id) REFERENCES CarTracking_users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS CarTracking_vehicles (
    id            INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id       INT UNSIGNED NOT NULL,
    uuid          CHAR(36)     NOT NULL UNIQUE,
    name          VARCHAR(100) NOT NULL,
    plate         VARCHAR(20)  NOT NULL DEFAULT '',
    fuel_type     VARCHAR(10)  NOT NULL DEFAULT 'petrol' COMMENT 'petrol | diesel | lpg | hybrid | electric',
    consumption   DECIMAL(5,2) NOT NULL DEFAULT 6.5 COMMENT 'l alebo kWh na 100 km',
    fuel_price    DECIMAL(6,3) NOT NULL DEFAULT 1.6 COMMENT '€ za l alebo kWh',
    odometer_km   INT UNSIGNED NOT NULL DEFAULT 0,
    odometer_date BIGINT       NOT NULL DEFAULT 0 COMMENT 'epoch ms',
    bt_name       VARCHAR(100) NOT NULL DEFAULT '',
    archived      TINYINT(1)   NOT NULL DEFAULT 0,
    updated_at    BIGINT       NOT NULL DEFAULT 0 COMMENT 'epoch ms',
    INDEX idx_vehicles_user (user_id),
    CONSTRAINT CarTracking_fk_vehicles_user FOREIGN KEY (user_id) REFERENCES CarTracking_users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS CarTracking_trips (
    id               INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id          INT UNSIGNED NOT NULL,
    uuid             CHAR(36)     NOT NULL UNIQUE,
    started_at       BIGINT       NOT NULL COMMENT 'epoch ms',
    ended_at         BIGINT       NULL     COMMENT 'epoch ms',
    distance_m       DOUBLE       NOT NULL DEFAULT 0,
    max_speed_kmh    DOUBLE       NOT NULL DEFAULT 0,
    avg_speed_kmh    DOUBLE       NOT NULL DEFAULT 0,
    start_lat        DOUBLE NULL, start_lng DOUBLE NULL,
    end_lat          DOUBLE NULL, end_lng   DOUBLE NULL,
    trigger_source   VARCHAR(20)  NOT NULL DEFAULT 'auto' COMMENT 'auto | bluetooth | manual',
    category         VARCHAR(10)  NOT NULL DEFAULT 'private' COMMENT 'private | business',
    note             VARCHAR(500) NOT NULL DEFAULT '',
    start_address    VARCHAR(255) NULL,
    end_address      VARCHAR(255) NULL,
    score            TINYINT UNSIGNED NULL COMMENT 'štýl jazdy 0–100',
    hard_brakes      INT UNSIGNED NOT NULL DEFAULT 0,
    hard_accels      INT UNSIGNED NOT NULL DEFAULT 0,
    speeding_sec     INT UNSIGNED NOT NULL DEFAULT 0,
    type             VARCHAR(10)  NOT NULL DEFAULT 'car' COMMENT 'car | walk',
    steps            INT UNSIGNED NOT NULL DEFAULT 0,
    vehicle_uuid     CHAR(36)     NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_trips_user_start (user_id, started_at),
    INDEX idx_trips_vehicle (vehicle_uuid),
    CONSTRAINT CarTracking_fk_trips_user FOREIGN KEY (user_id) REFERENCES CarTracking_users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS CarTracking_trip_points (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    trip_id    INT UNSIGNED NOT NULL,
    ts         BIGINT       NOT NULL COMMENT 'epoch ms',
    lat        DOUBLE       NOT NULL,
    lng        DOUBLE       NOT NULL,
    speed_kmh  FLOAT        NULL,
    accuracy_m FLOAT        NULL,
    bearing    FLOAT        NULL,
    altitude_m DOUBLE       NULL,
    event      VARCHAR(10)  NULL COMMENT 'brake | accel | speeding',
    UNIQUE KEY uq_point (trip_id, ts),
    CONSTRAINT CarTracking_fk_points_trip FOREIGN KEY (trip_id) REFERENCES CarTracking_trips(id) ON DELETE CASCADE
) ENGINE=InnoDB;
