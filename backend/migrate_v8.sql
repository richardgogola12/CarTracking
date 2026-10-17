-- Trasník 2.0: ochrana pred hádaním hesiel (obmedzenie pokusov o prihlásenie) a výdavky na auto (kind = expense)
SET NAMES utf8mb4;
USE car_tracking;

CREATE TABLE IF NOT EXISTS CarTracking_login_attempts (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    kind       VARCHAR(10)  NOT NULL COMMENT 'fail | register',
    email      VARCHAR(190) NOT NULL DEFAULT '',
    ip         VARCHAR(45)  NOT NULL DEFAULT '',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_attempts_email (email, created_at),
    INDEX idx_attempts_ip (ip, created_at)
) ENGINE=InnoDB;

ALTER TABLE CarTracking_items MODIFY kind VARCHAR(20) NOT NULL COMMENT 'place | refuel | reminder | expense';
