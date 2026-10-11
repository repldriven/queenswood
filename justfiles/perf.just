# Load tests run by k6 as a Job inside the cluster kubectl points at. See
# docs/tdd/performance-testing.md.

PERF_NAMESPACE := "queenswood"

# The Secret the Job reads its operator client from: the dev realm's on
# kind, the one `perf-run-instance` writes for its run on an instance.
PERF_OPERATOR_SECRET := env_var_or_default("PERF_OPERATOR_SECRET", "queenswood-keycloak-dev-operator")

# The node selector the Job carries, as a YAML flow mapping: empty on
# kind, the instance's load pool where `perf-run-instance` finds one.
PERF_NODE_SELECTOR := env_var_or_default("PERF_NODE_SELECTOR", "{}")
PERF_LOAD_POOL := "platform.repldriven.com/pool=load"

PERF_INSTANCE_SECRET := "queenswood-perf-operator"
PERF_INSTANCE_CLIENT := "queenswood-perf"
PERF_REALM := "queenswood"
PERF_KEYCLOAK_SERVICE := "queenswood-keycloak-service"
PERF_KEYCLOAK_ADMIN_SECRET := "queenswood-keycloak-bootstrap-admin"
PERF_KEYCLOAK_PORT := "18090"

# Create or delete the load test's operator client in the instance's realm, through a port-forward to its Keycloak signed in as the bootstrap admin, and the Secret the Job reads it from. context: the instance's kubectl context; action: up or down.
_perf-operator context action:
    #!/usr/bin/env zsh
    set -euo pipefail
    K=(kubectl --context "{{ context }}" -n {{ PERF_NAMESPACE }})
    if ! USER64=$("${K[@]}" get secret {{ PERF_KEYCLOAK_ADMIN_SECRET }} -o jsonpath='{.data.username}'); then
      echo "cannot read {{ PERF_KEYCLOAK_ADMIN_SECRET }} in {{ context }}" >&2
      exit 1
    fi
    PASS64=$("${K[@]}" get secret {{ PERF_KEYCLOAK_ADMIN_SECRET }} -o jsonpath='{.data.password}')
    ADMIN_USER=$(printf '%s' "$USER64" | base64 -d)
    ADMIN_PASS=$(printf '%s' "$PASS64" | base64 -d)
    "${K[@]}" port-forward svc/{{ PERF_KEYCLOAK_SERVICE }} {{ PERF_KEYCLOAK_PORT }}:8080 >/dev/null 2>&1 &
    PF=$!
    trap 'kill $PF 2>/dev/null || true' EXIT
    KC=http://localhost:{{ PERF_KEYCLOAK_PORT }}
    for i in $(seq 1 30); do
      if curl -sf -o /dev/null "$KC/realms/master"; then break; fi
      if ! kill -0 $PF 2>/dev/null; then
        echo "the port-forward to {{ PERF_KEYCLOAK_SERVICE }} ended; is port {{ PERF_KEYCLOAK_PORT }} free?" >&2
        exit 1
      fi
      sleep 1
    done
    OUT=$(curl -s -d client_id=admin-cli -d "username=$ADMIN_USER" \
      --data-urlencode "password=$ADMIN_PASS" -d grant_type=password \
      "$KC/realms/master/protocol/openid-connect/token" || true)
    TOKEN=$(printf '%s' "$OUT" | jq -r '.access_token // empty' 2>/dev/null || true)
    if [[ -z "$TOKEN" ]]; then
      echo "Keycloak gave the bootstrap admin no token: $OUT" >&2
      exit 1
    fi
    AUTH=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")
    ADMIN=$KC/admin/realms/{{ PERF_REALM }}
    api() {
      local out
      if ! out=$(curl -sS -f "${AUTH[@]}" "$@" 2>&1); then
        echo "Keycloak's admin API refused $*: $out" >&2
        return 1
      fi
      printf '%s' "$out"
    }
    OUT=$(api "$ADMIN/clients?clientId={{ PERF_INSTANCE_CLIENT }}")
    ID=$(printf '%s' "$OUT" | jq -r '.[0].id // empty')
    if [[ -n "$ID" ]]; then
      api -X DELETE "$ADMIN/clients/$ID" >/dev/null
      echo "deleted the {{ PERF_INSTANCE_CLIENT }} client"
    fi
    if [[ "{{ action }}" == "down" ]]; then
      "${K[@]}" delete secret {{ PERF_INSTANCE_SECRET }} --ignore-not-found
      exit 0
    fi
    if [[ "{{ action }}" != "up" ]]; then
      echo "action is up or down, not {{ action }}" >&2
      exit 1
    fi
    CLIENT=$(jq -n --arg id {{ PERF_INSTANCE_CLIENT }} '{
      clientId: $id, enabled: true, publicClient: false,
      clientAuthenticatorType: "client-secret", serviceAccountsEnabled: true,
      standardFlowEnabled: false, directAccessGrantsEnabled: false,
      implicitFlowEnabled: false, protocol: "openid-connect",
      optionalClientScopes: ["queenswood-api-test", "queenswood-api-live", "realm-roles"]}')
    api -X POST -d "$CLIENT" "$ADMIN/clients" >/dev/null
    OUT=$(api "$ADMIN/clients?clientId={{ PERF_INSTANCE_CLIENT }}")
    ID=$(printf '%s' "$OUT" | jq -r '.[0].id')
    OUT=$(api "$ADMIN/clients/$ID/client-secret")
    SECRET=$(printf '%s' "$OUT" | jq -r '.value')
    OUT=$(api "$ADMIN/clients/$ID/service-account-user")
    ACCOUNT=$(printf '%s' "$OUT" | jq -r '.id')
    ROLE=$(api "$ADMIN/roles/admin")
    api -X POST -d "[$ROLE]" "$ADMIN/users/$ACCOUNT/role-mappings/realm" >/dev/null
    "${K[@]}" create secret generic {{ PERF_INSTANCE_SECRET }} \
      --from-literal=client-id={{ PERF_INSTANCE_CLIENT }} \
      --from-literal=client-secret="$SECRET" --dry-run=client -o yaml \
      | "${K[@]}" apply -f - >/dev/null
    echo "created the {{ PERF_INSTANCE_CLIENT }} client, holding admin, and {{ PERF_INSTANCE_SECRET }}"

