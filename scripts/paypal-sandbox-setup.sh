#!/usr/bin/env bash
set -euo pipefail

: "${PAYPAL_CLIENT_ID:?Set PAYPAL_CLIENT_ID}"
: "${PAYPAL_CLIENT_SECRET:?Set PAYPAL_CLIENT_SECRET}"
: "${PAYPAL_BASE_URL:=https://api-m.sandbox.paypal.com}"
: "${PAYGUARD_MANDATE_ID:=payguard-demo-mandate}"
: "${PAYGUARD_DB_URL:=jdbc:postgresql://localhost:5432/payguard}"
: "${PAYGUARD_DB_USERNAME:=payguard}"
: "${PAYGUARD_DB_PASSWORD:=}"

case "$PAYPAL_BASE_URL" in
  https://api-m.sandbox.paypal.com|https://api-m.sandbox.paypal.com/) ;;
  *) echo "PAYPAL_BASE_URL must be the canonical Sandbox origin" >&2; exit 1 ;;
esac

amount="${1:?Usage: bash scripts/paypal-sandbox-setup.sh AMOUNT_USD}"
if [[ ! "$amount" =~ ^[0-9]+([.][0-9]{1,2})?$ ]]; then
  echo "AMOUNT_USD must be a positive USD amount with at most two decimals" >&2
  exit 1
fi

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
oauth_config="$work_dir/oauth.conf"
api_config="$work_dir/api.conf"
umask 077

cat >"$oauth_config" <<EOF
silent
show-error
fail-with-body
connect-timeout = 10
max-time = 30
user = "$PAYPAL_CLIENT_ID:$PAYPAL_CLIENT_SECRET"
header = "Accept: application/json"
header = "Accept-Language: en_US"
data = "grant_type=client_credentials"
url = "$PAYPAL_BASE_URL/v1/oauth2/token"
EOF
token_json="$(curl --config "$oauth_config")"
token="$(jq -er '.access_token | select(type == "string" and length > 0)' <<<"$token_json")"

cat >"$api_config" <<EOF
silent
show-error
fail-with-body
connect-timeout = 10
max-time = 30
header = "Authorization: Bearer ${token}"
header = "Content-Type: application/json"
url = "$PAYPAL_BASE_URL/v2/checkout/orders"
EOF
order_json="$(
  printf '%s\n' \
    "url = \"$PAYPAL_BASE_URL/v2/checkout/orders\"" \
    "data = {\"intent\":\"CAPTURE\",\"purchase_units\":[{\"amount\":{\"currency_code\":\"USD\",\"value\":\"$amount\"}}]}" \
    | cat "$api_config" - \
    | curl --config -
)"
order_id="$(jq -er '.id | select(type == "string" and length > 0)' <<<"$order_json")"
approval_url="$(jq -er '.links[] | select(.rel == "approve") | .href' <<<"$order_json")"
echo "Approve this Sandbox order in a browser:"
echo "$approval_url"
read -r -p "Press Enter after buyer approval: " _

capture_json="$(
  printf '%s\n' \
    "url = \"$PAYPAL_BASE_URL/v2/checkout/orders/$order_id/capture\"" \
    'data = {}' \
    | cat "$api_config" - \
    | curl --config -
)"
capture_id="$(jq -er '.status as $status | select($status == "COMPLETED") |
  .purchase_units[0].payments.captures[0].id |
  select(type == "string" and length > 0)' <<<"$capture_json")"
capture_status="$(jq -er '.purchase_units[0].payments.captures[0].status' <<<"$capture_json")"
capture_currency="$(jq -er '.purchase_units[0].payments.captures[0].amount.currency_code' <<<"$capture_json")"
capture_value="$(jq -er '.purchase_units[0].payments.captures[0].amount.value' <<<"$capture_json")"
if [[ "$capture_status" != "COMPLETED" || "$capture_currency" != "USD"
      || "$capture_value" != "$amount" ]]; then
  echo "Sandbox capture did not match the requested completed USD amount" >&2
  exit 1
fi

echo "Sandbox capture created: $capture_id"
echo "No refund is performed by this setup script."
if [[ "${PAYGUARD_REGISTER_CAPTURE:-false}" != "true" ]]; then
  echo "Set PAYGUARD_REGISTER_CAPTURE=true to explicitly register this capture."
  exit 0
fi

command -v psql >/dev/null || {
  echo "PAYGUARD_REGISTER_CAPTURE=true requires psql" >&2
  exit 1
}
db_url="${PAYGUARD_DB_URL#jdbc:}"
amount_cents="$(jq -er '.purchase_units[0].payments.captures[0].amount.value
  | tonumber * 100 | floor' <<<"$capture_json")"
PGPASSWORD="$PAYGUARD_DB_PASSWORD" psql "$db_url" \
  --username "$PAYGUARD_DB_USERNAME" \
  --set=capture_id="$capture_id" \
  --set=mandate_id="$PAYGUARD_MANDATE_ID" \
  --set=amount_cents="$amount_cents" \
  --set=order_id="$order_id" \
  --command "insert into paypal_capture_provenance
    (capture_id, mandate_id, amount_cents, currency, order_id,
     registered_at, no_external_refunds_confirmed)
    values (:'capture_id', :'mandate_id', :amount_cents, 'USD',
            :'order_id', now(), false)
    on conflict (capture_id) do nothing;"
