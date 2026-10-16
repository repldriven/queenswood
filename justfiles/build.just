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

# Lint the OpenAPI document at `path` with Spectral, exporting the working tree's first when `path` is empty
openapi-lint path="":
    #!/usr/bin/env bash
    set -euo pipefail
    spec="{{ path }}"
    if [[ -z "$spec" ]]; then
      work=$(mktemp -d)
      trap 'rm -rf "$work"' EXIT
      spec="$work/openapi.yaml"
      just export-openapi "$spec" >/dev/null
    fi
    spectral lint --ruleset .config/spectral/spectral.yaml "$spec"

# Regenerate Spectral's OpenAPI 3.2 schema rule from the vendored schema
openapi-lint-ruleset:
    #!/usr/bin/env bash
    set -euo pipefail
    schema=.config/spectral/oas-3.2-schema-2026-08-30.json
    jq '(.. | objects | select(.["$dynamicRef"] == "#meta"))
          |= (del(.["$dynamicRef"]) + {"$ref": "#/$defs/schema"})
        | {rules: {"oas3_2-schema": {
            description: "The document validates against the OpenAPI 3.2 schema.",
            message: "{{{{error}}",
            severity: "error",
            resolved: false,
            given: "$",
            then: {function: "schema",
                   functionOptions: {dialect: "draft2020-12",
                                     allErrors: true,
                                     schema: .}}}}}' \
      "$schema" > .config/spectral/oas-3.2.json

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
    # `diff` exits 1 when the files differ, which is this recipe's whole
    # point, so `|| true` keeps set -e from reporting a found diff as a
    # recipe failure.
    diff -u --label "{{ ref }}" --label "working tree" \
      "$work/ref.yaml" "$work/working.yaml" || true

# Copy Fideslang's data categories at tag (3.1.4) into the schema brick, then check every proto's keys against them
fideslang-update tag:
    #!/usr/bin/env bash
    set -euo pipefail
    sha=$(gh api "repos/ethyca/fideslang/commits/{{ tag }}" --jq .sha)
    upstream=$(gh api "repos/ethyca/fideslang/contents/data_files/data_categories.json?ref=$sha" --jq .content | base64 -d)
    jq -n --arg tag "{{ tag }}" --arg sha "$sha" --argjson upstream "$upstream" '
      {source: "https://github.com/ethyca/fideslang/blob/\($sha)/data_files/data_categories.json",
       license: "CC-BY-4.0, Ethyca Inc.",
       tag: $tag,
       commit: $sha,
       data_category: [$upstream.data_category[]
                       | {fides_key, name, description, parent_key,
                          version_added, version_deprecated, replaced_by}]}' \
      > components/schema/resources/fideslang/data-categories.json
    clojure -M:poly test brick:schema project:dev :all

# Copy England and Wales's bank holidays from GOV.UK into the GBLO calendar's data
calendars-update:
    #!/usr/bin/env bash
    set -euo pipefail
    upstream=$(curl -fsSL https://www.gov.uk/bank-holidays.json)
    mkdir -p components/resources/resources/calendars
    jq --arg fetched "$(date -u +%F)" '
      {source: "https://www.gov.uk/bank-holidays.json",
       division: "england-and-wales",
       "fetched-on": $fetched,
       holidays: [.["england-and-wales"].events[] | {date, title}]}' \
      <<<"$upstream" > components/resources/resources/calendars/gblo.json

# Force re-run code generation for the bank profile
force-prep:
    clj -X:deps prep :aliases '[{{ DOMAIN_ALIASES }} :dev]' :force true
