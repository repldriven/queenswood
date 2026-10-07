# Boolean constraints gcp-org-enforce-constraints enforces. Idempotent,
# so adding one and re-running changes only what is new.
ORG_CONSTRAINTS := "compute.skipDefaultNetworkCreation compute.disableSerialPortAccess compute.requireOsLogin"

# The organisation-scoped role bindings, keyed by capability.
ROLES_FILE := "infra/access/organisation-roles.json"

# Print the organisation id, where the caller sees exactly one.
[private]
_gcp-org-id:
    #!/usr/bin/env zsh
    set -e
    ORGS=$(gcloud organizations list --format='value(ID)' 2>/dev/null || true)
    COUNT=$(printf '%s' "$ORGS" | grep -c . || true)
    if [[ "$COUNT" -eq 0 ]]; then
      echo "no organization visible to $(gcloud config get-value account 2>/dev/null)" >&2
      echo "an organization comes from Cloud Identity, not from gcloud --" >&2
      echo "see docs/recipes/infra/organisation-foundation.md" >&2
      exit 1
    fi
    if [[ "$COUNT" -ne 1 ]]; then
      echo "$COUNT organizations visible to $(gcloud config get-value account 2>/dev/null):" >&2
      gcloud organizations list >&2
      echo "authenticate as an account that sees one" >&2
      exit 1
    fi
    printf '%s\n' "$ORGS"

# Print the organisation's primary domain.
[private]
_gcp-domain:
    #!/usr/bin/env zsh
    set -e
    ORG=$(just _gcp-org-id)
    gcloud organizations describe "$ORG" --format='value(displayName)'

# Print the Cloud Identity customer id.
[private]
_gcp-customer-id:
    #!/usr/bin/env zsh
    set -e
    ORG=$(just _gcp-org-id)
    gcloud organizations describe "$ORG" \
      --format='value(owner.directoryCustomerId)'

# Print `value` as a whole IAM member string, at `domain`.
[private]
_gcp-member value domain:
    #!/usr/bin/env zsh
    set -e
    V="{{ value }}"
    case "$V" in
      *:*) printf '%s\n' "$V" ;;
      *@*) printf 'group:%s\n' "$V" ;;
      *)   printf 'group:%s@%s\n' "$V" "{{ domain }}" ;;
    esac

# Print the billing account `sa` may bill projects to.
[private]
_gcp-billing-account sa:
    #!/usr/bin/env zsh
    set -e
    SA="{{ sa }}"
    OUT=$(gcloud billing accounts list --impersonate-service-account="$SA" \
            --filter='OPEN=True' --format='value(ACCOUNT_ID)' 2>/dev/null || true)
    COUNT=$(printf '%s' "$OUT" | grep -c . || true)
    if [[ "$COUNT" -eq 0 ]]; then
      echo "no open billing account visible to $SA" >&2
      echo "grant it roles/billing.user" >&2
      exit 1
    fi
    if [[ "$COUNT" -ne 1 ]]; then
      echo "$COUNT open billing accounts visible to $SA; pass one" >&2
      printf '%s\n' "$OUT" | sed 's/^/  /' >&2
      exit 1
    fi
    printf '%s\n' "$OUT"

# Print the first project matching `filter`, or nothing.
[private]
_gcp-project filter:
    #!/usr/bin/env zsh
    set -e
    # Not silenced: a denial exits non-zero with empty stdout, which a
    # caller would read as no such project.
    if ! OUT=$(gcloud projects list --filter="{{ filter }}" \
                 --format='value(projectId)' 2>&1); then
      echo "cannot list projects:" >&2
      printf '%s\n' "$OUT" | sed 's/^/  /' >&2
      exit 1
    fi
    printf '%s\n' "${OUT%%$'\n'*}"

# Print the capabilities in `scope` (org or installation, default both).
gcp-roles scope="": (_gcp-roles scope)

