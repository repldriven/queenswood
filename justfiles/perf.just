# Load tests run by k6 as a Job inside the cluster kubectl points at. See
# docs/tdd/performance-testing.md.

PERF_NAMESPACE := "queenswood"

# Run one scenario in the cluster and write its summary to target/perf. scenario: a script under perf/ (internal, outbound or inbound); profile: smoke or challenger (smoke); rate, duration, accounts: overrides, the profile's when empty.
perf-run scenario="internal" profile="smoke" rate="" duration="" accounts="":
    #!/usr/bin/env zsh
    set -euo pipefail
    cd {{ justfile_directory() }}
    STAMP=$(date -u +%Y%m%dT%H%M%SZ)
    NAME={{ scenario }}-{{ profile }}-$STAMP
    mkdir -p target/perf
    k6 archive --quiet \
      -e PROFILE={{ profile }} -e RATE={{ rate }} \
      -e DURATION={{ duration }} -e ACCOUNTS={{ accounts }} \
      -O target/perf/$NAME.tar perf/{{ scenario }}.js
    VERSION=$(k6 version | awk '{print $2}')
    export K6_IMAGE=grafana/k6:${VERSION#v}
    kubectl -n {{ PERF_NAMESPACE }} delete job queenswood-perf --ignore-not-found --wait
    kubectl -n {{ PERF_NAMESPACE }} create configmap queenswood-perf-scripts \
      --from-file=run.tar=target/perf/$NAME.tar --dry-run=client -o yaml \
      | kubectl apply -f -
    if ! kubectl -n {{ PERF_NAMESPACE }} get secret queenswood-keycloak-dev-operator >/dev/null 2>&1; then
      echo "no queenswood-keycloak-dev-operator Secret: the chart was installed without values-local.yaml's operator client" >&2
      exit 1
    fi
    envsubst '$K6_IMAGE' < infra/perf/k6-job.yaml | kubectl apply -f -
    echo "running $NAME on $K6_IMAGE"
    kubectl -n {{ PERF_NAMESPACE }} logs -f job/queenswood-perf \
      --pod-running-timeout=5m > target/perf/$NAME.log || true
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
    PAYMENTS=$(jq '.headline.payments' target/perf/$NAME.json)
    if [[ "$PAYMENTS" == "0" ]]; then
      echo "no payments sent, log in target/perf/$NAME.log" >&2
      exit 1
    fi
    HELD=$(jq '.headline.books.held' target/perf/$NAME.json)
    if [[ "$HELD" == "null" ]]; then
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

# Delete the load test's Job and scripts from the cluster.
perf-clean:
    kubectl -n {{ PERF_NAMESPACE }} delete job queenswood-perf --ignore-not-found
    kubectl -n {{ PERF_NAMESPACE }} delete configmap queenswood-perf-scripts --ignore-not-found
