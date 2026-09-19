# Delete orphan container versions from GHCR (multi-arch safe)
ghcr-prune-untagged:
    #!/usr/bin/env zsh
    set -e
    scopes=$(gh api --include / 2>&1 | awk -F': ' 'tolower($1)=="x-oauth-scopes"{print $2}')
    if [[ "$scopes" != *delete:packages* ]]; then
      echo "gh token missing delete:packages. Run:"
      echo "  gh auth refresh -h github.com -s delete:packages,read:packages"
      exit 1
    fi
    OWNER=$(gh repo view --json owner --jq '.owner.login')
    TYPE=$(gh api "/users/$OWNER" --jq '.type' 2>/dev/null || echo "User")
    if [[ $TYPE == "Organization" ]]; then
      DELETE_BASE="/orgs/$OWNER/packages/container"
    else
      DELETE_BASE="/user/packages/container"
    fi
    gh api "/users/$OWNER/packages?package_type=container" --paginate \
        --jq '.[].name' | while read -r pkg; do
      # Per-platform manifests referenced by a tagged index are "untagged"
      # in GHCR; deleting them breaks the index. Build a keep set first.
      # The registry path takes the name as-is; the REST API needs it
      # percent-encoded, or a namespaced package 404s as "not found".
      enc="${pkg//\//%2F}"
      regtoken=$(curl -sL "https://ghcr.io/token?service=ghcr.io&scope=repository:$OWNER/$pkg:pull" | jq -r .token)
      keep=$(gh api "/users/$OWNER/packages/container/$enc/versions" --paginate \
              --jq '.[] | select(.metadata.container.tags != []) | .name' \
              | while read -r digest; do
                  curl -sL -H "Authorization: Bearer $regtoken" \
                    -H "Accept: application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json" \
                    "https://ghcr.io/v2/$OWNER/$pkg/manifests/$digest" \
                    | jq -r '.manifests[]?.digest // empty'
                done | sort -u)
      to_delete=$(gh api "/users/$OWNER/packages/container/$enc/versions" --paginate \
                    --jq '.[] | select(.metadata.container.tags == []) | "\(.id) \(.name)"' \
                  | while read -r id name; do
                      echo "$keep" | grep -qFx "$name" || echo "$id"
                    done)
      n=$(echo -n "$to_delete" | grep -c '^' || true)
      if [[ $n -eq 0 ]]; then
        printf "  %-45s 0\n" "$pkg"
        continue
      fi
      printf "  %-45s %s" "$pkg" "$n"
      for id in ${(f)to_delete}; do
        gh api -X DELETE "$DELETE_BASE/$enc/versions/$id" --silent || true
        printf "."
      done
      printf "\n"
    done
