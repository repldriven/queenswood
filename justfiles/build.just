# Build all polylith projects as uberjars
build snapshot="true":
    #!/usr/bin/env zsh
    for project in projects/*/; do
        echo "Building ${project:t}..."
        (cd "$project" && clojure -X:build uber :snapshot {{ snapshot }})
    done

# Export the OpenAPI spec to the given path
export-openapi path="docs/openapi.yaml":
    clojure -Sdeps '{:aliases {:dev {:main-opts []}}}' -M{{ DOMAIN_ALIASES }}:dev -m com.repldriven.queenswood.api.export-spec {{ path }}

# Diff the OpenAPI document the working tree exports against `ref`'s.
openapi-diff ref:
    #!/usr/bin/env bash
    set -euo pipefail
    work=$(mktemp -d)
    tree="$work/tree"
    trap 'git worktree remove --force "$tree" >/dev/null 2>&1 || true
          rm -rf "$work"' EXIT
    git worktree add --detach "$tree" "{{ ref }}" >/dev/null
    (cd "$tree" && just force-prep >/dev/null)
    (cd "$tree" && just export-openapi "$work/ref.yaml" >/dev/null)
    just export-openapi "$work/working.yaml" >/dev/null
    diff -u --label "{{ ref }}" --label "working tree" \
      "$work/ref.yaml" "$work/working.yaml"

# Force re-run code generation for the bank profile
force-prep:
    clj -X:deps prep :aliases '[{{ DOMAIN_ALIASES }} :dev]' :force true
