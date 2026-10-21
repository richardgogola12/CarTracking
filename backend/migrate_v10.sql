-- Trasník 2.0: celodenné kroky z krokomera telefónu po hodinách (aj mimo zaznamenanej chôdze)
SET NAMES utf8mb4;
USE car_tracking;

CREATE TABLE IF NOT EXISTS CarTracking_step_hours (
    user_id INT UNSIGNED    NOT NULL,
    hour_ts BIGINT UNSIGNED NOT NULL COMMENT 'začiatok hodiny (ms)',
    steps   INT UNSIGNED    NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, hour_ts),
    CONSTRAINT CarTracking_fk_steps_user FOREIGN KEY (user_id) REFERENCES CarTracking_users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
