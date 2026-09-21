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
# The bank's owner in the console: a user the local realm seeds beside
# dev, invited as the organisation is created and accepting as itself.
# The console's public client allows the password grant locally, which
# is what lets the seed sign in as the owner without a browser.
DEMO_KEYCLOAK_URL := env_var_or_default("DEMO_KEYCLOAK_URL", "http://localhost:8090")
DEMO_REALM := "queenswood"
DEMO_CONSOLE_CLIENT_ID := "queenswood-console"
DEMO_OWNER_EMAIL := "xepha@queenswood.local"
DEMO_OWNER_USERNAME := "xepha"
DEMO_OWNER_PASSWORD := "xepha"
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

# Sign a local realm user in through the console's public client, answering a bearer token. username, password: the user's.
_demo-digital-bank-user-token username password:
    #!/usr/bin/env zsh
    set -e
    RES=$(curl -sS -X POST {{ DEMO_KEYCLOAK_URL }}/realms/{{ DEMO_REALM }}/protocol/openid-connect/token \
      -d grant_type=password \
      -d "client_id={{ DEMO_CONSOLE_CLIENT_ID }}" \
      -d "username={{ username }}" \
      -d "password={{ password }}" \
      -d scope=openid)
    TOKEN=$(jq -r '.access_token // empty' <<<"$RES")
    if [[ -z "$TOKEN" ]]; then
      echo "sign-in refused for {{ username }}: $RES" >&2
      exit 1
    fi
    echo "$TOKEN"

# Create the bank's organisation on the local monolith with its owner invited and store its credential in pass, reusing what pass holds while the platform still knows it; the organisation's client id is the last line of stdout.
_demo-digital-bank-organisation:
    #!/usr/bin/env zsh
    set -e
    CLIENT_ID=""
    if OUT=$(pass show {{ DEMO_PASS }}/ids/demo-digital-bank 2>/dev/null) && [[ -n "$OUT" ]]; then
      CLIENT_ID="${OUT%%$'\n'*}"
      OUT=$(pass show {{ DEMO_PASS }}/demo-digital-bank)
      CLIENT_SECRET="${OUT%%$'\n'*}"
      if TOKEN=$(just _demo-digital-bank-token "$CLIENT_ID" "$CLIENT_SECRET" queenswood-api-test 2>/dev/null); then
        echo "organisation $CLIENT_ID, from pass" >&2
      else
        echo "organisation $CLIENT_ID in pass is unknown to the platform; creating it again" >&2
        CLIENT_ID=""
      fi
    fi
    if [[ -z "$CLIENT_ID" ]]; then
      ADMIN=$(just _demo-digital-bank-token {{ DEMO_ADMIN_CLIENT_ID }} {{ DEMO_ADMIN_CLIENT_SECRET }} queenswood-api-test+realm-roles)
      RES=$(curl -sS -X POST {{ DEMO_API_URL }}/v1/banks \
        -H "authorization: Bearer $ADMIN" \
        -H 'content-type: application/json' \
        -H "idempotency-key: demo-digital-bank-seed-$(date +%s)" \
        -d '{"name":"{{ DEMO_BANK_NAME }}","status":"test","tier":"micro","currencies":["GBP"],"owner-email":"{{ DEMO_OWNER_EMAIL }}"}')
      CLIENT_ID=$(jq -r '.["client-id"] // empty' <<<"$RES")
      if [[ -z "$CLIENT_ID" ]]; then
        echo "organisation refused: $RES" >&2
        exit 1
      fi
      CLIENT_SECRET=$(jq -r '.["client-secret"]' <<<"$RES")
      printf '%s\n' "$CLIENT_ID" | pass insert -m -f {{ DEMO_PASS }}/ids/demo-digital-bank >/dev/null
      printf '%s\n' "$CLIENT_SECRET" | pass insert -m -f {{ DEMO_PASS }}/demo-digital-bank >/dev/null
      echo "organisation $CLIENT_ID, created: test, micro, GBP, sort code $(jq -r '.["sort-code"]' <<<"$RES")" >&2
    fi
    echo "$CLIENT_ID"

