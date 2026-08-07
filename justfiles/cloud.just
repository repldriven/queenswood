# =========================================================================
# Internal helpers
# =========================================================================

# Print this environment's GCP project id on stdout; exit 1 if unknown.
# Use from other recipes as: `PROJECT_ID=$(just _gcp-project-id)`.
[private]
_gcp-project-id:
    #!/usr/bin/env zsh
    set -e
    # `pass` first: it records the project for THIS environment, where
    # `gcloud config` records whatever project was last selected on this
    # machine. Those differ the moment you touch a second environment,
    # and the gcloud answer is the one that silently provisions into the
    # wrong project.
    PROJECT_ID=$(pass show {{ PASS_ENV }}/ids/project 2>/dev/null | head -1)
    if [[ -z "$PROJECT_ID" ]]; then
      PROJECT_ID=$(gcloud config get project 2>/dev/null)
    fi
    if [[ -z "$PROJECT_ID" ]]; then
      echo "no project for {{ QUEENSWOOD_ENV }}: set {{ PASS_ENV }}/ids/project in pass, or run 'just gcp-project-create'" >&2
      exit 1
    fi
    printf '%s\n' "$PROJECT_ID"

# Fail unless the logged-in gcloud account matches this environment's.
[private]
_gcp-assert-account:
    #!/usr/bin/env zsh
    set -e
    # Skipped silently when the entry is absent, so a store that hasn't
    # been populated yet doesn't block every cloud recipe.
    WANT=$(pass show {{ PASS_ENV }}/ids/account 2>/dev/null | head -1)
    if [[ -z "$WANT" ]]; then
      exit 0
    fi
    HAVE=$(gcloud config get-value account 2>/dev/null)
    if [[ "$WANT" != "$HAVE" ]]; then
      echo "gcloud is authenticated as $HAVE, but {{ QUEENSWOOD_ENV }} belongs to $WANT" >&2
      echo "run: gcloud config set account $WANT" >&2
      exit 1
    fi

# Print the kubeconfig context name for the queenswood GKE cluster.
# Use as: `KCTX=$(just _gke-ctx)`.
[private]
_gke-ctx:
    @printf 'gke_%s_{{ ZONE }}_queenswood-gke\n' "$(just _gcp-project-id)"

# Print a GCP service-account email for the given account-id.
# Use as: `EMAIL=$(just _gcp-sa-email crossplane-provider)`.
[private]
_gcp-sa-email name:
    @printf '%s@%s.iam.gserviceaccount.com\n' '{{ name }}' "$(just _gcp-project-id)"

# =========================================================================
# Org-level (one-time)
# =========================================================================

# Grant org-level orgpolicy.policyAdmin to the operator + enforce skipDefaultNetworkCreation.
gcp-org-create:
    #!/usr/bin/env zsh
    set -e
    # roles/owner does NOT include orgpolicy perms; bind explicitly.
    : "${GCLOUD_ORGANIZATION_ID:?must be set}"
    ME=$(gcloud config get-value account)
    echo ">>> granting orgpolicy.policyAdmin to $ME on org $GCLOUD_ORGANIZATION_ID"
    gcloud organizations add-iam-policy-binding "$GCLOUD_ORGANIZATION_ID" \
      --member="user:${ME}" --role="roles/orgpolicy.policyAdmin" --condition=None
    # Child projects inherit this so per-project policy work is unnecessary.
    echo ">>> setting compute.skipDefaultNetworkCreation on org"
    printf 'name: organizations/%s/policies/compute.skipDefaultNetworkCreation\nspec:\n  rules:\n    - enforce: true\n' "$GCLOUD_ORGANIZATION_ID" \
      | gcloud org-policies set-policy /dev/stdin

# =========================================================================
# Project-level
# =========================================================================

# Create a new GCP project, link billing, return the ID on stdout.
gcp-project-create suffix=`openssl rand -hex 3`:
    #!/usr/bin/env zsh
    set -e
    PROJECT_ID="queenswood-{{ suffix }}"
    org_flag=""
    if [[ -n "${GCLOUD_ORGANIZATION_ID:-}" ]]; then
      org_flag="--organization=${GCLOUD_ORGANIZATION_ID}"
      echo ">>> creating project $PROJECT_ID under org $GCLOUD_ORGANIZATION_ID" >&2
    else
      echo ">>> creating project $PROJECT_ID (no org)" >&2
    fi
    gcloud projects create "$PROJECT_ID" --name=queenswood $org_flag >&2
    : "${GCP_BILLING_ACCOUNT_ID:=$(gcloud beta billing accounts list \
        --filter='OPEN=True' --format='value(name)' | head -1)}"
    if [[ -z "$GCP_BILLING_ACCOUNT_ID" ]]; then
      echo "no open billing account; set GCP_BILLING_ACCOUNT_ID" >&2
      exit 1
    fi
    echo ">>> linking billing $GCP_BILLING_ACCOUNT_ID" >&2
    gcloud beta billing projects link "$PROJECT_ID" \
      --billing-account="$GCP_BILLING_ACCOUNT_ID" >&2
    gcloud config set project "$PROJECT_ID" >&2
    printf '%s\n' "$PROJECT_ID"

# Full retirement: gcp-down + delete the GCP project.
gcp-project-delete:
    #!/usr/bin/env zsh
    set -e
    PROJECT_ID=$(just _gcp-project-id)
    just gcp-down

    # DNS zone left behind (observe-only on the Crossplane side). dnsName
    # claim releases when project hard-deletes after the 30-day window.
    echo ">>> deleting GCP project $PROJECT_ID"
    gcloud projects delete "$PROJECT_ID" --quiet
    gcloud config unset project >/dev/null 2>&1 || true
    echo ">>> recover within 30 days: gcloud projects undelete $PROJECT_ID"

# =========================================================================
# Auth
# =========================================================================

# Authenticate gcloud as the operator (you).
gcp-user-login:
    gcloud auth login

# Set ADC to impersonate the crossplane-provider SA (no key file).
gcp-crossplane-login:
    gcloud auth application-default login \
      --impersonate-service-account=$(just _gcp-sa-email crossplane-provider)

# =========================================================================
# Bootstrap-of-bootstrap (one-time per project)
# =========================================================================

# Create the crossplane-provider SA + minimum IAM bindings Crossplane needs.
gcp-iam-bootstrap:
    #!/usr/bin/env zsh
    set -e
    PROJECT_ID=$(just _gcp-project-id)
    SA=crossplane-provider
    SA_EMAIL=$(just _gcp-sa-email $SA)
    ME=$(gcloud config get-value account)

    gcloud config set project $PROJECT_ID

    # Bootstrap APIs only -- chicken-and-egg ones Crossplane needs to
    # enable other APIs via infra/gcp/services/services.yml.
    gcloud services enable \
      cloudresourcemanager.googleapis.com \
      iam.googleapis.com \
      iamcredentials.googleapis.com \
      serviceusage.googleapis.com

    # SA create idempotent: re-running is a no-op if the SA exists.
    if ! gcloud iam service-accounts describe $SA_EMAIL >/dev/null 2>&1; then
      gcloud iam service-accounts create $SA \
        --display-name "Crossplane Provider"
    fi

    # The two roles Crossplane needs to manage everything else; the
    # broader role list reconciles via the `gcp-roles` Argo app.
    for role in \
      roles/resourcemanager.projectIamAdmin \
      roles/serviceusage.serviceUsageAdmin ; do
      gcloud projects add-iam-policy-binding $PROJECT_ID \
        --member="serviceAccount:${SA_EMAIL}" \
        --role="$role"
    done

    # Let the operator impersonate the SA (replaces key creation).
    gcloud iam service-accounts add-iam-policy-binding $SA_EMAIL \
      --member="user:${ME}" \
      --role="roles/iam.serviceAccountTokenCreator"

