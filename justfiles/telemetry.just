FOUNDRY_VERSION := "0.3.0"

# Download foundryctl at FOUNDRY_VERSION (0.3.0) into target/foundry, checked against the release's checksums
[private]
telemetry-foundryctl:
    #!/usr/bin/env zsh
    set -euo pipefail
    cd {{ justfile_directory() }}
    DIR=target/foundry/{{ FOUNDRY_VERSION }}
    if [[ -x $DIR/foundryctl ]]; then exit 0; fi
    OS=$(uname -s | tr '[:upper:]' '[:lower:]')
    ARCH=$(uname -m | sed -e s/x86_64/amd64/ -e s/aarch64/arm64/)
    ASSET=foundry_${OS}_${ARCH}.tar.gz
    URL=https://github.com/SigNoz/foundry/releases/download/v{{ FOUNDRY_VERSION }}
    TMP=$(mktemp -d)
    curl -fsSL -o "$TMP/$ASSET" "$URL/$ASSET"
    curl -fsSL -o "$TMP/sums.txt" "$URL/foundry_{{ FOUNDRY_VERSION }}_checksums.txt"
    (cd "$TMP" && grep " $ASSET\$" sums.txt | shasum -a 256 -c -)
    tar xzf "$TMP/$ASSET" -C "$TMP"
    mkdir -p "$DIR"
    mv "$TMP/foundry_${OS}_${ARCH}/bin/foundryctl" "$DIR/foundryctl"

# Start SigNoz for the monolith loop in Docker, cast from infra/signoz/casting.yaml into target/signoz: OTLP on localhost:4318, UI on http://localhost:3301
telemetry-start: telemetry-foundryctl
    #!/usr/bin/env zsh
    set -euo pipefail
    cd {{ justfile_directory() }}
    mkdir -p target/signoz
    cp infra/signoz/casting.yaml target/signoz/casting.yaml
    cd target/signoz
    ../foundry/{{ FOUNDRY_VERSION }}/foundryctl cast -f casting.yaml

# Stop the monolith loop's SigNoz, keeping its volumes and so its spans
telemetry-stop:
    cd {{ justfile_directory() }} && docker compose -f target/signoz/pours/deployment/compose.yaml down

# Port-forward the in-cluster SigNoz UI to http://localhost:3301
telemetry-ui:
    kubectl -n queenswood port-forward svc/queenswood-signoz 3301:8080
