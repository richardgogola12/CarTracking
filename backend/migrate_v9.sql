-- Trasník 2.0: prihlásenie webového prehľadu cez QR kód / párovací kód z aplikácie
SET NAMES utf8mb4;
USE car_tracking;

CREATE TABLE IF NOT EXISTS CarTracking_pairings (
    code_hash  CHAR(64)     NOT NULL PRIMARY KEY COMMENT 'SHA-256 tajného kódu z QR',
    short_code CHAR(6)      NOT NULL COMMENT '6 číslic na ručné zadanie',
    ip         VARCHAR(45)  NOT NULL DEFAULT '',
    user_agent VARCHAR(255) NOT NULL DEFAULT '',
    user_id    INT UNSIGNED NULL,
    token      CHAR(64)     NULL COMMENT 'vydaný token – zmaže sa hneď po odovzdaní webu',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME     NOT NULL,
    INDEX idx_pair_short (short_code, expires_at),
    CONSTRAINT CarTracking_fk_pair_user FOREIGN KEY (user_id) REFERENCES CarTracking_users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
