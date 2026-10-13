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
# Zlúčenie: druhá jazda sa zmaže, jej body prídu pod prvou jazdou
B2=aaaaaaaa-bbbb-cccc-dddd-000000000002
check "sync druhej jazdy" 200 "$(req "${AUTH[@]}" -X POST "$API/trips/sync" -d "{\"trips\":[{\"uuid\":\"$B2\",\"started_at\":1700000900000,\"ended_at\":1700001200000,\"distance_m\":3000,\"trigger_source\":\"auto\",\"type\":\"car\",\"vehicle_uuid\":\"$VEH\",\"points\":[{\"ts\":1700000900000,\"lat\":48.2,\"lng\":17.2,\"speed_kmh\":30}]}]}")"
check "zlúčená jazda (body + súčty)" 200 "$(req "${AUTH[@]}" -X POST "$API/trips/sync" -d "{\"trips\":[{\"uuid\":\"$CAR\",\"started_at\":1700000000000,\"ended_at\":1700001200000,\"distance_m\":11200,\"max_speed_kmh\":92,\"avg_speed_kmh\":33,\"end_lat\":48.2,\"end_lng\":17.2,\"trigger_source\":\"auto\",\"category\":\"business\",\"type\":\"car\",\"vehicle_uuid\":\"$VEH\",\"points\":[{\"ts\":1700000900000,\"lat\":48.2,\"lng\":17.2,\"speed_kmh\":30}]}]}")"
check "zmazanie zlúčenej druhej jazdy" 200 "$(req "${AUTH[@]}" -X DELETE "$API/trips/$B2")"
check "detail po zlúčení" 200 "$(req "${AUTH[@]}" "$API/trips/$CAR")"
python3 -c 'import sys,json;d=json.loads(sys.argv[1]);assert len(d["points"])==3, len(d["points"]);assert float(d["trip"]["distance_m"])==11200 and int(d["trip"]["ended_at"])==1700001200000' "$LAST" || fail "zlúčenie na serveri nesedí"
check "druhá jazda je preč" 404 "$(req "${AUTH[@]}" "$API/trips/$B2")"

