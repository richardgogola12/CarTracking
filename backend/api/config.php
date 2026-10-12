<?php
// Upravte podľa svojho servera. Hodnoty môžu prísť aj z premenných prostredia.
return [
    'db_host'     => getenv('CT_DB_HOST') ?: '127.0.0.1',
    'db_port'     => getenv('CT_DB_PORT') ?: '3306',
    'db_name'     => getenv('CT_DB_NAME') ?: 'car_tracking',
    'db_user'     => getenv('CT_DB_USER') ?: 'car_tracking',
    'db_pass'     => getenv('CT_DB_PASS') ?: 'zmen_ma',
    'token_days'  => 90,     // platnosť prihlasovacieho tokenu
    'max_points_per_request' => 2000,
];
