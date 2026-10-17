#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cat >"$tmp/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" != "--config" ]]; then
  echo "mock curl expected --config" >&2
  exit 1
fi
if [[ "$2" == "-" ]]; then
  config="$(cat)"
else
  config="$(cat "$2")"
fi
if grep -q '/v1/oauth2/token' <<<"$config"; then
  printf '{"access_token":"mock-token"}\n'
elif grep -q '/v2/checkout/orders/[^"]*/capture' <<<"$config"; then
  grep -q 'Authorization: Bearer mock-token' <<<"$config"
  printf '%s\n' '{"status":"COMPLETED","purchase_units":[{"payments":{"captures":[{"id":"CAPTURE-MOCK","status":"COMPLETED","amount":{"value":"35.00","currency_code":"USD"}}]}}]}'
elif grep -q '/v2/checkout/orders' <<<"$config"; then
  grep -q 'Authorization: Bearer mock-token' <<<"$config"
  printf '%s\n' '{"id":"ORDER-MOCK","links":[{"rel":"approve","href":"https://sandbox.example/approve"}]}'
else
  echo "mock curl received an unexpected URL" >&2
  exit 1
fi
EOF
chmod 700 "$tmp/curl"

output="$(
  printf '\n' |
    PATH="$tmp:$PATH" \
    PAYPAL_CLIENT_ID=mock-client \
    PAYPAL_CLIENT_SECRET=mock-secret \
    PAYGUARD_REGISTER_CAPTURE=false \
    bash "$root/scripts/paypal-sandbox-setup.sh" 35.00
)"
grep -q 'CAPTURE-MOCK' <<<"$output"
grep -q 'No refund is performed' <<<"$output"