# Pre-create the DNS zone as the verified domain owner.
gcp-dns-zone-create:
    #!/usr/bin/env zsh
    set -e
    # Cloud DNS rejects SA-initiated zone creates -- the crossplane SA
    # isn't a verified domain owner. If the operator isn't verified
    # either, the create surfaces an ownership error and the recipe
    # launches `gcloud domains verify`.
    PROJECT_ID=$(just _gcp-project-id)
    DNS={{ QUEENSWOOD_DOMAIN }}.
    NAME={{ QUEENSWOOD_DNS_ZONE }}
    if gcloud dns managed-zones describe "$NAME" \
         --project="$PROJECT_ID" >/dev/null 2>&1; then
      echo ">>> $NAME already exists; skipping create"
      exit 0
    fi
    if ! gcloud dns managed-zones create "$NAME" \
           --dns-name="$DNS" --description="Public DNS for queenswood" \
           --visibility=public --project="$PROJECT_ID" 2>/tmp/dns-err; then
      if grep -q verifyManagedZoneDnsNameOwnership /tmp/dns-err; then
        PARENT="${DNS%.}"; PARENT="${PARENT#*.}"
        echo ">>> $PARENT not verified for $(gcloud config get-value account); opening Search Console"
        gcloud domains verify "$PARENT"
        echo ">>> after verifying, rerun: just gcp-dns-zone-create"
        exit 1
      fi
      cat /tmp/dns-err; exit 1
    fi

# =========================================================================
# Lifecycle (up / down)
# =========================================================================

# Cold start: existing GCP project + no kind cluster -> green stack.
gcp-up:
    #!/usr/bin/env zsh
    set -e
    PROJECT_ID=$(just _gcp-project-id)
    just gcp-user-login
    just _gcp-assert-account
    just gcp-iam-bootstrap
    just gcp-crossplane-login
    just gcp-dns-zone-create
    just gcp-backup-bucket-create
    # Before kind-xp-up: the root Application reads both of these out of
    # `pass` to hand to FDB's backup agents, so they have to exist first.
    just gcp-backup-hmac-create
    just gcp-backup-key-create
    just kind-xp-up
    just gcp-cloudsql-wire
    # Identities for the realm export and the realm import. Both are
    # bucket-scoped, so neither depends on CloudSQL existing yet.
    just gcp-keycloak-restore-sa-create
    just gcp-keycloak-backup-sa-create
    # Google sign-in reads its client secret from Keycloak's vault, and
    # the Secret behind it is not part of any chart -- without this a
    # rebuilt environment authenticates nobody, failing with
    # "Unexpected error when authenticating with identity provider".
    just gcp-keycloak-vault-secret google
    # And the realm's own copy of the client id/secret, which the
    # committed realm JSON carries only as a placeholder. Without it
    # Google returns `401 invalid_client` and nobody can sign in --
    # which on a restored environment is indistinguishable from the
    # restore having failed.
    just gcp-keycloak-idp google
    just gcp-health-check

# Tear down kind + GKE + expensive GCP resources; keep project, SA, DNS zone.
gcp-down:
    #!/usr/bin/env zsh
    set -e
    GKE_CTX=$(just _gke-ctx)

    # Close both backups off first. Each runs in the workload namespace
    # against a live cluster, and each refuses rather than proceeds if
    # what it produced is not restorable -- draining past this point is
    # what makes the data unrecoverable.
    #
    # They are taken together because they are only useful together:
    # FDB references the Keycloak subject, so a pair whose identities
    # were captured at different moments restores a bank whose users no
    # longer resolve. That pairing is the property ADR-0022 asks for.
    just gcp-fdb-export
    just gcp-keycloak-export

    # Drain GKE workloads BEFORE killing kind. GKE's CSI driver only
    # DeleteDisks PVs while the cluster is alive; destroying GKE with
    # PVCs around leaks PDs (~180 GiB/cycle from pulsar + fdb).
    # Crossplane (kind) must still be up to clear namespaced finalizers.
    if kubectl --context "$GKE_CTX" get ns queenswood-test >/dev/null 2>&1; then
      echo ">>> draining queenswood-test (lets CSI free PVs cleanly)"
      kubectl --context "$GKE_CTX" delete ns queenswood-test \
        --wait=true --timeout=5m \
        || echo "  (drain timed out -- some PDs may leak)" >&2
    fi

    just kind-xp-down || true
    just gcp-gke-delete

    # The realms were exported before the drain, above -- an automated
    # CloudSQL backup is tied to the instance's lifecycle and goes with
    # it, but nothing here needs the database itself to survive.
    just gcp-cloudsql-delete
    just gcp-ip-delete
    just gcp-cert-delete
    just gcp-vpc-delete
    just gcp-dns-records-delete

    kubectl config delete-context "$GKE_CTX" 2>/dev/null || true
    kubectl config delete-cluster "$GKE_CTX" 2>/dev/null || true
    kubectl config delete-user    "$GKE_CTX" 2>/dev/null || true

    echo ">>> kept: project, SA, IAM, DNS zone, registrar NS. Run 'just gcp-up' to come back."

# Delete the queenswood-gke GKE cluster.
gcp-gke-delete:
    #!/usr/bin/env zsh
    PROJECT_ID=$(just _gcp-project-id)
    echo ">>> deleting GKE cluster (most of the bill)"
    gcloud container clusters delete queenswood-gke \
      --zone={{ ZONE }} --project="$PROJECT_ID" --quiet 2>/dev/null || true

# Delete the queenswood-keycloak CloudSQL instance (cascades to database + user).
gcp-cloudsql-delete:
    #!/usr/bin/env zsh
    # Crossplane would normally cascade-delete but kind-xp-down kills it
    # first. Deleting the instance drops the keycloak Database + User
    # inside it (SQL admin treats them as sub-resources).
    PROJECT_ID=$(just _gcp-project-id)
    echo ">>> deleting CloudSQL databases (instance + database + user)"
    gcloud sql instances delete queenswood-keycloak \
      --project="$PROJECT_ID" --quiet 2>/dev/null || true

# Delete the queenswood-ingress-ip static IP.
gcp-ip-delete:
    #!/usr/bin/env zsh
    PROJECT_ID=$(just _gcp-project-id)
    echo ">>> deleting ingress IP"
    gcloud compute addresses delete queenswood-ingress-ip \
      --region={{ REGION }} --project="$PROJECT_ID" --quiet 2>/dev/null || true

# Delete the queenswood-cert Certificate + queenswood-dns-auth DNSAuthorization.
gcp-cert-delete:
    #!/usr/bin/env zsh
    PROJECT_ID=$(just _gcp-project-id)
    echo ">>> deleting Certificate + DNSAuthorization"
    gcloud certificate-manager certificates delete queenswood-cert \
      --location={{ REGION }} --project="$PROJECT_ID" --quiet 2>/dev/null || true
    gcloud certificate-manager dns-authorizations delete queenswood-dns-auth \
      --location={{ REGION }} --project="$PROJECT_ID" --quiet 2>/dev/null || true

# Delete the queenswood VPC + its subnets.
gcp-vpc-delete:
    #!/usr/bin/env zsh
    PROJECT_ID=$(just _gcp-project-id)
    echo ">>> deleting subnets + VPC"
    gcloud compute networks subnets delete queenswood-subnet \
      --region={{ REGION }} --project="$PROJECT_ID" --quiet 2>/dev/null || true
    gcloud compute networks subnets delete queenswood-proxy-only \
      --region={{ REGION }} --project="$PROJECT_ID" --quiet 2>/dev/null || true
    gcloud compute networks delete queenswood-vpc \
      --project="$PROJECT_ID" --quiet 2>/dev/null || true

# DESTRUCTIVE: delete the ManagedZone and all its records. Emergency use only.
gcp-dns-zone-delete:
    #!/usr/bin/env zsh
    set -e
    PROJECT_ID=$(just _gcp-project-id)
    cat >&2 <<EOF
    ================================================================
    About to delete DNS ManagedZone '{{ QUEENSWOOD_DNS_ZONE }}'.

    Consequences:
      - All non-SOA/NS records will be deleted first (zone delete
        refuses while any user records remain).
      - On the next 'gcp-dns-zone-create', the new zone gets a
        DIFFERENT set of nameservers. The registrar delegation for
        {{ QUEENSWOOD_DOMAIN }} must be updated to match -- until then,
        public DNS resolution is broken.
      - GCP's domain-ownership verification for {{ QUEENSWOOD_DOMAIN }} may
        need redoing on the next create.
      - Each fresh zone burns one of GCP's per-domain NS-pool
        allocations; repeated delete/create can hit the quota.

    Use 'gcp-down' for routine teardown -- it keeps the zone.
    ================================================================
    EOF
    printf "Type 'yes' to proceed: "
    read confirm
    [[ "$confirm" == "yes" ]] || { echo "aborted." >&2; exit 1; }
    echo ">>> emptying record-sets in {{ QUEENSWOOD_DNS_ZONE }}"
    gcloud dns record-sets list --zone={{ QUEENSWOOD_DNS_ZONE }} --project="$PROJECT_ID" \
        --format='value(name,type)' 2>/dev/null \
      | while read name type; do
          if [[ "$type" == "SOA" || "$type" == "NS" ]]; then continue; fi
          gcloud dns record-sets delete "$name" --type="$type" \
            --zone={{ QUEENSWOOD_DNS_ZONE }} --project="$PROJECT_ID" --quiet 2>/dev/null || true
        done
    echo ">>> deleting ManagedZone {{ QUEENSWOOD_DNS_ZONE }}"
    gcloud dns managed-zones delete {{ QUEENSWOOD_DNS_ZONE }} \
      --project="$PROJECT_ID" --quiet

