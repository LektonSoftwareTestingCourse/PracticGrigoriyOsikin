#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
project="smp-api-tests-$$"
compose=(docker compose --env-file .env.example -p "$project"
  -f docker-compose.yaml -f infra/compose.api-tests.yaml)

cleanup() {
  result=$?
  if ((result != 0)); then
    "${compose[@]}" logs --tail=40 >&2 || true
  fi
  "${compose[@]}" down -t 5 -v || true
}
trap cleanup EXIT

"${compose[@]}" up -d --build --wait --wait-timeout 180 \
  gateway card-management switch authorization bin-lookup \
  transaction-logger terminal-simulator merchant-acquirer

port() {
  local address
  address=$("${compose[@]}" port "$1" 8080)
  echo "${address##*:}"
}

export API_TEST_HOST=127.0.0.1
export GATEWAY_PORT SWITCH_PORT AUTH_PORT LOGGER_PORT CARD_MGMT_PORT MERCHANT_PORT
GATEWAY_PORT=$(port gateway)
SWITCH_PORT=$(port switch)
AUTH_PORT=$(port authorization)
LOGGER_PORT=$(port transaction-logger)
CARD_MGMT_PORT=$(port card-management)
MERCHANT_PORT=$(port merchant-acquirer)

mvn -B -f services/pom.xml -Papi-tests verify "$@"

if (($# == 0)); then
  API_TEST_AMQP_PORT=1 "${compose[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 60 switch
  SWITCH_PORT=$(port switch)
  API_TEST_RATE_CAPACITY=1 API_TEST_RATE_REFILL=0 \
    "${compose[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 60 gateway
  GATEWAY_PORT=$(port gateway)
  mvn -B -f services/pom.xml -Papi-tests -Dtest=RollbackOnPublishFailureIT,GatewayRateLimitIT test
fi
