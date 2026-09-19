# The demo digital bank: its customer app, and the organisation it runs
# against on a local monolith. See docs/tdd/demo-digital-bank.md.

DEMO_API_URL := env_var_or_default("DEMO_API_URL", "http://localhost:8080")
# The operator service-account client both local realms seed, under the
# dev profile only. A deployed instance has no such client.
DEMO_ADMIN_CLIENT_ID := "queenswood-admin"
DEMO_ADMIN_CLIENT_SECRET := "queenswood-admin-test-secret"
# Where local development keeps a client: ids/<name> holds the id and
# <name> the secret, beside the local Google client.
DEMO_PASS := "queenswood/local/dev/auth/clients"
DEMO_BANK_NAME := "Xepha"

# Exchange client credentials at the API's token endpoint for a bearer token. client_id, client_secret, scope: the grant's.
_demo-digital-bank-token client_id client_secret scope:
    #!/usr/bin/env zsh
    set -e
    RES=$(curl -sS -X POST {{ DEMO_API_URL }}/oauth/token \
      -d grant_type=client_credentials \
      -d "client_id={{ client_id }}" \
      -d "client_secret={{ client_secret }}" \
      -d "scope={{ scope }}")
    TOKEN=$(jq -r '.access_token // empty' <<<"$RES")
    if [[ -z "$TOKEN" ]]; then
      echo "token refused for {{ client_id }}: $RES" >&2
      exit 1
    fi
    echo "$TOKEN"

# Start the Vite dev server for the demo digital bank's customer app (port 5174)
demo-digital-bank-app-start:
    #!/usr/bin/env zsh
    set -e
    cd {{ justfile_directory() }}
    corepack yarn install
    corepack yarn workspace demo-digital-bank-app dev

# Create the bank's organisation on the local monolith, store its credential in pass, and publish its three products; does only what is not already done.
demo-digital-bank-seed:
    #!/usr/bin/env zsh
    set -e
    if CLIENT_ID=$(pass show {{ DEMO_PASS }}/ids/demo-digital-bank 2>/dev/null | head -1) \
       && [[ -n "$CLIENT_ID" ]]; then
      CLIENT_SECRET=$(pass show {{ DEMO_PASS }}/demo-digital-bank | head -1)
      echo "organisation $CLIENT_ID, from pass"
    else
      ADMIN=$(just _demo-digital-bank-token {{ DEMO_ADMIN_CLIENT_ID }} {{ DEMO_ADMIN_CLIENT_SECRET }} queenswood-api-test+realm-roles)
      RES=$(curl -sS -X POST {{ DEMO_API_URL }}/v1/banks \
        -H "authorization: Bearer $ADMIN" \
        -H 'content-type: application/json' \
        -H "idempotency-key: demo-digital-bank-seed-$(date +%s)" \
        -d '{"name":"{{ DEMO_BANK_NAME }}","status":"test","tier":"micro","currencies":["GBP"]}')
      CLIENT_ID=$(jq -r '.["client-id"] // empty' <<<"$RES")
      if [[ -z "$CLIENT_ID" ]]; then
        echo "organisation refused: $RES" >&2
        exit 1
      fi
      CLIENT_SECRET=$(jq -r '.["client-secret"]' <<<"$RES")
      printf '%s\n' "$CLIENT_ID" | pass insert -m -f {{ DEMO_PASS }}/ids/demo-digital-bank >/dev/null
      printf '%s\n' "$CLIENT_SECRET" | pass insert -m -f {{ DEMO_PASS }}/demo-digital-bank >/dev/null
      echo "organisation $CLIENT_ID, created: test, micro, GBP, sort code $(jq -r '.["sort-code"]' <<<"$RES")"
    fi
    TOKEN=$(just _demo-digital-bank-token "$CLIENT_ID" "$CLIENT_SECRET" queenswood-api-test)
    RES=$(curl -sS {{ DEMO_API_URL }}/v1/cash-account-products -H "authorization: Bearer $TOKEN")
    EXISTING=$(jq -r '.items[].versions[].name' <<<"$RES")
    TODAY=$(date +%F)
    for SPEC in "Everyday|tpl.00000000000000000000000001|0" \
                "Rainy Day|tpl.00000000000000000000000002|410" \
                "1 Year Fixed|tpl.00000000000000000000000003|465"; do
      NAME=${SPEC%%|*}; REST=${SPEC#*|}; TEMPLATE=${REST%%|*}; BPS=${REST#*|}
      if grep -qFx "$NAME" <<<"$EXISTING"; then
        printf '  %-14s already published\n' "$NAME"
        continue
      fi
      BODY=$(jq -n --arg n "$NAME" --arg t "$TEMPLATE" --argjson b "$BPS" --arg d "$TODAY" \
        '{name:$n,"template-id":$t,currency:"GBP","interest-rate-bps":$b,"effective-from":$d}')
      RES=$(curl -sS -X POST {{ DEMO_API_URL }}/v1/cash-account-products \
        -H "authorization: Bearer $TOKEN" \
        -H 'content-type: application/json' \
        -H "idempotency-key: demo-digital-bank-product-${NAME// /-}" \
        -d "$BODY")
      PRODUCT=$(jq -r '.["product-id"] // empty' <<<"$RES")
      if [[ -z "$PRODUCT" ]]; then
        echo "product $NAME refused: $RES" >&2
        exit 1
      fi
      VERSION=$(jq -r '.["version-id"]' <<<"$RES")
      RES=$(curl -sS -X POST "{{ DEMO_API_URL }}/v1/cash-account-products/$PRODUCT/versions/$VERSION/publish" \
        -H "authorization: Bearer $TOKEN")
      if [[ "$(jq -r '.status // empty' <<<"$RES")" != published ]]; then
        echo "publishing $NAME refused: $RES" >&2
        exit 1
      fi
      printf '  %-14s %s, %s bps\n' "$NAME" "$PRODUCT" "$BPS"
    done