# Delete the apex A + cert-validation CNAME records (keep the zone).
gcp-dns-records-delete:
    #!/usr/bin/env zsh
    PROJECT_ID=$(just _gcp-project-id)
    echo ">>> deleting apex A + cert CNAME (keeping zone)"
    gcloud dns record-sets delete {{ QUEENSWOOD_DOMAIN }}. --type=A --zone={{ QUEENSWOOD_DNS_ZONE }} \
      --project="$PROJECT_ID" --quiet 2>/dev/null || true
    gcloud dns record-sets list --zone={{ QUEENSWOOD_DNS_ZONE }} --project="$PROJECT_ID" \
        --filter="type=CNAME" --format='value(name)' 2>/dev/null \
      | while read n; do
          gcloud dns record-sets delete "$n" --type=CNAME --zone={{ QUEENSWOOD_DNS_ZONE }} \
            --project="$PROJECT_ID" --quiet 2>/dev/null || true
        done

# Block on each Argo Application reaching Healthy (helper for gcp-up).
kind-xp-wait-ready timeout="2700":
    #!/usr/bin/env zsh
    set -e
    PROJECT_ID=$(just _gcp-project-id)
    # Surfaces the ManagedZone's nameservers as soon as the zone is
    # queryable so the operator can paste them into the registrar --
    # cert validation gates on NS propagation.
    for app in crossplane-providers crossplane-configs crossplane-xrds \
               queenswood-gcp queenswood-platform; do
      echo ">>> $app"
      until kubectl --context {{ KIND_XP_CLUSTER }} -n argocd \
            get application/$app >/dev/null 2>&1; do sleep 5; done
      if [[ "$app" == "queenswood-platform" ]]; then
        # Bounded and noisy on purpose. The zone is observe-only, so
        # Crossplane never creates it -- if the real Cloud DNS zone is
        # absent or named differently, nameServers never appear and an
        # unbounded loop here is indistinguishable from a slow
        # reconcile, with nothing printed either way.
        zone_deadline=$(( $(date +%s) + {{ timeout }} ))
        NS=""
        while [[ -z "$NS" ]]; do
          NS=$(kubectl --context {{ KIND_XP_CLUSTER }} -n {{ QUEENSWOOD_ENV }} get \
                managedzone.dns.gcp.m.upbound.io {{ QUEENSWOOD_DNS_ZONE }} \
                -o jsonpath='{.status.atProvider.nameServers}' 2>/dev/null || true)
          # `[[ ... ]] && break` would abort the whole recipe under
          # set -e whenever the test fails, which is every iteration
          # this loop exists for.
          if [[ -n "$NS" ]]; then break; fi
          if (( $(date +%s) > zone_deadline )); then
            echo >&2
            echo "ManagedZone {{ QUEENSWOOD_DNS_ZONE }} never published nameservers." >&2
            echo "It is observe-only, so Crossplane will not create it. Check the" >&2
            echo "Cloud DNS zone exists under that exact name:" >&2
            echo "  gcloud dns managed-zones describe {{ QUEENSWOOD_DNS_ZONE }} --project=$PROJECT_ID" >&2
            echo "  just gcp-dns-zone-create" >&2
            exit 1
          fi
          echo "  waiting for {{ QUEENSWOOD_DNS_ZONE }} nameservers..."
          sleep 10
        done
        echo
        echo "------------------------------------------------------"
        echo "ACTION: paste these 4 nameservers into your registrar's"
        echo "NS records for {{ QUEENSWOOD_DOMAIN }} (trailing dots"
        echo "optional). cert validation gates on this propagating."
        kubectl --context {{ KIND_XP_CLUSTER }} -n {{ QUEENSWOOD_ENV }} \
          get managedzone.dns.gcp.m.upbound.io {{ QUEENSWOOD_DNS_ZONE }} \
          -o jsonpath='{.status.atProvider.nameServers}' \
          | tr ',' '\n' | tr -d '[]"' | sed 's/^ */  /'
        echo "------------------------------------------------------"
        echo
      fi
      kubectl --context {{ KIND_XP_CLUSTER }} -n argocd wait \
        --for=jsonpath='{.status.health.status}'=Healthy \
        application/$app --timeout={{ timeout }}s
      if [[ "$app" == "queenswood-gcp" ]]; then
        # Argo reports this Application Healthy once its manifests are
        # applied, not once the cloud resources exist -- it has no
        # health check for Crossplane managed resources.
        #
        # The signal is the managed resources, not gcloud. A cluster is
        # created without its node pool and gets ours separately, so
        # gcloud reports RUNNING in the gap and flips back to
        # RECONCILING -- a cluster with no nodes, briefly indis-
        # tinguishable from a finished one. The Ready condition also
        # gates something get-credentials does not: the cluster
        # publishes its kubeconfig connection secret when it goes
        # Ready, and until it does provider-helm cannot build a client
        # ("currentContext not set in kubeconfig") and every Release in
        # the platform wave fails to sync.
        gke_deadline=$(( $(date +%s) + {{ timeout }} ))
        ready() {
          [[ "$(kubectl --context {{ KIND_XP_CLUSTER }} -n crossplane-system get \
                "$1" "$2" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' \
                2>/dev/null)" == "True" ]]
        }
        until ready cluster.container.gcp.m.upbound.io queenswood-gke \
           && ready nodepool.container.gcp.m.upbound.io queenswood-pool; do
          if (( $(date +%s) > gke_deadline )); then
            echo >&2
            echo "queenswood-gke/queenswood-pool never went Ready. Check them:" >&2
            echo "  kubectl --context {{ KIND_XP_CLUSTER }} -n crossplane-system get \\" >&2
            echo "    cluster.container.gcp.m.upbound.io nodepool.container.gcp.m.upbound.io" >&2
            exit 1
          fi
          echo "  waiting for queenswood-gke + queenswood-pool to go Ready..."
          sleep 20
        done
        gcloud container clusters get-credentials queenswood-gke \
          --zone={{ ZONE }} --project="$PROJECT_ID"
      fi
    done

# =========================================================================
# Per-deploy
# =========================================================================

# Wire the CloudSQL connection + SA email from Crossplane's status into platform values.
gcp-cloudsql-wire:
    #!/usr/bin/env zsh
    set -e
    # CloudSQL provisioning takes 5-10 minutes; wait timeout is 15m.
    PROJECT_ID=$(just _gcp-project-id)
    VALUES=infra/helm/queenswood-platform/values.yaml
    # Matches the cloud-sql-proxy GCP SA composed by the XPlatform XR.
    SA_EMAIL=$(just _gcp-sa-email keycloak-sql-proxy)

    echo "Waiting for XPlatform/queenswood status.connectionName (up to 15m)..."
    CONN=""
    deadline=$(( $(date +%s) + 900 ))
    while [[ -z "$CONN" && $(date +%s) -lt $deadline ]]; do
      CONN=$(kubectl --context {{ KIND_XP_CLUSTER }} -n crossplane-system \
               get xplatform.platform.queenswood.repldriven.com queenswood \
               -o jsonpath='{.status.connectionName}' 2>/dev/null || true)
      if [[ -z "$CONN" ]]; then sleep 10; fi
    done
    if [[ -z "$CONN" ]]; then
      echo "" >&2
      echo "Timed out waiting for status.connectionName on XPlatform/queenswood." >&2
      echo "--- XPlatform conditions ---" >&2
      kubectl --context {{ KIND_XP_CLUSTER }} -n crossplane-system \
        get xplatform.platform.queenswood.repldriven.com queenswood \
        -o jsonpath='{range .status.conditions[*]}{.type}={.status}  reason={.reason}  message={.message}{"\n"}{end}' >&2 || true
      exit 1
    fi
    echo "Resolved connectionName: $CONN"
    echo "Pinning gcpServiceAccount:  $SA_EMAIL"

    cur_conn=$(yq -r '.queenswoodKeycloak.proxy.connectionName' $VALUES)
    cur_sa=$(yq -r '.queenswoodKeycloak.proxy.gcpServiceAccount' $VALUES)
    if [[ "$cur_conn" == "$CONN" && "$cur_sa" == "$SA_EMAIL" ]]; then
      echo "$VALUES already pinned; skipping commit."
      exit 0
    fi

    # sed not `yq -y -i` -- kislyuk/yq round-trips through JSON and
    # strips YAML comments. Two keys, unique paths, anchored sed safe.
    sed -i.bak -E "s|^([[:space:]]*connectionName:).*|\1 $CONN|" $VALUES
    sed -i.bak -E "s|^([[:space:]]*gcpServiceAccount:).*|\1 $SA_EMAIL|" $VALUES
    rm -f "${VALUES}.bak"

    git add $VALUES
    git commit -m "queenswood-platform: pin queenswood-keycloak CloudSQL connection + WI SA"
    git push
    echo "Pushed. Argo will reconcile queenswood-platform; Crossplane will reinstall queenswood-keycloak with the new values."