_gcp-roles scope="":
    #!/usr/bin/env zsh
    set -e
    if [[ -n "{{ scope }}" && "{{ scope }}" != "org" \
          && "{{ scope }}" != "installation" ]]; then
      echo "scope must be org or installation, not '{{ scope }}'" >&2
      exit 1
    fi

    if ! OUT=$(jq -r --arg scope "{{ scope }}" '
          .capabilities[]
          | select($scope == "" or .scope == $scope)
          | "\(.key)\t\(.scope)\t\(.membership)\t\(.grants)",
            ( .roles[]? | "  \(.role)\n    \(.why)" ),
            ( select((.roles | length) == 0)
              | "  bound below the organisation\n    \(.boundElsewhere)" ),
            ""' "{{ ROLES_FILE }}"); then
      echo "cannot read {{ ROLES_FILE }}" >&2
      exit 1
    fi
    printf '%s\n' "$OUT"

# Bind the organisation's access groups (as a super admin).
gcp-groups-bind-org:
    just _gcp-groups-bind org

# Bind an installation's access groups (as a super admin).
gcp-groups-bind-installation:
    just _gcp-groups-bind installation

# Bind every access group, the organisation's then an installation's.
gcp-groups-bind:
    just gcp-groups-bind-org
    just gcp-groups-bind-installation

# Bind the capabilities in `scope` (org or installation) at the organisation.
_gcp-groups-bind scope:
    #!/usr/bin/env zsh
    set -e
    ORG=$(just _gcp-org-id)
    DOMAIN=$(just _gcp-domain)

    if ! SPECS_RAW=$(jq -r --arg scope "{{ scope }}" '
          .capabilities[]
          | select(.scope == $scope)
          | .key as $key
          | if (.roles | length) == 0 or (has("boundElsewhere"))
            then "\($key)\t"
            else .roles[] | "\($key)\t\(.role)"
            end' "{{ ROLES_FILE }}"); then
      echo "cannot read {{ ROLES_FILE }}" >&2
      exit 1
    fi

    if [[ -z "$SPECS_RAW" ]]; then
      echo "no capabilities at scope '{{ scope }}' in {{ ROLES_FILE }}" >&2
      exit 1
    fi

    group_for() {
      case "$1" in
        orgAdmin)         printf '%s' "{{ ORG_ADMIN }}" ;;
        folderAdmin)      printf '%s' "{{ FOLDER_ADMIN }}" ;;
        projectAdmin)     printf '%s' "{{ PROJECT_ADMIN }}" ;;
        dnsAdmin)         printf '%s' "{{ DNS_ADMIN }}" ;;
        webAdmin)         printf '%s' "{{ WEB_ADMIN }}" ;;
        billingAdmin)     printf '%s' "{{ ORG_BILLING_ADMIN_GROUP }}" ;;
        securityReviewer) printf '%s' "{{ SECURITY_REVIEWER }}" ;;
        platformViewer)   printf '%s' "{{ PLATFORM_VIEWER }}" ;;
        platformAdmin)    printf '%s' "{{ PLATFORM_ADMIN }}" ;;
        clusterAdmin)     printf '%s' "{{ CLUSTER_ADMIN }}" ;;
        secretsAdmin)     printf '%s' "{{ SECRETS_ADMIN }}" ;;
        *) return 1 ;;
      esac
    }

    MISSING=0
    while IFS=$'\t' read -r KEY ROLE; do
      if ! NAME=$(group_for "$KEY"); then
        echo "{{ ROLES_FILE }} names capability '$KEY', which no group answers" >&2
        MISSING=1
        continue
      fi
      MEMBER=$(just _gcp-member "$NAME" "$DOMAIN")

      if [[ -z "$ROLE" ]]; then
        # Where, not merely not-here. A capability that names no
        # destination reads as one somebody forgot to bind.
        WHERE=$(jq -r --arg k "$KEY" '.capabilities[]
                  | select(.key == $k) | .boundElsewhere // ""' \
                  "{{ ROLES_FILE }}" | sed 's/\. .*//')
        if [[ -n "$WHERE" ]]; then
          echo ">>> $MEMBER: $WHERE"
        else
          echo ">>> $MEMBER is bound below the organisation, not here"
        fi
        continue
      fi

      echo ">>> binding $ROLE to $MEMBER on organization $ORG"
      BOUND=0
      for attempt in 1 2 3; do
        if ERR=$(gcloud organizations add-iam-policy-binding "$ORG" \
                   --member="$MEMBER" --role="$ROLE" \
                   --condition=None 2>&1 >/dev/null); then
          BOUND=1
          break
        fi
        sleep 5
      done
      if [[ "$BOUND" -ne 1 ]]; then
        printf '%s\n' "$ERR" | head -2 | sed 's/^/    /' >&2
        MISSING=1
      fi
    done <<< "$SPECS_RAW"

    if [[ "$MISSING" -ne 0 ]]; then
      exit 1
    fi

