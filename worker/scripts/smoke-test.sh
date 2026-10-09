#!/usr/bin/env bash
# Smoke test of a deployed sim-service: health, keyed encounter, and a report saved by an optimize job then read back.
# Usage: SIM_API_KEY=<key> scripts/smoke-test.sh https://dnd-app-sim.<subdomain>.workers.dev
set -euo pipefail

base="${1:?usage: SIM_API_KEY=<key> $0 <base-url>}"
base="${base%/}"
key="${SIM_API_KEY:?SIM_API_KEY is required}"
body="$(mktemp)"
trap 'rm -f "$body"' EXIT

# Prints the HTTP status; the response body is left in $body.
call() {
	local method="$1" path="$2"
	shift 2
	curl -sS -o "$body" -w '%{http_code}' --max-time 60 -X "$method" "$base$path" "$@"
}
keyed() { call "$@" -H "X-API-Key: $key"; }
fail() {
	echo "FAIL: $*" >&2
	cat "$body" >&2 || true
	echo >&2
	exit 1
}

echo "Waiting for $base/actuator/health (first request after a deploy starts the container)"
for attempt in $(seq 1 40); do
	status="$(call GET /actuator/health || true)"
	if [ "$status" = 200 ] && jq -e '.status == "UP"' "$body" >/dev/null; then
		echo "health: UP"
		break
	fi
	[ "$attempt" = 40 ] && fail "health returned $status"
	sleep 15
done

encounter='{"level":3,"party":[{"type":"build","genome":{"classSlug":"fighter","weaponName":"Longsword","armorName":"Chain Mail","shield":true,"fightingStyle":"defense"}}],"enemies":[{"monsterSlug":"goblin-warrior","count":2}],"map":"open-field","runs":5,"seed":7}'
status="$(call POST /api/v1/simulate/encounter -H 'Content-Type: application/json' -d "$encounter")"
[ "$status" = 401 ] || fail "encounter without a key returned $status, expected 401"
status="$(keyed POST /api/v1/simulate/encounter -H 'Content-Type: application/json' -d "$encounter")"
[ "$status" = 200 ] || fail "keyed encounter returned $status"
jq -e '.runs == 5 and (.winRate.point | type == "number")' "$body" >/dev/null || fail "unexpected encounter response"
echo "encounter: winRate $(jq -r .winRate.point "$body")"

status="$(keyed POST /api/v1/simulate/optimize -H 'Content-Type: application/json' \
	-d '{"level":3,"ga":{"populationSize":4,"generations":1,"evalRuns":2},"seed":7}')"
[ "$status" = 202 ] || fail "optimize returned $status"
job="$(jq -r .id "$body")"
echo "optimize: job $job"
for attempt in $(seq 1 60); do
	status="$(keyed GET "/api/v1/jobs/$job")"
	[ "$status" = 200 ] || fail "job poll returned $status"
	state="$(jq -r .status "$body")"
	case "$state" in
	succeeded) break ;;
	failed | cancelled) fail "job $state" ;;
	esac
	[ "$attempt" = 60 ] && fail "job still $state"
	sleep 5
done
report="$(jq -r .reportId "$body")"

status="$(keyed GET "/api/v1/reports/$report")"
[ "$status" = 200 ] || fail "report $report returned $status"
status="$(keyed GET "/api/v1/reports?limit=200")"
[ "$status" = 200 ] || fail "report list returned $status"
jq -e --arg id "$report" 'any(.items[]; .id == $id)' "$body" >/dev/null || fail "report $report missing from the list"
echo "report: $report saved and read back"
echo "Smoke test passed"