# Roll the listed queenswood Deployments to pick up :latest images.
gcp-k8s-redeploy-svc +components:
    #!/usr/bin/env zsh
    set -e
    # Use after release-images.yml republishes :latest without a chart
    # version bump -- Crossplane Helm Release sees no spec diff and
    # imagePullPolicy: Always only re-pulls on pod create. Components
    # are brick names without the release prefix: `just gcp-k8s-redeploy-svc
    # console api-service`.
    PROJECT_ID=$(just _gcp-project-id)
    KCTX=$(just _gke-ctx)
    for c in {{ components }}; do
      echo "→ deploy/{{ QUEENSWOOD_ENV }}-$c"
      kubectl --context $KCTX -n {{ QUEENSWOOD_ENV }} rollout restart deploy/{{ QUEENSWOOD_ENV }}-$c
      kubectl --context $KCTX -n {{ QUEENSWOOD_ENV }} rollout status  deploy/{{ QUEENSWOOD_ENV }}-$c
    done

# Print what to create in the Google console, then capture the result into `pass`.
gcp-oauth-client name="google":
    #!/usr/bin/env zsh
    set -euo pipefail
    # Google has no API for creating a Web OAuth client with a custom
    # redirect URI -- `gcloud iap oauth-clients` is IAP-scoped, sets its
    # own redirect URI, and went non-functional in Jan 2026. So the
    # client is created by hand and this recipe owns the two things
    # that are easy to get wrong: the exact redirect URI, and where the
    # result belongs in `pass`.
    #
    # One client per environment, not one shared across them: revoking or
    # rotating a dev client must not touch the one real customers sign in
    # through, and a client still in Google's Testing mode is a different
    # risk object from a published one. The local rig has its own, filed
    # under queenswood/local/dev/auth/clients/.
    PROJECT_ID=$(just _gcp-project-id)
    echo "Create a Web application OAuth client here:"
    echo "  https://console.cloud.google.com/apis/credentials/oauthclient?project=$PROJECT_ID"
    echo
    echo "Name it:                  {{ QUEENSWOOD_ENV }}"
    echo "Authorised redirect URI:  https://keycloak.{{ QUEENSWOOD_DOMAIN }}/realms/queenswood/broker/{{ name }}/endpoint"
    echo
    printf 'Client ID: '
    read client_id
    printf 'Client secret (not echoed): '
    read -s client_secret
    echo
    if [[ -z "$client_id" || -z "$client_secret" ]]; then
      echo "both values are required" >&2; exit 1
    fi
    printf '%s\n' "$client_id" | pass insert -m -f {{ PASS_ENV }}/auth/clients/ids/{{ name }} >/dev/null
    printf '%s\n' "$client_secret" | pass insert -m -f {{ PASS_ENV }}/auth/clients/{{ name }} >/dev/null
    echo "stored {{ PASS_ENV }}/auth/clients/{ids/{{ name }},{{ name }}}"
    echo "now run: just gcp-keycloak-idp {{ name }}"

# Push an IdP's client id + secret from `pass` into the deployed realm.
gcp-keycloak-idp name="google":
    #!/usr/bin/env zsh
    set -euo pipefail
    # Both the client id and the vault expression go over the Admin API,
    # because the operator won't re-run a completed KeycloakRealmImport
    # -- a realm that already exists never picks up a chart change, so
    # it keeps whatever placeholder it was first imported with. Only the
    # expression is sent, never the secret: Keycloak resolves
    # ${vault.<key>} from the mounted file at token exchange.
    KCTX=$(just _gke-ctx)
    KCHOST=https://keycloak.{{ QUEENSWOOD_DOMAIN }}
    client_id=$(pass show {{ PASS_ENV }}/auth/clients/ids/{{ name }} | head -1)
    just gcp-keycloak-vault-secret {{ name }}
    just _keycloak-wait-serving
    TOK=$(just _keycloak-admin-token)
    idp=$(curl -sf --max-time 15 -H "Authorization: Bearer $TOK" \
      "$KCHOST/admin/realms/queenswood/identity-provider/instances/{{ name }}")
    printf '%s' "$idp" \
      | CLIENT_ID="$client_id" VAULT_KEY='${vault.{{ name }}-client-secret}' python3 -c 'import sys,json,os; d=json.load(sys.stdin); d["config"]["clientId"]=os.environ["CLIENT_ID"]; d["config"]["clientSecret"]=os.environ["VAULT_KEY"]; json.dump(d,sys.stdout)' \
      | curl -sf --max-time 15 -X PUT -H "Authorization: Bearer $TOK" \
          -H 'Content-Type: application/json' --data @- \
          "$KCHOST/admin/realms/queenswood/identity-provider/instances/{{ name }}"
    echo ">>> {{ name }} clientId updated; restarting Keycloak for the vault mount"
    kubectl --context $KCTX -n {{ QUEENSWOOD_ENV }} rollout restart statefulset/queenswood-keycloak-keycloak
    kubectl --context $KCTX -n {{ QUEENSWOOD_ENV }} rollout status statefulset/queenswood-keycloak-keycloak --timeout=5m

# Block until the queenswood realm answers on the public hostname.
[private]
_keycloak-wait-serving timeout="1800":
    #!/usr/bin/env zsh
    set -euo pipefail
    # The realm endpoint rather than the Job or the pod, because it is
    # the conjunction of everything a caller here needs: DNS resolving,
    # the certificate issued, the gateway routing, Keycloak serving,
    # AND the realm imported. Nothing in `gcp-up` waits for that on its
    # own -- Argo calls the platform Application Healthy once the
    # Crossplane Releases are applied, which is well before the
    # realm-import Job has run, and `gcp-health-check` reports rather
    # than blocks.
    URL=https://keycloak.{{ QUEENSWOOD_DOMAIN }}/realms/queenswood/.well-known/openid-configuration
    deadline=$(( $(date +%s) + {{ timeout }} ))
    until curl -sf --max-time 10 -o /dev/null "$URL"; do
      if (( $(date +%s) > deadline )); then
        echo >&2
        echo "the queenswood realm never came up at $URL" >&2
        echo "check the realm import and the gateway:" >&2
        echo "  kubectl -n {{ QUEENSWOOD_ENV }} logs -l app.kubernetes.io/component=keycloak-realm-import" >&2
        echo "  kubectl -n {{ QUEENSWOOD_ENV }} get httproute,gateway" >&2
        exit 1
      fi
      echo "  waiting for the queenswood realm to serve..."
      sleep 10
    done
    echo ">>> the queenswood realm is serving"

# Print a Keycloak admin access token for the active env.
[private]
_keycloak-admin-token:
    #!/usr/bin/env zsh
    set -euo pipefail
    KCTX=$(just _gke-ctx)
    S=queenswood-keycloak-keycloak-initial-admin
    u=$(kubectl --context $KCTX -n {{ QUEENSWOOD_ENV }} get secret $S -o jsonpath='{.data.username}' | base64 -d)
    p=$(kubectl --context $KCTX -n {{ QUEENSWOOD_ENV }} get secret $S -o jsonpath='{.data.password}' | base64 -d)
    curl -sf --max-time 15 -d client_id=admin-cli -d "username=$u" \
      --data-urlencode "password=$p" -d grant_type=password \
      "https://keycloak.{{ QUEENSWOOD_DOMAIN }}/realms/master/protocol/openid-connect/token" \
      | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])'

