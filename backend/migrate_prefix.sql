-- Jednorazová migrácia pre už existujúcu databázu (staré názvy tabuliek bez prefixu).
SET NAMES utf8mb4;
-- Nová inštalácia ju nepotrebuje – stačí schema.sql.
USE car_tracking;
RENAME TABLE
    users       TO CarTracking_users,
    api_tokens  TO CarTracking_api_tokens,
    trips       TO CarTracking_trips,
    trip_points TO CarTracking_trip_points;
