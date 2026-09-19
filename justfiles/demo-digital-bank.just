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
# Where the platform delivers to: the bank's receiver, on the developer's
# own machine, which the monolith's dev profile allows an endpoint to be.
DEMO_WEBHOOK_URL := env_var_or_default("DEMO_WEBHOOK_URL", "http://localhost:8100/webhooks")

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

# Run the bank's backend against a local monolith, its credential from pass and its Postgres in a container, teeing the log to demo-digital-bank.log (port 8100)
demo-digital-bank-start:
    #!/usr/bin/env zsh
    set -e
    cd {{ justfile_directory() }}
    if ! OUT=$(pass show {{ DEMO_PASS }}/ids/demo-digital-bank 2>/dev/null) || [[ -z "$OUT" ]]; then
      echo "no credential under {{ DEMO_PASS }}/demo-digital-bank: run just demo-digital-bank-seed first" >&2
      exit 1
    fi
    export DEMO_DIGITAL_BANK_CLIENT_ID="${OUT%%$'\n'*}"
    OUT=$(pass show {{ DEMO_PASS }}/demo-digital-bank)
    export DEMO_DIGITAL_BANK_CLIENT_SECRET="${OUT%%$'\n'*}"
    if ! OUT=$(pass show {{ DEMO_PASS }}/demo-digital-bank-webhook 2>/dev/null) || [[ -z "$OUT" ]]; then
      echo "no webhook secret under {{ DEMO_PASS }}/demo-digital-bank-webhook: run just demo-digital-bank-seed again" >&2
      exit 1
    fi
    export DEMO_DIGITAL_BANK_WEBHOOK_SECRET="${OUT%%$'\n'*}"
    export DEMO_API_URL={{ DEMO_API_URL }}
    clj -M{{ DOMAIN_ALIASES }}:dev:test -e "(require 'com.repldriven.mono.testcontainers.interface)" -m com.repldriven.queenswood.demo-digital-bank-api.main -c "classpath:demo-digital-bank-api/application-test.yml" -p "dev" 2>&1 | tee demo-digital-bank.log

# Create the bank's organisation on the local monolith, store its credential in pass, publish its three products and register its webhook endpoint with the secret in pass; does only what is not already done, and replaces a credential the platform no longer knows.
demo-digital-bank-seed:
    #!/usr/bin/env zsh
    set -e
    CLIENT_ID=""
    if OUT=$(pass show {{ DEMO_PASS }}/ids/demo-digital-bank 2>/dev/null) && [[ -n "$OUT" ]]; then
      CLIENT_ID="${OUT%%$'\n'*}"
      OUT=$(pass show {{ DEMO_PASS }}/demo-digital-bank)
      CLIENT_SECRET="${OUT%%$'\n'*}"
      if TOKEN=$(just _demo-digital-bank-token "$CLIENT_ID" "$CLIENT_SECRET" queenswood-api-test 2>/dev/null); then
        echo "organisation $CLIENT_ID, from pass"
      else
        echo "organisation $CLIENT_ID in pass is unknown to the platform; creating it again"
        CLIENT_ID=""
      fi
    fi
    if [[ -z "$CLIENT_ID" ]]; then
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
      TOKEN=$(just _demo-digital-bank-token "$CLIENT_ID" "$CLIENT_SECRET" queenswood-api-test)
    fi
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
    RES=$(curl -sS {{ DEMO_API_URL }}/v1/webhook-endpoints -H "authorization: Bearer $TOKEN")
    ENDPOINT=$(jq -r --arg a "{{ DEMO_WEBHOOK_URL }}" \
      '[.items[] | select(.address == $a and .status != "removed")][0]["endpoint-id"] // empty' <<<"$RES")
    SECRET=""
    if [[ -n "$ENDPOINT" ]] && OUT=$(pass show {{ DEMO_PASS }}/demo-digital-bank-webhook 2>/dev/null) && [[ -n "$OUT" ]]; then
      echo "webhook $ENDPOINT at {{ DEMO_WEBHOOK_URL }}, secret from pass"
    elif [[ -n "$ENDPOINT" ]]; then
      RES=$(curl -sS -X POST "{{ DEMO_API_URL }}/v1/webhook-endpoints/$ENDPOINT/rotate-secret" \
        -H "authorization: Bearer $TOKEN" \
        -H "idempotency-key: demo-digital-bank-webhook-rotate-$(date +%s)")
      SECRET=$(jq -r '.secret // empty' <<<"$RES")
      if [[ -z "$SECRET" ]]; then
        echo "webhook secret rotation refused: $RES" >&2
        exit 1
      fi
      echo "webhook $ENDPOINT at {{ DEMO_WEBHOOK_URL }}, secret rotated: pass held none"
    else
      RES=$(curl -sS -X POST {{ DEMO_API_URL }}/v1/webhook-endpoints \
        -H "authorization: Bearer $TOKEN" \
        -H 'content-type: application/json' \
        -H "idempotency-key: demo-digital-bank-webhook-$(date +%s)" \
        -d '{"address":"{{ DEMO_WEBHOOK_URL }}","description":"The demo digital bank"}')
      ENDPOINT=$(jq -r '.endpoint["endpoint-id"] // empty' <<<"$RES")
      SECRET=$(jq -r '.secret // empty' <<<"$RES")
      if [[ -z "$ENDPOINT" || -z "$SECRET" ]]; then
        echo "webhook refused: $RES" >&2
        exit 1
      fi
      echo "webhook $ENDPOINT at {{ DEMO_WEBHOOK_URL }}, registered"
    fi
    if [[ -n "$SECRET" ]]; then
      printf '%s\n' "$SECRET" | pass insert -m -f {{ DEMO_PASS }}/demo-digital-bank-webhook >/dev/null
    fi

# Pay money into one of the bank's accounts from outside, as the bank, through the platform's sandbox affordance. account_id: the platform's; amount: in pence.
demo-digital-bank-fund account_id amount:
    #!/usr/bin/env zsh
    set -e
    if ! OUT=$(pass show {{ DEMO_PASS }}/ids/demo-digital-bank 2>/dev/null) || [[ -z "$OUT" ]]; then
      echo "no credential under {{ DEMO_PASS }}/demo-digital-bank: run just demo-digital-bank-seed first" >&2
      exit 1
    fi
    CLIENT_ID="${OUT%%$'\n'*}"
    OUT=$(pass show {{ DEMO_PASS }}/demo-digital-bank)
    CLIENT_SECRET="${OUT%%$'\n'*}"
    TOKEN=$(just _demo-digital-bank-token "$CLIENT_ID" "$CLIENT_SECRET" queenswood-api-test)
    RES=$(curl -sS -X POST "{{ DEMO_API_URL }}/v1/simulate/banks/$CLIENT_ID/inbound-transfer" \
      -H "authorization: Bearer $TOKEN" \
      -H 'content-type: application/json' \
      -H "idempotency-key: demo-digital-bank-fund-$(date +%s%N)" \
      -d '{"account-id":"{{ account_id }}","amount":{{ amount }},"currency":"GBP"}')
    if [[ "$(jq -r '.status // empty' <<<"$RES")" != posted ]]; then
      echo "funding refused: $RES" >&2
      exit 1
    fi
    echo "{{ amount }} pence into {{ account_id }}: $(jq -r '."transaction-id"' <<<"$RES")"