# Load an IdP client secret from `pass` into Keycloak's vault Secret.
gcp-keycloak-vault-secret name="google":
    #!/usr/bin/env zsh
    set -euo pipefail
    # Mirrors the testcontainer flow in test-resources' keycloak
    # container config: same `pass` entry, same files-plaintext vault,
    # same <realm>_<key> filename. The secret goes from `pass` straight
    # to the cluster -- never git, never the shell environment.
    KCTX=$(just _gke-ctx)
    secret=$(pass show {{ PASS_ENV }}/auth/clients/{{ name }} | head -1) || {
      echo "{{ PASS_ENV }}/auth/clients/{{ name }} not in pass" >&2; exit 1
    }
    # <realm>_<key>, which the realm references as ${vault.<key>}.
    kubectl --context $KCTX -n {{ QUEENSWOOD_ENV }} create secret generic \
      queenswood-keycloak-vault \
      --from-literal=queenswood_{{ name }}-client-secret="$secret" \
      --dry-run=client -o yaml \
      | kubectl --context $KCTX -n {{ QUEENSWOOD_ENV }} apply -f -
    echo ">>> stored queenswood_{{ name }}-client-secret in the vault Secret"

# =========================================================================
# Backups
# =========================================================================

# Create the durable backup bucket. Survives gcp-down; storage is pennies.
gcp-backup-bucket-create:
    #!/usr/bin/env zsh
    set -euo pipefail
    # Tier 0, alongside the project and the DNS zone: what makes the
    # expensive tier disposable rather than destructive. Created here
    # rather than by Crossplane for the same reason the DNS zone is --
    # no storage provider is installed, and one bucket doesn't justify
    # another provider package on the management plane.
    PROJECT_ID=$(just _gcp-project-id)
    BUCKET=$(just _gcp-backup-bucket)
    if gcloud storage buckets describe "$BUCKET" --project="$PROJECT_ID" >/dev/null 2>&1; then
      echo ">>> $BUCKET already exists"
      exit 0
    fi
    gcloud storage buckets create "$BUCKET" \
      --project="$PROJECT_ID" --location={{ REGION }} \
      --uniform-bucket-level-access --public-access-prevention
    just gcp-backup-lifecycle-set
    echo ">>> created $BUCKET"

# Apply per-prefix retention to the backup bucket.
gcp-backup-lifecycle-set:
    #!/usr/bin/env zsh
    set -euo pipefail
    # Retention is policy and belongs here, not in the export recipes --
    # a backup script that deletes its own old output is one bug away
    # from deleting the wrong thing. Everything is written to Standard
    # and demoted by age rather than written cold, so a restore drill
    # in the first week is not a Coldline retrieval.
    #
    # FDB's prefixes are not age-managed: a continuous backup's older
    # objects are not stale copies, they are the log a restore reads
    # through. `fdbbackup expire` is what prunes those, by version.
    PROJECT_ID=$(just _gcp-project-id)
    BUCKET=$(just _gcp-backup-bucket)
    cat > /tmp/backup-lifecycle.json <<'EOF'
    {
      "lifecycle": {
        "rule": [
          {
            "action": {"type": "SetStorageClass", "storageClass": "NEARLINE"},
            "condition": {"age": 7, "matchesPrefix": ["keycloak/realms/"]}
          },
          {
            "action": {"type": "SetStorageClass", "storageClass": "COLDLINE"},
            "condition": {"age": 30, "matchesPrefix": ["keycloak/realms/"]}
          },
          {
            "action": {"type": "Delete"},
            "condition": {"age": 365, "matchesPrefix": ["keycloak/realms/"]}
          }
        ]
      }
    }
    EOF
    gcloud storage buckets update "$BUCKET" \
      --lifecycle-file=/tmp/backup-lifecycle.json --project="$PROJECT_ID" >/dev/null
    echo ">>> lifecycle applied to $BUCKET"

# Print the backup bucket URL for this environment.
[private]
_gcp-backup-bucket:
    @printf 'gs://%s-backups\n' "$(just _gcp-project-id)"

# Print the bucket name (no gs:// scheme), which is what FDB wants.
[private]
_gcp-backup-bucket-name:
    @printf '%s-backups\n' "$(just _gcp-project-id)"

# Exempt this project from the org's ban on service-account key creation.
[private]
_gcp-allow-sa-keys:
    #!/usr/bin/env zsh
    set -euo pipefail
    # GCP enforces iam.disableServiceAccountKeyCreation at the org by
    # default, and an HMAC key counts as a service-account key -- so
    # minting one fails with a 412 until the project is exempted. The
    # exception is set on the project rather than the org: every other
    # project stays locked, and the one key it permits is scoped to the
    # fdb-backup service account, which holds objectAdmin on the backup
    # bucket and nothing else.
    #
    # The v1 `resource-manager org-policies` surface, not the v2
    # `org-policies` one used at org scope in gcp-org-create: v2 needs
    # orgpolicy.googleapis.com enabled on the project, which is one more
    # bootstrap API for a call v1 already serves off
    # cloudresourcemanager.
    PROJECT_ID=$(just _gcp-project-id)
    ENFORCED=$(gcloud resource-manager org-policies describe \
      constraints/iam.disableServiceAccountKeyCreation \
      --project="$PROJECT_ID" --effective \
      --format='value(booleanPolicy.enforced)' 2>/dev/null || true)
    if [[ "$ENFORCED" != "True" ]]; then
      exit 0
    fi
    echo ">>> exempting $PROJECT_ID from iam.disableServiceAccountKeyCreation"
    gcloud resource-manager org-policies disable-enforce \
      constraints/iam.disableServiceAccountKeyCreation \
      --project="$PROJECT_ID" >/dev/null

# Mint the HMAC key FDB's backup agents authenticate with; store it in pass.
gcp-backup-hmac-create:
    #!/usr/bin/env zsh
    set -euo pipefail
    # FDB's backup agent speaks S3, not GCS, so it reaches the bucket
    # through the interop endpoint at storage.googleapis.com -- which
    # authenticates with an HMAC key rather than a service-account
    # token. The key belongs to a dedicated service account so revoking
    # it costs nothing else.
    PROJECT_ID=$(just _gcp-project-id)
    BUCKET=$(just _gcp-backup-bucket)
    SA=$(just _gcp-sa-email fdb-backup)
    just gcp-backup-bucket-create
    just _gcp-allow-sa-keys
    if ! gcloud iam service-accounts describe "$SA" --project="$PROJECT_ID" >/dev/null 2>&1; then
      echo ">>> creating service account fdb-backup"
      gcloud iam service-accounts create fdb-backup --project="$PROJECT_ID" \
        --display-name="FDB backup agents"
    fi
    # A freshly created service account is not immediately visible to
    # IAM policy binding -- the first attempt reports it doesn't exist.
    for i in {1..12}; do
      if gcloud storage buckets add-iam-policy-binding "$BUCKET" \
           --member="serviceAccount:${SA}" --role=roles/storage.objectAdmin \
           --project="$PROJECT_ID" >/dev/null 2>&1; then break; fi
      echo ">>> waiting for $SA to propagate ($i/12)"
      sleep 5
    done
    ACTIVE=$(gcloud storage hmac list --project="$PROJECT_ID" \
      --filter="serviceAccountEmail=$SA AND state=ACTIVE" \
      --format='value(accessId)' || true)
    if [[ -n "$ACTIVE" ]]; then
      echo ">>> $SA already has an active HMAC key ($ACTIVE)"
      echo ">>> to rotate: gcloud storage hmac update $ACTIVE --deactivate && just gcp-backup-hmac-create"
      exit 0
    fi
    # The secret is returned once, at creation, and is unrecoverable
    # afterwards -- so it goes straight into pass in the same pipeline.
    #
    # Retried because _gcp-allow-sa-keys may have only just lifted the
    # org-policy ban, and a policy change takes minutes to reach the
    # admission path -- until it does, this fails with a 412 naming the
    # constraint that the effective policy already says is unenforced.
    KEY=""
    for i in {1..20}; do
      if KEY=$(gcloud storage hmac create "$SA" --project="$PROJECT_ID" --format=json 2>/dev/null); then break; fi
      echo ">>> waiting for the key-creation policy to propagate ($i/20)"
      sleep 30
    done
    if [[ -z "$KEY" ]]; then
      echo "could not mint an HMAC key for $SA" >&2
      exit 1
    fi
    ACCESS_ID=$(printf '%s' "$KEY" | jq -r '.metadata.accessId')
    SECRET=$(printf '%s' "$KEY" | jq -r '.secret')
    printf '%s\n' "$ACCESS_ID" | pass insert -m -f {{ PASS_ENV }}/storage/ids/hmac >/dev/null
    printf '%s\n' "$SECRET" | pass insert -m -f {{ PASS_ENV }}/storage/hmac >/dev/null
    echo ">>> minted $ACCESS_ID and stored it in {{ PASS_ENV }}/storage/{ids/hmac,hmac}"

