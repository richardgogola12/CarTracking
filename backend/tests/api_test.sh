#!/usr/bin/env bash
# End-to-end test REST API proti reálnej MySQL. Použitie: api_test.sh http://127.0.0.1:8080
set -euo pipefail
API="$1"
fail() { echo "::error::$1"; exit 1; }
req() { curl -s -w '\n%{http_code}' -H 'Content-Type: application/json' "$@"; }
check() { # check <popis> <očakávaný kód> <odpoveď>
  local code; code=$(echo "$3" | tail -1); local body; body=$(echo "$3" | sed '$d')
  [ "$code" = "$2" ] || fail "$1: očakávaný HTTP $2, prišiel $code – $body"
  echo "OK  $1 ($code)"; LAST="$body"
}
EMAIL="test$RANDOM@example.com"

check "registrácia" 201 "$(req -X POST "$API/register" -d "{\"name\":\"Test\",\"email\":\"$EMAIL\",\"password\":\"heslo1234\"}")"
TOKEN=$(echo "$LAST" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
check "duplicitná registrácia" 409 "$(req -X POST "$API/register" -d "{\"name\":\"Test\",\"email\":\"$EMAIL\",\"password\":\"heslo1234\"}")"
check "zlé heslo" 401 "$(req -X POST "$API/login" -d "{\"email\":\"$EMAIL\",\"password\":\"zle\"}")"
check "prihlásenie" 200 "$(req -X POST "$API/login" -d "{\"email\":\"$EMAIL\",\"password\":\"heslo1234\"}")"
TOKEN=$(echo "$LAST" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
AUTH=(-H "Authorization: Bearer $TOKEN")
check "bez tokenu" 401 "$(req "$API/me")"
check "me" 200 "$(req "${AUTH[@]}" "$API/me")"

VEH=11111111-2222-3333-4444-555555555555
check "sync vozidla" 200 "$(req "${AUTH[@]}" -X POST "$API/vehicles/sync" -d "{\"vehicles\":[{\"uuid\":\"$VEH\",\"name\":\"Škoda Octavia\",\"plate\":\"BA123XY\",\"fuel_type\":\"diesel\",\"consumption\":5.4,\"fuel_price\":1.55,\"odometer_km\":120000,\"odometer_date\":1700000000000,\"bt_name\":\"Octavia\",\"archived\":false,\"updated_at\":1700000000000,\"limit_urban\":50,\"limit_rural\":80,\"limit_highway\":100}]}")"
echo "$LAST" | grep -q "$VEH" || fail "vozidlo nebolo prijaté"
check "zoznam vozidiel" 200 "$(req "${AUTH[@]}" "$API/vehicles")"
echo "$LAST" | grep -q 'BA123XY' || fail "vozidlo chýba v zozname"
python3 -c 'import sys,json;v=json.loads(sys.argv[1])["vehicles"][0];assert int(v["limit_rural"])==80 and int(v["limit_highway"])==100, v' "$LAST" || fail "limity vozidla nesedia"

CAR=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
WALK=aaaaaaaa-bbbb-cccc-dddd-ffffffffffff
TRIPS="{\"trips\":[
 {\"uuid\":\"$CAR\",\"started_at\":1700000000000,\"ended_at\":1700000600000,\"distance_m\":8200,\"max_speed_kmh\":92,\"avg_speed_kmh\":49,
  \"start_lat\":48.1,\"start_lng\":17.1,\"end_lat\":48.2,\"end_lng\":17.2,\"trigger_source\":\"auto\",\"category\":\"business\",\"note\":\"Klient\",
  \"start_address\":\"Hlavná 1, Bratislava\",\"end_address\":\"Lesná 5, Imeľ\",\"score\":87,\"hard_brakes\":1,\"hard_accels\":0,\"speeding_sec\":0,
  \"type\":\"car\",\"steps\":0,\"vehicle_uuid\":\"$VEH\",
  \"points\":[{\"ts\":1700000000000,\"lat\":48.1,\"lng\":17.1,\"speed_kmh\":0,\"accuracy_m\":5},
             {\"ts\":1700000003000,\"lat\":48.1001,\"lng\":17.1001,\"speed_kmh\":40,\"accuracy_m\":5,\"event\":\"brake\"}]},
 {\"uuid\":\"$WALK\",\"started_at\":1700001000000,\"ended_at\":1700001600000,\"distance_m\":750,\"max_speed_kmh\":0,\"avg_speed_kmh\":4.5,
  \"trigger_source\":\"auto\",\"type\":\"walk\",\"steps\":1000,\"points\":[]}
]}"
check "sync jázd" 200 "$(req "${AUTH[@]}" -X POST "$API/trips/sync" -d "$TRIPS")"
echo "$LAST" | grep -q "$CAR" && echo "$LAST" | grep -q "$WALK" || fail "jazdy neboli prijaté: $LAST"
check "opakovaný sync (bez duplicít)" 200 "$(req "${AUTH[@]}" -X POST "$API/trips/sync" -d "$TRIPS")"
check "zoznam jázd" 200 "$(req "${AUTH[@]}" "$API/trips")"
python3 - "$LAST" <<'PY' || fail "nesprávne údaje v zozname jázd"
import sys, json
trips = {t["uuid"]: t for t in json.loads(sys.argv[1])["trips"]}
car = trips["aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"]; walk = trips["aaaaaaaa-bbbb-cccc-dddd-ffffffffffff"]
assert len(trips) == 2, trips
assert car["type"] == "car" and car["vehicle_uuid"] == "11111111-2222-3333-4444-555555555555" and car["category"] == "business"
assert int(car["score"]) == 87 and car["end_address"] == "Lesná 5, Imeľ"
assert walk["type"] == "walk" and int(walk["steps"]) == 1000
PY
check "detail jazdy" 200 "$(req "${AUTH[@]}" "$API/trips/$CAR")"
python3 -c 'import sys,json;d=json.loads(sys.argv[1]);assert len(d["points"])==2 and d["points"][1]["event"]=="brake"' "$LAST" || fail "body jazdy nesedia"
check "zmazanie" 200 "$(req "${AUTH[@]}" -X DELETE "$API/trips/$WALK")"
check "zmazaná jazda" 404 "$(req "${AUTH[@]}" "$API/trips/$WALK")"
check "odhlásenie" 200 "$(req "${AUTH[@]}" -X POST "$API/logout")"
check "token po odhlásení" 401 "$(req "${AUTH[@]}" "$API/me")"
echo "::notice::Všetky testy API prešli ✅"