# Accept the owner's invitation to the organisation as the owner, signing in with the password grant. client_id: the organisation's, which is its bank id.
_demo-digital-bank-owner-accept client_id:
    #!/usr/bin/env zsh
    set -e
    OWNER=$(just _demo-digital-bank-user-token {{ DEMO_OWNER_USERNAME }} {{ DEMO_OWNER_PASSWORD }})
    RES=$(curl -sS {{ DEMO_API_URL }}/v1/me -H "authorization: Bearer $OWNER")
    if [[ -n "$(jq -r --arg b "{{ client_id }}" '.memberships[] | select(."bank-id" == $b) | ."membership-id"' <<<"$RES")" ]]; then
      echo "owner {{ DEMO_OWNER_EMAIL }} already a member"
    else
      RES=$(curl -sS {{ DEMO_API_URL }}/v1/me/invitations -H "authorization: Bearer $OWNER")
      INVITATION=$(jq -r --arg b "{{ client_id }}" '[.items[] | select(."bank-id" == $b)][0]["invitation-id"] // empty' <<<"$RES")
      if [[ -z "$INVITATION" ]]; then
        echo "no invitation to {{ client_id }} for {{ DEMO_OWNER_EMAIL }}: the organisation predates its owner, so restart the monolith and seed again" >&2
        exit 1
      fi
      RES=$(curl -sS -X POST "{{ DEMO_API_URL }}/v1/me/invitations/$INVITATION/accept" \
        -H "authorization: Bearer $OWNER")
      if [[ "$(jq -r '.role // empty' <<<"$RES")" != owner ]]; then
        echo "accepting $INVITATION refused: $RES" >&2
        exit 1
      fi
      echo "owner {{ DEMO_OWNER_EMAIL }} accepted $INVITATION: sign in to the console as {{ DEMO_OWNER_USERNAME }}"
    fi

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

# Create the bank's organisation on the local monolith with its owner invited, store its credential in pass, publish its three products, fund its own funds with £10,000 once, register its webhook endpoint with the secret in pass, and accept the owner's invitation as the owner; does only what is not already done, and replaces a credential the platform no longer knows.
demo-digital-bank-seed:
    #!/usr/bin/env zsh
    set -e
    CLIENT_ID=$(just _demo-digital-bank-organisation)
    OUT=$(pass show {{ DEMO_PASS }}/demo-digital-bank)
    CLIENT_SECRET="${OUT%%$'\n'*}"
    TOKEN=$(just _demo-digital-bank-token "$CLIENT_ID" "$CLIENT_SECRET" queenswood-api-test)
    RES=$(curl -sS {{ DEMO_API_URL }}/v1/cash-account-products -H "authorization: Bearer $TOKEN")
    EXISTING=$(jq -r '.items[].versions[].name' <<<"$RES")
    TODAY=$(date +%F)
    for SPEC in "Everyday|tpl.00000000000000000000000001|0|5000" \
                "Rainy Day|tpl.00000000000000000000000002|410|0" \
                "1 Year Fixed|tpl.00000000000000000000000003|465|0"; do
      NAME=${SPEC%%|*}; REST=${SPEC#*|}; TEMPLATE=${REST%%|*}; REST=${REST#*|}; BPS=${REST%%|*}; REWARD=${REST#*|}
      if grep -qFx "$NAME" <<<"$EXISTING"; then
        printf '  %-14s already published\n' "$NAME"
        continue
      fi
      BODY=$(jq -n --arg n "$NAME" --arg t "$TEMPLATE" --argjson b "$BPS" --arg d "$TODAY" --argjson r "$REWARD" \
        '{name:$n,"template-id":$t,currency:"GBP","interest-rate-bps":$b,"effective-from":$d}
         + (if $r > 0 then {"opening-reward":{amount:$r}} else {} end)')
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
      SUFFIX=""
      if (( REWARD > 0 )); then SUFFIX=", $REWARD pence opening reward"; fi
      printf '  %-14s %s, %s bps%s\n' "$NAME" "$PRODUCT" "$BPS" "$SUFFIX"
    done
    RES=$(curl -sS -X POST "{{ DEMO_API_URL }}/v1/simulate/banks/$CLIENT_ID/inbound-transfer" \
      -H "authorization: Bearer $TOKEN" \
      -H 'content-type: application/json' \
      -H "idempotency-key: demo-digital-bank-fund-house" \
      -d '{"amount":1000000,"currency":"GBP"}')
    if [[ "$(jq -r '.status // empty' <<<"$RES")" != posted ]]; then
      echo "funding the bank refused: $RES" >&2
      exit 1
    fi
    echo "own funds $(jq -r '."account-id"' <<<"$RES") hold 1000000 pence, which pays the rewards"
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
    just _demo-digital-bank-owner-accept "$CLIENT_ID"

# Create the bank's organisation on the local monolith with its owner invited, store its credential in pass, and accept the owner's invitation as the owner, and nothing else: an empty bank to sign in to as xepha. Does only what is not already done.
demo-digital-bank-seed-bank-only:
    #!/usr/bin/env zsh
    set -e
    CLIENT_ID=$(just _demo-digital-bank-organisation)
    just _demo-digital-bank-owner-accept "$CLIENT_ID"

# Pay money into the bank's own funds from outside, as the bank, through the platform's sandbox affordance; a customer is paid from there by an internal payment. amount: in pence.
demo-digital-bank-fund amount:
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
      -d '{"amount":{{ amount }},"currency":"GBP"}')
    if [[ "$(jq -r '.status // empty' <<<"$RES")" != posted ]]; then
      echo "funding refused: $RES" >&2
      exit 1
    fi
    echo "{{ amount }} pence into own funds $(jq -r '."account-id"' <<<"$RES"): $(jq -r '."transaction-id"' <<<"$RES")"