# Generate the AES-256 key FDB encrypts its backup files with; store it in pass.
gcp-backup-key-create:
    #!/usr/bin/env zsh
    set -euo pipefail
    # Stored rather than generated per install, and the one secret in
    # this repo that breaks the usual rule: nothing outside holds the
    # matching half, but the backup does, and the backup outlives every
    # cluster that could regenerate it. Lose this and the bucket is a
    # pile of undecryptable objects.
    #
    # Base64 because that is what a Secret's `data` takes verbatim --
    # the chart writes it through unchanged, and the agents see the 32
    # raw bytes fdbbackup wants.
    ENTRY={{ PASS_ENV }}/backup/fdb-encryption
    if pass show "$ENTRY" >/dev/null 2>&1; then
      echo ">>> $ENTRY already exists"
      exit 0
    fi
    openssl rand -base64 32 | pass insert -m -f "$ENTRY" >/dev/null
    echo ">>> generated $ENTRY"

# Print the blobstore URL FDB backs this environment up to.
[private]
_gcp-fdb-backup-url:
    #!/usr/bin/env zsh
    set -euo pipefail
    ACCESS_ID=$(pass show {{ PASS_ENV }}/storage/ids/hmac | head -1)
    BUCKET=$(just _gcp-backup-bucket-name)
    # Port 80 and secure_connection=0 -- FDB's blobstore has no usable
    # trust store and the operator cannot pass it a CA, so the transport
    # is plaintext and the files are encrypted instead. See
    # infra/helm/queenswood/templates/fdb-backup.yaml.
    printf 'blobstore://%s@storage.googleapis.com:80/%s?bucket=%s&region=%s&secure_connection=0\n' \
      "$ACCESS_ID" "$(just _gcp-fdb-backup-name)" "$BUCKET" '{{ REGION }}'

# Print FDB's backup name -- <service>/<backup-type>, matching the chart.
[private]
_gcp-fdb-backup-name:
    @printf 'fdb/continuous\n'

# Print the name of a running FDB backup-agent pod on GKE.
[private]
_gcp-fdb-agent-pod:
    #!/usr/bin/env zsh
    set -euo pipefail
    KCTX=$(just _gke-ctx)
    POD=$(kubectl --context "$KCTX" -n {{ QUEENSWOOD_ENV }} get pods \
      -o name --field-selector=status.phase=Running \
      | grep 'fdb-backup-agents' | head -1 | cut -d/ -f2 || true)
    if [[ -z "$POD" ]]; then
      echo "no running FDB backup agent in {{ QUEENSWOOD_ENV }}" >&2
      exit 1
    fi
    printf '%s\n' "$POD"

# List the versions the FDB backup can be restored to.
gcp-fdb-restore-points:
    #!/usr/bin/env zsh
    set -euo pipefail
    # For the cases gcp-fdb-export cannot cover: a cluster that died
    # without a teardown, or a recovery that wants an earlier point than
    # the last one because the newest data is the problem.
    KCTX=$(just _gke-ctx)
    POD=$(just _gcp-fdb-agent-pod)
    URL=$(just _gcp-fdb-backup-url)
    kubectl --context "$KCTX" -n {{ QUEENSWOOD_ENV }} exec "$POD" -c foundationdb -- \
      fdbbackup describe -d "$URL" --json 2>/dev/null \
      | jq -r '
        "restorable:  \(.Restorable)",
        "encrypted:   \(.FileLevelEncryption)",
        "",
        "oldest:      \(.MinRestorablePoint.Version)  (\(.MinRestorablePoint.RelativeDays * -24 | round)h ago)",
        "newest:      \(.MaxRestorablePoint.Version)  (\(.MaxRestorablePoint.RelativeDays * -24 | round)h ago)",
        "",
        "snapshots (\(.Snapshots | length)):",
        (.Snapshots[] | "  \(.End.Version)  \(if .Restorable then "restorable" else "incomplete" end)  \(.TotalBytes) bytes")'
    echo
    echo ">>> any version between oldest and newest is restorable, not just a snapshot"
    echo ">>> choose one with: pass insert -e -f {{ PASS_ENV }}/backup/fdb-restore-version"

# Close off the FDB backup and record the version it can be restored to.
gcp-fdb-export:
    #!/usr/bin/env zsh
    set -euo pipefail
    # Unlike the CloudSQL export, this runs BEFORE the workload
    # namespace is drained: the agents that flush the backup live in
    # that namespace and need a live cluster to read from. Nothing here
    # copies data -- the continuous backup has been shipping it all
    # along. What this does is stop the backup cleanly so the last
    # mutations land, then write down the version to restore to.
    KCTX=$(just _gke-ctx)
    POD=$(just _gcp-fdb-agent-pod)
    URL=$(just _gcp-fdb-backup-url)

    echo ">>> waiting for the backup to be restorable"
    for i in {1..60}; do
      R=$(kubectl --context "$KCTX" -n {{ QUEENSWOOD_ENV }} get foundationdbbackup \
        {{ QUEENSWOOD_ENV }}-fdb -o jsonpath='{.status.backupDetails.restorable}' 2>/dev/null || true)
      if [[ "$R" == "true" ]]; then break; fi
      if (( i == 60 )); then
        echo "backup never became restorable -- NOT safe to tear down" >&2
        exit 1
      fi
      sleep 10
    done

    # -w waits for the backup to finish flushing rather than only
    # submitting the request.
    echo ">>> discontinuing the backup"
    kubectl --context "$KCTX" -n {{ QUEENSWOOD_ENV }} exec "$POD" -c foundationdb -- \
      fdbbackup discontinue -t default -w -C /var/dynamic-conf/fdb.cluster \
      || echo "  (discontinue reported an error -- checking restorability anyway)" >&2

    VERSION=$(kubectl --context "$KCTX" -n {{ QUEENSWOOD_ENV }} exec "$POD" -c foundationdb -- \
      fdbbackup describe -d "$URL" --json 2>/dev/null \
      | jq -r 'select(.Restorable == true) | .MaxRestorablePoint.Version' || true)
    if [[ -z "$VERSION" || "$VERSION" == "null" ]]; then
      echo "backup is not restorable -- NOT safe to tear down" >&2
      exit 1
    fi
    # Recorded rather than rediscovered at restore time: the rebuilt
    # cluster backs up into the same container, and its versions bear no
    # relation to this one's -- so "the latest restorable point" could
    # be the new empty cluster's.
    printf '%s\n' "$VERSION" | pass insert -m -f {{ PASS_ENV }}/backup/fdb-restore-version >/dev/null
    echo ">>> restorable to version $VERSION; recorded in {{ PASS_ENV }}/backup/fdb-restore-version"

# There is no gcp-fdb-import. A restore is declared, not run:
# `fdb.restore.version` in the queenswood chart names the version a
# cluster's data should come from, and the chart's restore Job acts
# on it before the migrator writes anything. A recipe could not do
# the same job -- by the time one could be typed the migrator has
# already saved record metadata, and FDB refuses to restore into a
# non-empty destination. The version above travels to the chart via
# the root Application, so `gcp-up` restores on its own.