# Miesta / tankovania / pripomienky
PL=bbbbbbbb-0000-0000-0000-000000000001
RF=bbbbbbbb-0000-0000-0000-000000000002
check "sync položiek" 200 "$(req "${AUTH[@]}" -X POST "$API/items/sync" -d "{\"items\":[
 {\"kind\":\"place\",\"uuid\":\"$PL\",\"updated_at\":100,\"deleted\":false,\"data\":{\"name\":\"Domov\",\"lat\":48.1,\"lng\":17.1,\"radius_m\":150}},
 {\"kind\":\"refuel\",\"uuid\":\"$RF\",\"updated_at\":100,\"deleted\":false,\"data\":{\"liters\":45.2,\"total_price\":70.06,\"odometer_km\":120450}}]}")"
echo "$LAST" | grep -q "$PL" && echo "$LAST" | grep -q "$RF" || fail "položky neboli prijaté"
check "staršia verzia sa neprepíše" 200 "$(req "${AUTH[@]}" -X POST "$API/items/sync" -d "{\"items\":[{\"kind\":\"place\",\"uuid\":\"$PL\",\"updated_at\":50,\"deleted\":false,\"data\":{\"name\":\"STARE\"}}]}")"
check "zoznam miest" 200 "$(req "${AUTH[@]}" "$API/items?kind=place")"
python3 -c 'import sys,json;i=json.loads(sys.argv[1])["items"];assert len(i)==1 and i[0]["data"]["name"]=="Domov", i' "$LAST" || fail "miesto nesedí"
check "jazda s názvami miest" 200 "$(req "${AUTH[@]}" -X POST "$API/trips/sync" -d "{\"trips\":[{\"uuid\":\"$CAR\",\"started_at\":1700000000000,\"ended_at\":1700001200000,\"distance_m\":11200,\"trigger_source\":\"auto\",\"type\":\"car\",\"start_place\":\"Domov\",\"end_place\":\"Práca\",\"points\":[]}]}")"
check "detail s miestami" 200 "$(req "${AUTH[@]}" "$API/trips/$CAR")"
python3 -c 'import sys,json;t=json.loads(sys.argv[1])["trip"];assert t["start_place"]=="Domov" and t["end_place"]=="Práca", t' "$LAST" || fail "názvy miest nesedia"

# Filtre zoznamu jázd (webový prehľad)
check "jazdy podľa obdobia a typu" 200 "$(req "${AUTH[@]}" "$API/trips?from=1699999999000&to=1700000500000&type=car&limit=1000")"
python3 -c 'import sys,json;t=json.loads(sys.argv[1])["trips"];assert len(t)==1 and t[0]["uuid"]=="'"$CAR"'", t' "$LAST" || fail "filter jázd nesedí"

# Sadzba náhrady €/km
check "sadzba vozidla" 200 "$(req "${AUTH[@]}" -X POST "$API/vehicles/sync" -d "{\"vehicles\":[{\"uuid\":\"$VEH\",\"name\":\"Škoda Octavia\",\"plate\":\"BA123XY\",\"fuel_type\":\"diesel\",\"consumption\":5.4,\"fuel_price\":1.55,\"odometer_km\":120000,\"odometer_date\":1700000000000,\"bt_name\":\"\",\"archived\":false,\"updated_at\":1700000000001,\"limit_urban\":50,\"limit_rural\":80,\"limit_highway\":100,\"rate_per_km\":0.313}]}")"
check "zoznam vozidiel so sadzbou" 200 "$(req "${AUTH[@]}" "$API/vehicles")"
python3 -c 'import sys,json;v=json.loads(sys.argv[1])["vehicles"][0];assert abs(v["rate_per_km"]-0.313)<1e-6 and v["is_owner"] is True, v' "$LAST" || fail "sadzba nesedí"

# Zdieľané vozidlo
EMAIL2="druhy$RANDOM@example.com"
check "registrácia druhého vodiča" 201 "$(req -X POST "$API/register" -d "{\"name\":\"Druhý\",\"email\":\"$EMAIL2\",\"password\":\"heslo1234\"}")"
AUTH2=(-H "Authorization: Bearer $(echo "$LAST" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')")
check "registrácia tretieho" 201 "$(req -X POST "$API/register" -d "{\"name\":\"Tretí\",\"email\":\"treti$RANDOM@example.com\",\"password\":\"heslo1234\"}")"
AUTH3=(-H "Authorization: Bearer $(echo "$LAST" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')")
check "zdieľanie s neexistujúcim" 404 "$(req "${AUTH[@]}" -X POST "$API/vehicles/$VEH/shares" -d '{"email":"nikto@example.com"}')"
check "zdieľanie vozidla" 201 "$(req "${AUTH[@]}" -X POST "$API/vehicles/$VEH/shares" -d "{\"email\":\"$EMAIL2\"}")"
U2=$(echo "$LAST" | python3 -c 'import sys,json;print(json.load(sys.stdin)["share"]["user_id"])')
check "zoznam zdieľaní" 200 "$(req "${AUTH[@]}" "$API/vehicles/$VEH/shares")"
echo "$LAST" | grep -q "$EMAIL2" || fail "zdieľanie chýba"
check "druhý vidí vozidlo" 200 "$(req "${AUTH2[@]}" "$API/vehicles")"
python3 -c 'import sys,json;v=json.loads(sys.argv[1])["vehicles"];assert len(v)==1 and v[0]["is_owner"] is False and v[0]["owner_name"]=="Test", v' "$LAST" || fail "zdieľané vozidlo nesedí"
check "druhý nemôže zdieľať ďalej" 403 "$(req "${AUTH2[@]}" -X POST "$API/vehicles/$VEH/shares" -d '{"email":"x@example.com"}')"
check "druhý nemôže meniť vozidlo" 200 "$(req "${AUTH2[@]}" -X POST "$API/vehicles/sync" -d "{\"vehicles\":[{\"uuid\":\"$VEH\",\"name\":\"PREPISANE\",\"updated_at\":1800000000000}]}")"
check "vozidlo ostalo" 200 "$(req "${AUTH[@]}" "$API/vehicles")"
echo "$LAST" | grep -q PREPISANE && fail "zdieľaný používateľ prepísal vozidlo"
T1=cccccccc-0000-0000-0000-000000000001
T2=cccccccc-0000-0000-0000-000000000002
check "jazda vlastníka" 200 "$(req "${AUTH[@]}" -X POST "$API/trips/sync" -d "{\"trips\":[{\"uuid\":\"$T1\",\"started_at\":1700100000000,\"ended_at\":1700100600000,\"distance_m\":5000,\"trigger_source\":\"auto\",\"type\":\"car\",\"vehicle_uuid\":\"$VEH\",\"points\":[{\"ts\":1700100000000,\"lat\":48.1,\"lng\":17.1}]}]}")"
check "jazda druhého vodiča" 200 "$(req "${AUTH2[@]}" -X POST "$API/trips/sync" -d "{\"trips\":[{\"uuid\":\"$T2\",\"started_at\":1700200000000,\"ended_at\":1700200600000,\"distance_m\":7000,\"trigger_source\":\"auto\",\"category\":\"business\",\"type\":\"car\",\"vehicle_uuid\":\"$VEH\",\"points\":[]}]}")"
check "spoločná kniha jázd" 200 "$(req "${AUTH[@]}" "$API/vehicles/$VEH/trips?from=1700050000000&to=1700300000000")"
python3 -c 'import sys,json;t=json.loads(sys.argv[1])["trips"];d=sorted(x["driver_name"] for x in t);assert d==["Druhý","Test"], t' "$LAST" || fail "spoločná kniha jázd nesedí"
check "druhý vidí jazdu vlastníka" 200 "$(req "${AUTH2[@]}" "$API/trips/$T1")"
check "druhý nemôže zmazať jazdu vlastníka" 404 "$(req "${AUTH2[@]}" -X DELETE "$API/trips/$T1")"
check "cudzí nevidí knihu jázd" 404 "$(req "${AUTH3[@]}" "$API/vehicles/$VEH/trips")"
check "cudzí nevidí jazdu" 404 "$(req "${AUTH3[@]}" "$API/trips/$T1")"
check "zrušenie zdieľania" 200 "$(req "${AUTH[@]}" -X DELETE "$API/vehicles/$VEH/shares/$U2")"
check "po zrušení bez prístupu" 404 "$(req "${AUTH2[@]}" "$API/vehicles/$VEH/trips")"

check "odhlásenie" 200 "$(req "${AUTH[@]}" -X POST "$API/logout")"
check "token po odhlásení" 401 "$(req "${AUTH[@]}" "$API/me")"
echo "::notice::Všetky testy API prešli ✅"