# Enforce ORG_CONSTRAINTS as `sa`, which holds policyAdmin.
gcp-org-enforce-constraints sa:
    #!/usr/bin/env zsh
    set -e
    ORG=$(just _gcp-org-id)
    SA="{{ sa }}"

    TOKEN=""
    for attempt in 1 2 3; do
      TOKEN=$(gcloud auth print-access-token \
                --impersonate-service-account="$SA" 2>/dev/null || true)
      if [[ -n "$TOKEN" ]]; then break; fi
      sleep 3
    done
    if [[ -z "$TOKEN" ]]; then
      echo "cannot impersonate $SA, which needs serviceAccountTokenCreator" >&2
      echo "on it -- carried by {{ PLATFORM_ADMIN }}, joined for the task" >&2
      exit 1
    fi
    export CLOUDSDK_AUTH_ACCESS_TOKEN="$TOKEN"

    echo ">>> organisation $ORG"
    for c in {{ ORG_CONSTRAINTS }}; do
      echo ">>> enforcing $c"
      gcloud resource-manager org-policies enable-enforce "$c" \
        --organization="$ORG"
    done

# Add a version to `secret` in `project`, from `file` or stdin.
gcp-secret-version secret project file="":
    #!/usr/bin/env zsh
    set -e
    SECRET="{{ secret }}"
    PROJECT="{{ project }}"

    if ! gcloud secrets describe "$SECRET" --project="$PROJECT" >/dev/null 2>&1; then
      echo "no secret $SECRET in $PROJECT" >&2
      echo "the composite makes the container -- apply it first" >&2
      exit 1
    fi

    if [[ -n "{{ file }}" ]]; then
      if [[ ! -f "{{ file }}" ]]; then
        echo "no file at {{ file }}" >&2
        exit 1
      fi
      gcloud secrets versions add "$SECRET" --project="$PROJECT" \
        --data-file="{{ file }}"
    else
      if [[ -t 0 ]]; then
        printf 'Value for %s (not echoed): ' "$SECRET"
        read -rs VALUE
        echo
      else
        VALUE=$(cat)
      fi
      if [[ -z "$VALUE" ]]; then
        echo "no value given" >&2
        exit 1
      fi
      # No trailing newline: a consumer that reads the value whole
      # sends it as part of the credential.
      printf '%s' "$VALUE" \
        | gcloud secrets versions add "$SECRET" --project="$PROJECT" --data-file=-
    fi

    echo ">>> added a version to $SECRET in $PROJECT"
    echo ">>> external-secrets refreshes hourly; delete its pod to hurry it"

# Scan the organisation against `framework`, accepted findings muted.
gcp-scan framework="cis_5.0_gcp":
    #!/usr/bin/env zsh
    set -e
    ORG=$(just _gcp-org-id)

    echo ">>> scanning organisation $ORG against {{ framework }}"
    prowler gcp \
      --organization-id "$ORG" \
      --compliance "{{ framework }}" \
      --mutelist-file infra/security/prowler-mutelist.yaml