# Run one scenario in the cluster and write its summary to target/perf. scenario: a script under perf/ (internal, outbound, inbound, mixed, parties, accounts, reads or interest); profile: smoke, challenger or knee, as the scenario defines them (smoke); rate, duration, accounts: overrides, the profile's when empty; from: the lowest step rate kept, every step when empty; step: each step's length, as 30s or 1m, the profile's when empty.
perf-run scenario="internal" profile="smoke" rate="" duration="" accounts="" from="" step="":
    #!/usr/bin/env zsh
    set -euo pipefail
    cd {{ justfile_directory() }}
    STAMP=$(date -u +%Y%m%dT%H%M%SZ)
    NAME={{ scenario }}-{{ profile }}-$STAMP
    mkdir -p target/perf
    k6 archive --quiet \
      -e PROFILE={{ profile }} -e RATE={{ rate }} \
      -e DURATION={{ duration }} -e ACCOUNTS={{ accounts }} -e FROM={{ from }} \
      -e STEP={{ step }} \
      -O target/perf/$NAME.tar perf/{{ scenario }}.js
    VERSION=$(k6 version | awk '{print $2}')
    export K6_IMAGE=grafana/k6:${VERSION#v}
    kubectl -n {{ PERF_NAMESPACE }} delete job queenswood-perf --ignore-not-found --wait
    kubectl -n {{ PERF_NAMESPACE }} create configmap queenswood-perf-scripts \
      --from-file=run.tar=target/perf/$NAME.tar --dry-run=client -o yaml \
      | kubectl apply -f -
    if ! kubectl -n {{ PERF_NAMESPACE }} get secret {{ PERF_OPERATOR_SECRET }} >/dev/null 2>&1; then
      echo "no {{ PERF_OPERATOR_SECRET }} Secret: on kind, the chart was installed without values-local.yaml's operator client; on an instance, run perf-run-instance" >&2
      exit 1
    fi
    export PERF_OPERATOR_SECRET={{ PERF_OPERATOR_SECRET }}
    export PERF_NODE_SELECTOR='{{ PERF_NODE_SELECTOR }}'
    envsubst '$K6_IMAGE $PERF_OPERATOR_SECRET $PERF_NODE_SELECTOR' < infra/perf/k6-job.yaml | kubectl apply -f -
    echo "running $NAME on $K6_IMAGE"
    while true; do
      kubectl -n {{ PERF_NAMESPACE }} logs -f job/queenswood-perf \
        --pod-running-timeout=5m > target/perf/$NAME.log || true
      ENDED=$(kubectl -n {{ PERF_NAMESPACE }} get job queenswood-perf \
        -o jsonpath='{.status.succeeded}{.status.failed}')
      if [[ -n "$ENDED" ]]; then break; fi
      sleep 5
    done
    POD=$(kubectl -n {{ PERF_NAMESPACE }} get pods -l job-name=queenswood-perf \
      -o jsonpath='{.items[0].metadata.name}')
    EXIT=""
    for i in $(seq 1 30); do
      EXIT=$(kubectl -n {{ PERF_NAMESPACE }} get pod $POD \
        -o jsonpath='{.status.containerStatuses[0].state.terminated.exitCode}')
      if [[ -n "$EXIT" ]]; then break; fi
      sleep 1
    done
    awk '/==== perf summary end ====/{f=0} f; /==== perf summary begin ====/{f=1}' \
      target/perf/$NAME.log > target/perf/$NAME.json
    if [[ ! -s target/perf/$NAME.json ]]; then
      tail -40 target/perf/$NAME.log
      echo "no summary: k6 exited $EXIT, log in target/perf/$NAME.log" >&2
      exit 1
    fi
    jq '{run: .run, headline: .headline}' target/perf/$NAME.json
    UNIT=$(jq -r '.run.unit // "payments"' target/perf/$NAME.json)
    SENT=$(jq --arg u "$UNIT" '.headline[$u] // 0' target/perf/$NAME.json)
    if [[ "$SENT" == "0" ]]; then
      echo "no $UNIT sent, log in target/perf/$NAME.log" >&2
      exit 1
    fi
    BOOKS=$(jq '.run.books != false' target/perf/$NAME.json)
    HELD=$(jq '.headline.books.held' target/perf/$NAME.json)
    if [[ "$BOOKS" == "true" && "$HELD" == "null" ]]; then
      echo "the books were not checked, log in target/perf/$NAME.log" >&2
      exit 1
    fi
    if [[ "$HELD" == "false" ]]; then
      echo "books did not hold" >&2
      exit 1
    fi
    echo "summary in target/perf/$NAME.json, log in target/perf/$NAME.log"
    if [[ "$EXIT" == "99" ]]; then
      echo "thresholds not met"
    elif [[ "$EXIT" != "0" ]]; then
      echo "k6 exited $EXIT" >&2
      exit 1
    fi

# Run one scenario on an instance as perf-run does on kind, on the instance's load pool where it has one, under an operator client that exists only for the run: created before it and deleted after it, however it ends. context: the instance's kubectl context, as queenswood-instance-ctx names it; the rest: as perf-run.
perf-run-instance context scenario="internal" profile="smoke" rate="" duration="" accounts="" from="" step="":
    #!/usr/bin/env zsh
    set -euo pipefail
    cd {{ justfile_directory() }}
    CONFIG=$(mktemp)
    trap 'just _perf-operator "{{ context }}" down; rm -f "$CONFIG"' EXIT
    if ! kubectl config view --minify --flatten --context "{{ context }}" > "$CONFIG"; then
      echo "no kubectl context {{ context }}" >&2
      exit 1
    fi
    if ! NODES=$(kubectl --context "{{ context }}" get nodes -l {{ PERF_LOAD_POOL }} -o name); then
      echo "cannot list the nodes of {{ context }}" >&2
      exit 1
    fi
    SELECTOR='{}'
    if [[ -n "$NODES" ]]; then
      SELECTOR='{"{{ replace(PERF_LOAD_POOL, "=", "\": \"") }}"}'
    else
      echo "no node labelled {{ PERF_LOAD_POOL }}: k6 runs beside the services, and the run's figures are not publishable" >&2
    fi
    just _perf-operator "{{ context }}" up
    KUBECONFIG="$CONFIG" PERF_OPERATOR_SECRET={{ PERF_INSTANCE_SECRET }} \
      PERF_NODE_SELECTOR="$SELECTOR" \
      just perf-run "{{ scenario }}" "{{ profile }}" "{{ rate }}" "{{ duration }}" \
      "{{ accounts }}" "{{ from }}" "{{ step }}"

# Delete the load test's Job and scripts from the cluster.
perf-clean:
    kubectl -n {{ PERF_NAMESPACE }} delete job queenswood-perf --ignore-not-found
    kubectl -n {{ PERF_NAMESPACE }} delete configmap queenswood-perf-scripts --ignore-not-found

# Delete the load test's operator client, its Secret, its Job and its scripts from an instance, for a run that ended before perf-run-instance could. context: the instance's kubectl context.
perf-clean-instance context:
    just _perf-operator "{{ context }}" down
    kubectl --context "{{ context }}" -n {{ PERF_NAMESPACE }} delete job queenswood-perf --ignore-not-found
    kubectl --context "{{ context }}" -n {{ PERF_NAMESPACE }} delete configmap queenswood-perf-scripts --ignore-not-found