# List the most recent Keycloak realm exports available to restore from.
gcp-keycloak-restore-points count="5":
    #!/usr/bin/env zsh
    set -euo pipefail
    PROJECT_ID=$(just _gcp-project-id)
    BUCKET=$(just _gcp-backup-bucket)
    CURRENT=$(gcloud storage cat "$BUCKET/keycloak/realms/LATEST" \
      --project="$PROJECT_ID" 2>/dev/null | head -1 || true)
    # Sorted by name, which for this layout is chronological:
    # keycloak/realms/<YYYY>/<MM>/<DD>/<HHMMSSZ>/. Listed by manifest
    # because there is one per export, where realm files are one each.
    gcloud storage ls -l "$BUCKET/keycloak/realms/**/manifest.json" \
      --project="$PROJECT_ID" 2>/dev/null \
      | grep '^ ' | sort -k3 | tail -{{ count }} \
      | while read -r bytes created url _; do
          prefix=${url#$BUCKET/}
          prefix=${prefix%/manifest.json}
          marker="  "
          if [[ "$prefix" == "$CURRENT" ]]; then marker="* "; fi
          printf '%s%s  %s\n' "$marker" "$prefix" "$created"
        done
    echo
    echo ">>> * is what LATEST points at, and what gcp-down last recorded"
    echo ">>> choose one with: pass insert -e -f {{ PASS_ENV }}/backup/keycloak-restore-realms"

# Create the identity the Keycloak realm-import Job reads exports with.
gcp-keycloak-restore-sa-create:
    #!/usr/bin/env zsh
    set -euo pipefail
    # Reading an object from the backup bucket is the whole of it. The
    # CloudSQL import this replaced needed `cloudsql.instances.import`
    # -- carried among predefined roles only by `cloudsql.admin`, which
    # can delete the instance, so it took a custom role -- plus a
    # binding for the instance's own service agent, whose identity is
    # per-instance and so had to be re-granted after every rebuild.
    # Importing through Keycloak rather than underneath it leaves none
    # of that.
    PROJECT_ID=$(just _gcp-project-id)
    BUCKET=$(just _gcp-backup-bucket)
    SA=$(just _gcp-sa-email keycloak-restore)

    if ! gcloud iam service-accounts describe "$SA" --project="$PROJECT_ID" >/dev/null 2>&1; then
      echo ">>> creating service account keycloak-restore"
      gcloud iam service-accounts create keycloak-restore --project="$PROJECT_ID" \
        --display-name="Keycloak realm-import Job"
    fi
    # A freshly created service account is not immediately visible to
    # IAM policy binding.
    for i in {1..12}; do
      if gcloud storage buckets add-iam-policy-binding "$BUCKET" \
           --member="serviceAccount:${SA}" --role=roles/storage.objectViewer \
           --project="$PROJECT_ID" >/dev/null 2>&1; then break; fi
      echo ">>> waiting for $SA to propagate ($i/12)"
      sleep 5
    done
    # Let the Job's Kubernetes SA impersonate it.
    gcloud iam service-accounts add-iam-policy-binding "$SA" --project="$PROJECT_ID" \
      --role=roles/iam.workloadIdentityUser \
      --member="serviceAccount:${PROJECT_ID}.svc.id.goog[{{ QUEENSWOOD_ENV }}/queenswood-keycloak-realm-import]" \
      >/dev/null
    echo ">>> $SA can read realm exports from $BUCKET"

# Create the identity the Keycloak export CronJob writes with.
gcp-keycloak-backup-sa-create:
    #!/usr/bin/env zsh
    set -euo pipefail
    # objectAdmin rather than objectCreator: the export rewrites the
    # LATEST pointer on every run, and overwriting an object needs
    # delete. Scoped to the backup bucket, so it is narrower than the
    # project-level CloudSQL permissions the dump path needed.
    PROJECT_ID=$(just _gcp-project-id)
    BUCKET=$(just _gcp-backup-bucket)
    SA=$(just _gcp-sa-email keycloak-backup)

    if ! gcloud iam service-accounts describe "$SA" --project="$PROJECT_ID" >/dev/null 2>&1; then
      echo ">>> creating service account keycloak-backup"
      gcloud iam service-accounts create keycloak-backup --project="$PROJECT_ID" \
        --display-name="Keycloak realm export CronJob"
    fi
    for i in {1..12}; do
      if gcloud storage buckets add-iam-policy-binding "$BUCKET" \
           --member="serviceAccount:${SA}" --role=roles/storage.objectAdmin \
           --project="$PROJECT_ID" >/dev/null 2>&1; then break; fi
      echo ">>> waiting for $SA to propagate ($i/12)"
      sleep 5
    done
    gcloud iam service-accounts add-iam-policy-binding "$SA" --project="$PROJECT_ID" \
      --role=roles/iam.workloadIdentityUser \
      --member="serviceAccount:${PROJECT_ID}.svc.id.goog[{{ QUEENSWOOD_ENV }}/queenswood-keycloak-backup]" \
      >/dev/null
    echo ">>> $SA can write realm exports to $BUCKET"

# Close off the Keycloak realm export and record the prefix to restore from.
gcp-keycloak-export:
    #!/usr/bin/env zsh
    set -euo pipefail
    # Like gcp-fdb-export and unlike the CloudSQL dump it replaces, this
    # runs BEFORE the workload namespace is drained: `kc.sh export` is a
    # Job on the Keycloak image reading the live database, so it needs
    # the cluster up. The scheduled CronJob has been exporting all
    # along; this takes one final export so the window between the last
    # scheduled run and the teardown is not lost.
    PROJECT_ID=$(just _gcp-project-id)
    BUCKET=$(just _gcp-backup-bucket)
    KCTX=$(just _gke-ctx)
    JOB="keycloak-export-$(date -u +%Y%m%d%H%M%S)"

    # Checked rather than discovered through `create job --from`
    # failing: this runs first in gcp-down, so an unhelpful error here
    # aborts the teardown with GKE still billing.
    if ! kubectl --context "$KCTX" -n {{ QUEENSWOOD_ENV }} get \
         cronjob/queenswood-keycloak-backup >/dev/null 2>&1; then
      echo "no queenswood-keycloak-backup CronJob in {{ QUEENSWOOD_ENV }}." >&2
      echo "The realm export is what makes this environment restorable," >&2
      echo "so tearing down without it loses the realm. Check that the" >&2
      echo "deployed queenswood-keycloak chart has backup.enabled, and" >&2
      echo "that 'just gcp-keycloak-backup-sa-create' has run." >&2
      exit 1
    fi

    echo ">>> running a final realm export"
    kubectl --context "$KCTX" -n {{ QUEENSWOOD_ENV }} create job "$JOB" \
      --from=cronjob/queenswood-keycloak-backup
    if ! kubectl --context "$KCTX" -n {{ QUEENSWOOD_ENV }} wait \
           --for=condition=complete --timeout=10m "job/$JOB"; then
      kubectl --context "$KCTX" -n {{ QUEENSWOOD_ENV }} logs "job/$JOB" --all-containers \
        --tail=40 >&2 || true
      echo "the realm export failed -- NOT safe to tear down" >&2
      exit 1
    fi

    # The Job writes LATEST only once every realm and the manifest are
    # uploaded, so reading it back is what confirms the export landed
    # whole rather than merely that the Job exited.
    PREFIX=$(gcloud storage cat "$BUCKET/keycloak/realms/LATEST" \
      --project="$PROJECT_ID" 2>/dev/null | head -1 || true)
    if [[ -z "$PREFIX" ]]; then
      echo "the export left no LATEST pointer -- NOT safe to tear down" >&2
      exit 1
    fi

    # Recorded rather than rediscovered at restore time, for the reason
    # FDB's version is: a rebuilt environment must import the realms
    # captured at THIS teardown. Anything newer is the rebuilt
    # environment's own first export, whose user ids are the ones that
    # duplicate the bank.
    printf '%s\n' "$PREFIX" \
      | pass insert -m -f {{ PASS_ENV }}/backup/keycloak-restore-realms >/dev/null
    echo ">>> exported $PREFIX; recorded in {{ PASS_ENV }}/backup/keycloak-restore-realms"

# There is no gcp-keycloak-import. A restore is declared, not run:
# `restore.realms` in the queenswood-keycloak chart names the export a
# realm's definition should come from, and the chart's realm-import Job
# acts on it instead of importing the committed JSON. A recipe could
# not do the same job -- the operator creates realms and never
# overwrites them, so by the time one could be typed the realm exists
# and the import is refused. The prefix above travels to the chart via
# the root Application, so `gcp-up` restores on its own.

# =========================================================================
# Diagnostics
# =========================================================================

# Print Cloud DNS nameservers (paste into registrar).
gcp-dns-check:
    kubectl --context {{ KIND_XP_CLUSTER }} -n {{ QUEENSWOOD_ENV }} \
      get managedzone.dns.gcp.m.upbound.io {{ QUEENSWOOD_DNS_ZONE }} \
      -o jsonpath='{.status.atProvider.nameServers}' \
      | tr ',' '\n' | tr -d '[]"' | sed 's/^ */  /'
    @echo

# Walk the public-traffic chain; one tick/cross/busy line per check.
gcp-health-check:
    #!/usr/bin/env zsh
    MGT=kind-xp-mp
    KCTX=$(just _gke-ctx)
    ok=$'\033[32m✓\033[0m'
    bad=$'\033[31m✗\033[0m'
    busy=$'\033[33m⏳\033[0m'

    # eq label actual expected -- ✓ if match, ✗ if empty, ⏳ otherwise.
    eq() {
      if [[ -z "$2" ]]; then printf "%s %s (missing)\n" "$bad" "$1"
      elif [[ "$2" == "$3" ]]; then printf "%s %s\n" "$ok" "$1"
      else printf "%s %s (%s)\n" "$busy" "$1" "$2"
      fi
    }
    # present label actual -- ✓ if non-empty, ✗ otherwise.
    present() {
      if [[ -n "$2" ]]; then printf "%s %s\n" "$ok" "$1"
      else printf "%s %s (not yet)\n" "$bad" "$1"
      fi
    }
    # is label actual expected -- binary (✓ on match, ✗ otherwise) for
    # terminal checks like HTTP probes where no transitional state exists.
    is() {
      if [[ "$2" == "$3" ]]; then printf "%s %s\n" "$ok" "$1"
      else printf "%s %s (%s)\n" "$bad" "$1" "${2:-no response}"
      fi
    }

    # ---- Crossplane (kind) ----
    kubectl --context $MGT get providers.pkg.crossplane.io \
      -o jsonpath='{range .items[*]}{.metadata.name} {.status.conditions[?(@.type=="Installed")].status} {.status.conditions[?(@.type=="Healthy")].status}{"\n"}{end}' 2>/dev/null \
      | while read name installed healthy; do
        if [[ -z "$name" ]]; then continue; fi
        if [[ "$installed" == "True" && "$healthy" == "True" ]]; then
          printf "%s provider/%s\n" "$ok" "$name"
        else
          printf "%s provider/%s (installed=%s healthy=%s)\n" "$busy" "$name" "$installed" "$healthy"
        fi
      done

    # ---- GCP infra (kind side) ----
    present "static IP queenswood-ingress-ip allocated" \
      "$(kubectl --context $MGT -n {{ QUEENSWOOD_ENV }} get addresses.compute.gcp.m.upbound.io queenswood-ingress-ip -o jsonpath='{.status.atProvider.address}' 2>/dev/null)"
    eq "ManagedZone {{ QUEENSWOOD_DNS_ZONE }} Ready" \
      "$(kubectl --context $MGT -n {{ QUEENSWOOD_ENV }} get managedzones.dns.gcp.m.upbound.io {{ QUEENSWOOD_DNS_ZONE }} -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null)" True
    eq "RecordSet queenswood-apex Synced" \
      "$(kubectl --context $MGT -n {{ QUEENSWOOD_ENV }} get recordsets.dns.gcp.m.upbound.io queenswood-apex -o jsonpath='{.status.conditions[?(@.type=="Synced")].status}' 2>/dev/null)" True
    eq "XPlatform queenswood Ready" \
      "$(kubectl --context $MGT -n crossplane-system get xplatform.platform.queenswood.repldriven.com queenswood -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null)" True
    present "XPlatform.status.connectionName populated" \
      "$(kubectl --context $MGT -n crossplane-system get xplatform.platform.queenswood.repldriven.com queenswood -o jsonpath='{.status.connectionName}' 2>/dev/null)"
    eq "Certificate queenswood-cert ACTIVE" \
      "$(kubectl --context $MGT -n {{ QUEENSWOOD_ENV }} get certificate.certificatemanager.gcp.m.upbound.io queenswood-cert -o jsonpath='{.status.atProvider.managed.state}' 2>/dev/null)" ACTIVE
    eq "Certificate queenswood-wildcard-cert ACTIVE" \
      "$(kubectl --context $MGT -n {{ QUEENSWOOD_ENV }} get certificate.certificatemanager.gcp.m.upbound.io queenswood-wildcard-cert -o jsonpath='{.status.atProvider.managed.state}' 2>/dev/null)" ACTIVE

    # ---- GKE workloads ----
    pending=$(kubectl --context $KCTX -n {{ QUEENSWOOD_ENV }} get pods --field-selector=status.phase=Pending --no-headers 2>/dev/null | wc -l | tr -d ' ')
    failing=$(kubectl --context $KCTX -n {{ QUEENSWOOD_ENV }} get pods --no-headers 2>/dev/null | awk '$3 ~ /(Error|CrashLoopBackOff|ImagePullBackOff)/' | wc -l | tr -d ' ')
    if (( failing > 0 )); then printf "%s pods (%d failing)\n" "$bad" "$failing"
    elif (( pending > 0 )); then printf "%s pods (%d pending)\n" "$busy" "$pending"
    else printf "%s pods\n" "$ok"
    fi
    eq "FDB cluster available" \
      "$(kubectl --context $KCTX -n {{ QUEENSWOOD_ENV }} get foundationdbcluster -o jsonpath='{.items[0].status.health.available}' 2>/dev/null)" true
    eq "cloud-sql-proxy ready" \
      "$(kubectl --context $KCTX -n {{ QUEENSWOOD_ENV }} get deploy queenswood-keycloak-cloudsql-proxy -o jsonpath='{.status.readyReplicas}/{.status.replicas}' 2>/dev/null)" "1/1"
    # The operator names the StatefulSet <Keycloak CR>-keycloak, so the
    # queenswood-keycloak CR yields queenswood-keycloak-keycloak.
    eq "Keycloak StatefulSet ready" \
      "$(kubectl --context $KCTX -n {{ QUEENSWOOD_ENV }} get statefulset queenswood-keycloak-keycloak -o jsonpath='{.status.readyReplicas}/{.status.replicas}' 2>/dev/null)" "1/1"
    eq "Gateway {{ QUEENSWOOD_ENV }}-gateway Programmed" \
      "$(kubectl --context $KCTX -n {{ QUEENSWOOD_ENV }} get gateway {{ QUEENSWOOD_ENV }}-gateway -o jsonpath='{.status.conditions[?(@.type=="Programmed")].status}' 2>/dev/null)" True

    # ---- Public DNS + HTTPS ----
    present "dig A {{ QUEENSWOOD_DOMAIN }} @8.8.8.8" \
      "$(dig +short A {{ QUEENSWOOD_DOMAIN }} @8.8.8.8 2>/dev/null)"
    is "Keycloak realm endpoint -> 200" \
      "$(curl -sIo /dev/null -w '%{http_code}' --max-time 5 https://keycloak.{{ QUEENSWOOD_DOMAIN }}/realms/queenswood/.well-known/openid-configuration 2>/dev/null)" 200
    present "dig A console.{{ QUEENSWOOD_DOMAIN }} @8.8.8.8" \
      "$(dig +short A console.{{ QUEENSWOOD_DOMAIN }} @8.8.8.8 2>/dev/null)"
    is "HTTPS console.{{ QUEENSWOOD_DOMAIN }} -> 200" \
      "$(curl -sIo /dev/null -w '%{http_code}' --max-time 5 https://console.{{ QUEENSWOOD_DOMAIN }} 2>/dev/null)" 200
    is "HTTPS {{ QUEENSWOOD_DOMAIN }} -> 200" \
      "$(curl -sIo /dev/null -w '%{http_code}' --max-time 5 https://{{ QUEENSWOOD_DOMAIN }} 2>/dev/null)" 200
    is "HTTP {{ QUEENSWOOD_DOMAIN }} -> 301" \
      "$(curl -sIo /dev/null -w '%{http_code}' --max-time 5 http://{{ QUEENSWOOD_DOMAIN }} 2>/dev/null)" 301

# Print Keycloak's CloudSQL password from `pass`, creating it on first use.
[private]
_keycloak-db-password:
    #!/usr/bin/env zsh
    set -euo pipefail
    # Generated once and kept, not generated per install: CloudSQL holds
    # the matching half, so a fresh value on a rebuilt cluster would not
    # match an instance that survived. `pass generate` is idempotent here
    # only because we check first -- it overwrites otherwise.
    ENTRY={{ PASS_ENV }}/db/keycloak
    if ! pass show "$ENTRY" >/dev/null 2>&1; then
      pass generate -n "$ENTRY" 32 >/dev/null
      echo ">>> generated $ENTRY" >&2
    fi
    pass show "$ENTRY" | head -1
