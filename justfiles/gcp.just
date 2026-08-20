# =========================================================================
# Google Cloud, from the foundation up.
#
# All new GCP work lands here, and anything still needed from
# cloud.just moves here rather than being called across -- cloud.just is
# the previous generation and shrinks as its recipes are replaced by
# managed resources. See docs/recipes/crossplane-app-deployment.md.
# =========================================================================

# Throwaway control plane used to create the folder and the management
# project, then discarded. Deliberately not XP_CLUSTER: that one is the
# previous generation's permanent plane.
BOOT_CLUSTER := "boot-mgmt"
BOOT_CTX := "kind-boot-mgmt"

# The installation these recipes act on. Names derive from the code.
CODE := "qw01"

# The region abbreviated, for the names that carry one. GCP publishes
# no short form, so the installation states it. REGION and ZONE come
# from the root Justfile.
REGION_CODE := "euw2"

# Where a management plane reconciles its own configuration from: the
# provider packages, the installation API, and the configurations under
# them. A fork sets these; `just --set SOURCE_REVISION <branch> ...`
# overrides one for a single run.
SOURCE_REPO := "https://github.com/repldriven/queenswood"
SOURCE_REVISION := "main"

# Where this project sits inside that repository. "." for a fork, which
# keeps the layout; a directory for a mirror that vendors it.
SOURCE_PATH_PREFIX := "."

# The repository this installation's manifest is committed to, which
# the management plane reconciles itself from. Empty where there is
# none, and then nothing fetches a credential for it.
MANIFEST_REPO_URL := "https://github.com/repldriven/installations"

# Where the installation manifests are checked out. A separate, private
# repository holding data and nothing executable: this one is public,
# and a manifest is identifiers. See docs/plan/cloud-just-migration.md.
#
# The default is beside this checkout, which is not where it is from a
# worktree, so it is overridable from the environment as well as with
# --set.
INSTALLATIONS_REPO := env_var_or_default("INSTALLATIONS_REPO", "../installations")

# The management cluster's kubectl context, added by
# gcp-mgmt-cluster-ctx. Every recipe acting on a cluster names the
# context on the command line rather than trusting the current one.
MGMT_CTX := CODE + "-mgmt"

# Access groups, named for the capability each carries. Roles bind to
# these and never move; membership does. The organisation set outlives
# every installation, the coded set dies with one. See ADR-0023.
ORG_ADMIN_GROUP := "grp-gcp-org-admin"
FOLDER_ADMIN_GROUP := "grp-gcp-folder-admin"
BILLING_ADMIN_GROUP := "grp-gcp-billing-admin"
SECURITY_REVIEWER_GROUP := "grp-gcp-security-reviewer"

PLATFORM_VIEWER_GROUP := "grp-gcp-" + CODE + "-platform-viewer"
PLATFORM_ADMIN_GROUP := "grp-gcp-" + CODE + "-platform-admin"
CLUSTER_ADMIN_GROUP := "grp-gcp-" + CODE + "-cluster-admin"
SECRETS_ADMIN_GROUP := "grp-gcp-" + CODE + "-secrets-admin"

# Boolean organisation policy constraints gcp-org-setup enforces. This
# is the list to add to: enforcing is idempotent, so re-running after an
# addition costs nothing and changes only what is new.
#
# Boolean only. A list constraint carries values -- allowed domains,
# permitted external-IP targets -- and belongs in a recipe that can say
# what they are.
#
# requireOsLogin ties SSH to IAM identities and stops metadata SSH keys
# working at all, which is also what CIS 4.3 asks for. It means a
# person reaches a VM as their own directory account holding
# roles/compute.osLogin, rather than by a key somebody added to project
# metadata and nobody removed.
#
# Not here on purpose, pending a decision against what an instance
# actually runs: sql.restrictPublicIp, and compute.vmExternalIpAccess,
# which is a list constraint anyway.
ORG_CONSTRAINTS := "compute.skipDefaultNetworkCreation compute.disableSerialPortAccess compute.requireOsLogin"

# Print the organisation's primary domain, which is its display name.
[private]
_gcp-domain org="":
    #!/usr/bin/env zsh
    set -e
    ORG=$(just _gcp-org-id "{{ org }}")
    gcloud organizations describe "$ORG" --format='value(displayName)'

# Print the Cloud Identity customer id, e.g. C01k1e9nw.
[private]
_gcp-customer-id org="":
    #!/usr/bin/env zsh
    set -e
    ORG=$(just _gcp-org-id "{{ org }}")
    gcloud organizations describe "$ORG" \
      --format='value(owner.directoryCustomerId)'

# Print the seed project id, found by label. One per organisation: it
# holds the identity that creates installations, so it sits above every
# folder and outlives all of them.
[private]
_gcp-seed-project:
    #!/usr/bin/env zsh
    set -e
    # Not silenced. A denial or an expired session exits non-zero with an
    # empty stdout, and swallowing that reports "no seed project" for one
    # that plainly exists.
    if ! OUT=$(gcloud projects list --filter="labels.queenswood-tier=seed" \
                 --format='value(projectId)' 2>&1); then
      echo "cannot list projects:" >&2
      printf '%s\n' "$OUT" | sed 's/^/  /' >&2
      exit 1
    fi
    ID=$(printf '%s\n' "$OUT" | head -1)
    if [[ -z "$ID" ]]; then
      echo "no seed project; run 'just gcp-boot-identity'" >&2
      exit 1
    fi
    printf '%s\n' "$ID"

# Print the bootstrap service account's email.
[private]
_gcp-boot-sa:
    @printf 'sa-{{ CODE }}-boot@%s.iam.gserviceaccount.com\n' "$(just _gcp-seed-project)"

# Print the billing account the bootstrap identity may bill projects to.
[private]
_gcp-billing-account:
    #!/usr/bin/env zsh
    set -e
    # Asked of the identity rather than of the operator. It holds
    # billing.user on exactly the account the organisation gave it,
    # where a person may see several and the first is not necessarily
    # the right one. Ambiguity is an error here rather than a guess:
    # billing the wrong account is not something a later run corrects.
    SA=$(just _gcp-boot-sa)
    OUT=$(gcloud billing accounts list --impersonate-service-account="$SA" \
            --filter='OPEN=True' --format='value(ACCOUNT_ID)' 2>/dev/null || true)
    COUNT=$(printf '%s' "$OUT" | grep -c . || true)
    if [[ "$COUNT" -eq 0 ]]; then
      echo "no open billing account visible to $SA" >&2
      echo "grant it roles/billing.user -- see gcp-boot-identity" >&2
      exit 1
    fi
    if [[ "$COUNT" -ne 1 ]]; then
      echo "$COUNT open billing accounts visible to $SA; pass one" >&2
      printf '%s\n' "$OUT" | sed 's/^/  /' >&2
      exit 1
    fi
    printf '%s\n' "$OUT"

# Print the organisation id, discovering it when only one is visible.
[private]
_gcp-org-id org="":
    #!/usr/bin/env zsh
    set -e
    if [[ -n "{{ org }}" ]]; then
      # Checked rather than trusted: an id from elsewhere authenticates
      # fine and then 403s on every call, which surfaces as "no
      # bindings" rather than "wrong organization".
      if ! gcloud organizations describe "{{ org }}" >/dev/null 2>&1; then
        echo "organization {{ org }} is not readable by $(gcloud config get-value account 2>/dev/null)" >&2
        exit 1
      fi
      printf '%s\n' "{{ org }}"
      exit 0
    fi
    ORGS=$(gcloud organizations list --format='value(ID)' 2>/dev/null || true)
    COUNT=$(printf '%s' "$ORGS" | grep -c . || true)
    if [[ "$COUNT" -eq 0 ]]; then
      echo "no organization visible to $(gcloud config get-value account 2>/dev/null)" >&2
      echo "an organization comes from Cloud Identity, not from gcloud --" >&2
      echo "see docs/recipes/cloud-account.md" >&2
      exit 1
    fi
    if [[ "$COUNT" -ne 1 ]]; then
      echo "$COUNT organizations visible; pass one, e.g. just gcp-preflight <id>:" >&2
      gcloud organizations list >&2
      exit 1
    fi
    printf '%s\n' "$ORGS"

# Report the organisation, billing and rights held (takes an org id if ambiguous).
gcp-preflight org="":
    #!/usr/bin/env zsh
    set -e

    ME=$(gcloud config get-value account 2>/dev/null || true)
    if [[ -z "$ME" || "$ME" == "(unset)" ]]; then
      echo "not authenticated -- run: gcloud auth login" >&2
      exit 1
    fi
    echo "account           $ME"

    ORG=$(just _gcp-org-id "{{ org }}")
    SOURCE="discovered"
    if [[ -n "{{ org }}" ]]; then
      SOURCE="given"
    fi
    # Unsuppressed on purpose: a 403 here means the wrong organization,
    # and rendering that as an empty field is how it gets missed.
    CUSTOMER=$(gcloud organizations describe "$ORG" \
                 --format='value(owner.directoryCustomerId)')
    echo "organization      $ORG ($SOURCE)"
    echo "cloud identity    customer $CUSTOMER"

    BILLING=$(gcloud billing accounts list --filter='OPEN=True' \
                --format='value(ACCOUNT_ID)' 2>/dev/null | tr '\n' ' ' || true)
    if [[ -z "${BILLING// }" ]]; then
      echo "billing           NONE open -- create one in the console, no API exists"
    else
      echo "billing           $BILLING"
    fi

    # No foundation resource lives in it, but gcloud attaches it to some
    # calls as the quota project, so a stale one denies commands that
    # have nothing to do with projects.
    PROJECT=$(gcloud config get-value project 2>/dev/null || true)
    if [[ -n "$PROJECT" && "$PROJECT" != "(unset)" ]]; then
      echo "active project    $PROJECT (quota attribution only)"
    fi

    # Domain ownership is deliberately not checked here. The only thing
    # that needs it is creating a public DNS zone, which happens per
    # instance and long after this, and every read-only way to ask
    # wants an enabled API on a project that does not exist yet.

    # Direct bindings only. A role held through a group does not appear
    # here, so an absent line is a prompt to check rather than proof.
    echo
    echo "organization roles bound directly to $ME:"
    # Not fatal, unlike the describe above. Browser carries no
    # organizations.getIamPolicy, so the operating user legitimately
    # cannot read this -- and a denial must never render as "(none)".
    if ROLES=$(gcloud organizations get-iam-policy "$ORG" \
                 --flatten='bindings[].members' \
                 --filter="bindings.members:user:$ME" \
                 --format='value(bindings.role)' 2>/dev/null); then
      if [[ -z "${ROLES// }" ]]; then
        echo "  none bound directly"
      else
        printf '%s\n' "$ROLES" | sed 's/^/  /'
      fi
      echo "  (domain-wide grants are not listed here -- they name the domain)"
    else
      echo "  not readable by this account, which blocks nothing"
      echo "  (Browser carries no organizations.getIamPolicy)"
    fi

    # What the two paths need, stated rather than asserted, because the
    # bootstrap identity is granted these later -- not the operator.
    echo
    echo "to create a folder, one of these must hold on the parent:"
    echo "  roles/resourcemanager.folderAdmin"
    echo "  roles/resourcemanager.folderCreator + folderIamAdmin"
    echo "granting them at the organization needs:"
    echo "  roles/resourcemanager.organizationAdmin"

    echo
    echo "parents a folder could be created under:"
    printf '  organizations/%s\n' "$ORG"
    gcloud resource-manager folders list --organization="$ORG" \
      --format='value(name,displayName)' 2>/dev/null \
      | sed 's/^/  /' || true

# =========================================================================
# The bootstrap identity
#
# Two recipes because two accounts are involved. The operator owns the
# billing account and can create projects, but only an organisation
# admin can grant roles at the organisation -- which is the whole of
# what break-glass is for.
#
# The seed project holding the identity carries no installation code:
# one serves the organisation. The identities inside it do carry one,
# because each creates a particular installation.
# =========================================================================

# Create the bootstrap identity and your right to impersonate it (as the operator).
gcp-boot-identity org="" billing="":
    #!/usr/bin/env zsh
    set -e
    ORG=$(just _gcp-org-id "{{ org }}")
    ME=$(gcloud config get-value account)

    BILLING="{{ billing }}"
    if [[ -z "$BILLING" ]]; then
      BILLING=$(gcloud billing accounts list --filter='OPEN=True' \
                  --format='value(ACCOUNT_ID)' | head -1 || true)
    fi
    if [[ -z "$BILLING" ]]; then
      echo "no open billing account; pass one as the second argument" >&2
      exit 1
    fi

    # Same reason, and worse here: a swallowed denial reads as "none
    # exists" and mints a second seed project, consuming an id forever.
    if ! OUT=$(gcloud projects list --filter="labels.queenswood-tier=seed" \
                 --format='value(projectId)' 2>&1); then
      echo "cannot list projects:" >&2
      printf '%s\n' "$OUT" | sed 's/^/  /' >&2
      exit 1
    fi
    PROJECT=$(printf '%s\n' "$OUT" | head -1)
    if [[ -n "$PROJECT" ]]; then
      echo ">>> reusing bootstrap project $PROJECT"
    else
      PROJECT="prj-b-seed-$(openssl rand -hex 3)"
      echo ">>> creating project $PROJECT under organization $ORG"
      gcloud projects create "$PROJECT" --name=prj-b-seed \
        --organization="$ORG" --labels=queenswood-tier=seed
      echo ">>> linking billing $BILLING"
      gcloud billing projects link "$PROJECT" \
        --billing-account="$BILLING" >/dev/null
    fi

    # As the caller, who owns this project by having created it. The
    # identity inside it holds organisation roles and nothing here, so
    # it cannot enable its own APIs -- the asymmetry that makes this the
    # only place these belong.
    #
    # cloudasset is for auditing the organisation rather than for
    # building anything: queries are billed to the project they run
    # from, which is the one the reading identity lives in.
    echo ">>> enabling the APIs the identity needs"
    gcloud services enable --project="$PROJECT" \
      cloudasset.googleapis.com \
      cloudresourcemanager.googleapis.com \
      cloudbilling.googleapis.com \
      iam.googleapis.com \
      iamcredentials.googleapis.com \
      serviceusage.googleapis.com

    SA="sa-{{ CODE }}-boot@${PROJECT}.iam.gserviceaccount.com"
    if ! gcloud iam service-accounts describe "$SA" \
           --project="$PROJECT" >/dev/null 2>&1; then
      echo ">>> creating service account $SA"
      gcloud iam service-accounts create sa-{{ CODE }}-boot --project="$PROJECT" \
        --display-name="sa-{{ CODE }}-boot" \
        --description="Creates the management project"
    fi

    # A freshly created service account is not immediately visible to
    # IAM policy binding -- the first attempt reports it doesn't exist.
    echo ">>> granting roles/billing.user on billing account $BILLING"
    BOUND=""
    for i in {1..12}; do
      if gcloud billing accounts add-iam-policy-binding "$BILLING" \
           --member="serviceAccount:${SA}" --role=roles/billing.user \
           >/dev/null 2>&1; then BOUND=1; break; fi
      echo ">>> waiting for $SA to propagate ($i/12)"
      sleep 5
    done
    if [[ -z "$BOUND" ]]; then
      echo "could not grant billing.user to $SA on $BILLING" >&2
      exit 1
    fi

    # The group, never a person. Membership is then the whole of who can
    # act as this identity, and removing it is one revocation in one
    # place rather than an edit to this policy. platform-admin rather
    # than platform-viewer: assuming this identity is a write capability,
    # so it is joined for a task and left.
    DOMAIN=$(just _gcp-domain "{{ org }}")
    PLATFORM_ADMIN="{{ PLATFORM_ADMIN_GROUP }}@${DOMAIN}"
    echo ">>> letting $PLATFORM_ADMIN impersonate $SA"
    gcloud iam service-accounts add-iam-policy-binding "$SA" \
      --project="$PROJECT" --member="group:${PLATFORM_ADMIN}" \
      --role=roles/iam.serviceAccountTokenCreator >/dev/null

    # Converge on group-only: a person named here would be standing
    # authority that no group membership change could take away.
    STRAGGLERS=$(gcloud iam service-accounts get-iam-policy "$SA" \
                   --project="$PROJECT" --flatten='bindings[].members' \
                   --filter="bindings.role:roles/iam.serviceAccountTokenCreator AND bindings.members:user:*" \
                   --format='value(bindings.members)' 2>/dev/null || true)
    for member in ${(f)STRAGGLERS}; do
      echo ">>> removing direct binding for $member"
      gcloud iam service-accounts remove-iam-policy-binding "$SA" \
        --project="$PROJECT" --member="$member" \
        --role=roles/iam.serviceAccountTokenCreator >/dev/null
    done

    echo
    echo ">>> project  $PROJECT"
    echo ">>> identity $SA"
    echo ">>> act as it by joining $PLATFORM_ADMIN, and leave after"
    echo ">>> if it holds no organization roles yet, an organization admin"
    echo ">>> runs: just gcp-boot-org-roles"

# Grant the bootstrap identity its organisation roles (as an org admin).
gcp-boot-org-roles org="":
    #!/usr/bin/env zsh
    set -e
    ORG=$(just _gcp-org-id "{{ org }}")
    SA=$(just _gcp-boot-sa)

    # folderCreator + folderIamAdmin rather than folderAdmin: together
    # they create a folder and grant roles inside it, and cannot delete
    # one. projectCreator is org-scoped because the folder it will
    # create does not exist yet.
    #
    # browser is read-only and not optional: GCP masks a project that
    # does not exist as permission denied unless the caller can read the
    # hierarchy, and an identity that cannot tell "absent" from
    # "forbidden" never gets past its first observation to create one.
    for role in \
      roles/resourcemanager.folderCreator \
      roles/resourcemanager.folderIamAdmin \
      roles/resourcemanager.projectCreator \
      roles/orgpolicy.policyAdmin \
      roles/cloudasset.viewer \
      roles/browser ; do
      echo ">>> granting $role on organization $ORG"
      gcloud organizations add-iam-policy-binding "$ORG" \
        --member="serviceAccount:${SA}" --role="$role" \
        --condition=None >/dev/null
    done
    echo
    echo ">>> $SA can now create the folder"

# Report the organisation policies in force, at the org and the folder.
gcp-policy-status org="" folder="":
    #!/usr/bin/env zsh
    set -e
    ORG=$(just _gcp-org-id "{{ org }}")
    SA=$(just _gcp-boot-sa)

    FOLDER="{{ folder }}"
    if [[ -z "$FOLDER" ]]; then
      FOLDER=$(gcloud resource-manager folders list \
                 --organization="$ORG" \
                 --filter="displayName=fldr-{{ CODE }}" \
                 --format='value(name)' 2>/dev/null | head -1 || true)
    fi
    FOLDER="${FOLDER#folders/}"

    # One token for the whole run, not one per read. Minting is a
    # separate call that can fail on its own -- while a group membership
    # is propagating it fails intermittently -- and a dozen mints turn
    # that into a report where some rows are wrong and none of them says
    # so.
    #
    # Not ADC either: gcloud authenticates with its own login and never
    # reads application-default credentials, so gcp-adc-boot leaves
    # Crossplane impersonating the bootstrap identity and gcloud still
    # being you.
    TOKEN=""
    AS="you"
    if ! gcloud resource-manager org-policies list --organization="$ORG" \
           --format='value(constraint)' >/dev/null 2>&1; then
      for attempt in 1 2 3; do
        TOKEN=$(gcloud auth print-access-token \
                  --impersonate-service-account="$SA" 2>/dev/null || true)
        if [[ -n "$TOKEN" ]]; then break; fi
        sleep 3
      done
      if [[ -z "$TOKEN" ]]; then
        echo "cannot read organisation policies as you, and cannot" >&2
        echo "impersonate $SA. What GCP said:" >&2
        gcloud auth print-access-token --impersonate-service-account="$SA" \
          2>&1 | head -3 | sed 's/^/  /' >&2
        echo >&2
        echo "reading these needs orgpolicy.policyViewer, carried by" >&2
        echo "{{ SECURITY_REVIEWER_GROUP }} -- which is populated, so" >&2
        echo "this should need no break-glass. If it does, that binding" >&2
        echo "has not been applied: run gcp-groups-bind." >&2
        echo >&2
        echo "The fallback is impersonating the bootstrap identity," >&2
        echo "which needs grp-gcp-{{ CODE }}-platform-admin and grants" >&2
        echo "far more than reading. gcp-adc-boot does not help: gcloud" >&2
        echo "does not read ADC." >&2
        exit 1
      fi
      AS="$SA"
      export CLOUDSDK_AUTH_ACCESS_TOKEN="$TOKEN"
    fi
    echo "read as $AS"
    echo

    echo "organisation $ORG"
    echo "policies set here:"
    OUT=$(gcloud resource-manager org-policies list --organization="$ORG" \
            --format='value(constraint)' 2>/dev/null || true)
    printf '%s\n' "${OUT:-  none}" | sed 's/^/  /'

    if [[ -n "$FOLDER" ]]; then
      echo
      echo "folder $FOLDER"
      echo "policies set here:"
      if OUT=$(gcloud resource-manager org-policies list --folder="$FOLDER" \
                 --format='value(constraint)' 2>/dev/null); then
        printf '%s\n' "${OUT:-  none}" | sed 's/^/  /'
      else
        echo "  not readable"
      fi
    fi

    # What is in force, which neither listing above answers. A
    # constraint set at the organisation and unset at the folder still
    # binds -- and GCP enforces some by default, which are in force
    # while set nowhere at all.
    #
    # The list is the constraints this project has an opinion about, not
    # GCP's. Anything absent from it is unexamined rather than judged
    # safe.
    echo
    echo "in force on ${FOLDER:-the organisation}, constraint by constraint:"
    SCOPE="--organization=$ORG"
    if [[ -n "$FOLDER" ]]; then SCOPE="--folder=$FOLDER"; fi
    #
    # Both spellings where both exist. GCP has two generations of
    # constraint id and the newer one carries a .managed. infix: asking
    # the legacy name about an organisation that enforces the managed
    # one answers "not set" while the protection is fully in place,
    # which is the most dangerous wrong answer this recipe could give.
    for c in \
      compute.skipDefaultNetworkCreation \
      compute.disableSerialPortAccess \
      compute.requireOsLogin \
      compute.vmExternalIpAccess \
      iam.disableServiceAccountKeyCreation \
      iam.managed.disableServiceAccountKeyCreation \
      iam.disableServiceAccountKeyUpload \
      iam.automaticIamGrantsForDefaultServiceAccounts \
      iam.allowedPolicyMemberDomains \
      sql.restrictPublicIp \
      storage.uniformBucketLevelAccess ; do
      # Matched against the document rather than a projection of it: an
      # unset boolean answers as an empty booleanPolicy, and asking a
      # field projection for a key that is not there returns a
      # separator, which is not empty and reads as a value.
      # Retried: eleven reads in a row against this API returns a
      # denial for some of them, and a denial that reads as "not set"
      # about a security control is the wrong way to be wrong.
      OUT=""
      OK=0
      for attempt in 1 2 3; do
        if OUT=$(gcloud resource-manager org-policies describe "$c" $SCOPE \
                   --effective --format=yaml 2>/dev/null); then
          OK=1
          break
        fi
        sleep 2
      done
      if [[ "$OK" -eq 1 ]]; then
        case "$OUT" in
          *"enforced: true"*)   STATE="enforced" ;;
          *"enforced: false"*)  STATE="explicitly off" ;;
          *"allValues: ALLOW"*) STATE="list: allow all" ;;
          *"allValues: DENY"*)  STATE="list: deny all" ;;
          *allowedValues*)      STATE="list: some allowed" ;;
          *deniedValues*)       STATE="list: some denied" ;;
          *)                    STATE="not set" ;;
        esac
      else
        STATE="NOT READABLE"
      fi
      printf '  %-52s %s\n' "$c" "$STATE"
    done

# Apply what an organisation needs once: policies, and what audits them.
gcp-org-setup org="":
    #!/usr/bin/env zsh
    set -e
    ORG=$(just _gcp-org-id "{{ org }}")
    SA=$(just _gcp-boot-sa)

    # As the caller where the caller can, which is the point of
    # {{ ORG_ADMIN_GROUP }} holding policyAdmin: reaching the bootstrap
    # identity instead means standing in a group that can also
    # impersonate the platform identity, which administers every cluster
    # in the folder. More power for the same act.
    #
    # Reading org policy is the probe. Nothing grants it but policyAdmin
    # and policyViewer, so being able to read is a fair proxy for being
    # able to write, and a wrong guess fails loudly on the first enforce
    # rather than silently.
    if ! gcloud resource-manager org-policies list --organization="$ORG" \
           --format='value(constraint)' >/dev/null 2>&1; then
      TOKEN=""
      for attempt in 1 2 3; do
        TOKEN=$(gcloud auth print-access-token \
                  --impersonate-service-account="$SA" 2>/dev/null || true)
        if [[ -n "$TOKEN" ]]; then break; fi
        sleep 3
      done
      if [[ -z "$TOKEN" ]]; then
        echo "cannot set organisation policy as you, and cannot" >&2
        echo "impersonate $SA." >&2
        echo >&2
        echo "join {{ ORG_ADMIN_GROUP }} for the task -- it carries" >&2
        echo "policyAdmin -- then leave. Membership takes a while to" >&2
        echo "propagate after being granted." >&2
        exit 1
      fi
      export CLOUDSDK_AUTH_ACCESS_TOKEN="$TOKEN"
    fi

    # At the organisation rather than the folder. Every folder inherits
    # it, including installations that do not exist yet, and a folder
    # inside an organisation somebody else owns is their act rather than
    # ours.
    echo ">>> organisation $ORG"
    for c in {{ ORG_CONSTRAINTS }}; do
      echo ">>> enforcing $c"
      gcloud resource-manager org-policies enable-enforce "$c" \
        --organization="$ORG"
    done

    echo
    echo ">>> what is in force now: just gcp-policy-status"

# Scan the organisation against CIS, with the accepted findings muted.
gcp-scan org="" framework="cis_5.0_gcp":
    #!/usr/bin/env zsh
    set -e
    ORG=$(just _gcp-org-id "{{ org }}")

    # Reading needs securityReviewer and cloudasset.viewer at the
    # organisation, both carried by {{ SECURITY_REVIEWER_GROUP }},
    # which is populated: auditing who holds what must never require
    # the power to change it.
    #
    # Authentication is ADC rather than gcloud's own login, so a scan
    # runs as whatever ADC impersonates -- gcp-adc-boot points it at the
    # bootstrap identity, which is not you and needs a group you should
    # not be standing in.
    echo ">>> scanning organisation $ORG against {{ framework }}"
    prowler gcp \
      --organization-id "$ORG" \
      --compliance "{{ framework }}" \
      --mutelist-file infra/security/prowler-mutelist.yaml

# Show the bootstrap identity, its rights, and who may impersonate it.
gcp-boot-status org="":
    #!/usr/bin/env zsh
    set -e
    ORG=$(just _gcp-org-id "{{ org }}")
    PROJECT=$(just _gcp-seed-project)
    SA=$(just _gcp-boot-sa)
    echo "project   $PROJECT"
    echo "identity  $SA"
    # Captured before printing: piping into sed takes sed's exit status,
    # so a denial would print nothing and read as "none".
    echo "organization roles:"
    if OUT=$(gcloud organizations get-iam-policy "$ORG" \
               --flatten='bindings[].members' \
               --filter="bindings.members:serviceAccount:$SA" \
               --format='value(bindings.role)' 2>/dev/null); then
      printf '%s\n' "${OUT:-  none}" | sed 's/^/  /'
    else
      echo "  not readable by this account"
    fi

    echo "may impersonate it:"
    if OUT=$(gcloud iam service-accounts get-iam-policy "$SA" \
               --project="$PROJECT" --flatten='bindings[].members' \
               --filter="bindings.role:roles/iam.serviceAccountTokenCreator" \
               --format='value(bindings.members)' 2>/dev/null); then
      printf '%s\n' "${OUT:-  nobody}" | sed 's/^/  /'
    else
      echo "  not readable by this account"
    fi

# =========================================================================
# Access groups
#
# Roles bind to groups, and membership is the only thing that moves
# afterwards. Nobody stands in the admins group: break-glass is a
# Workspace super admin adding a member, which the Admin audit log
# records -- unlike switching gcloud's active account, which nothing does.
# =========================================================================

# Bind the access groups' roles at the organisation (as a super admin).
gcp-groups-bind org="":
    #!/usr/bin/env zsh
    set -e
    # The groups themselves are made in the Admin console, not here.
    # Cloud Identity writes go through ADC and require a quota project,
    # and at foundation time no project exists to name. Group reads and
    # IAM bindings need none, so this verifies and binds.
    ORG=$(just _gcp-org-id "{{ org }}")
    DOMAIN=$(just _gcp-domain "{{ org }}")

    # cloudasset.viewer beside securityReviewer, because a scanner
    # enumerates before it reads: without it, auditing the organisation
    # means naming every project by hand, which is wrong the moment an
    # instance creates one. It reads the asset inventory -- what exists
    # -- rather than the resources themselves, so it is a smaller grant
    # than the roles/viewer a scanner also wants, and that one is still
    # an open question rather than an omission.
    #
    # browser on the platform-viewer group is the one place an
    # installation group is bound at the organisation. Hierarchy metadata
    # only, and the tooling cannot reach a folder without first resolving
    # the organisation that holds it. organizationViewer would add
    # nothing: its one permission is already in browser. Where the
    # organisation is not ours, this is whatever it grants its own people.
    MISSING=0
    for spec in \
      "{{ ORG_ADMIN_GROUP }}:roles/resourcemanager.organizationAdmin" \
      "{{ ORG_ADMIN_GROUP }}:roles/orgpolicy.policyAdmin" \
      "{{ FOLDER_ADMIN_GROUP }}:roles/resourcemanager.folderAdmin" \
      "{{ SECURITY_REVIEWER_GROUP }}:roles/iam.securityReviewer" \
      "{{ SECURITY_REVIEWER_GROUP }}:roles/cloudasset.viewer" \
      "{{ SECURITY_REVIEWER_GROUP }}:roles/orgpolicy.policyViewer" \
      "{{ BILLING_ADMIN_GROUP }}:" \
      "{{ PLATFORM_VIEWER_GROUP }}:roles/browser" \
      "{{ PLATFORM_ADMIN_GROUP }}:" \
      "{{ CLUSTER_ADMIN_GROUP }}:" \
      "{{ SECRETS_ADMIN_GROUP }}:" ; do
      NAME="${spec%%:*}"
      ROLE="${spec#*:}"
      EMAIL="${NAME}@${DOMAIN}"

      if [[ -z "$ROLE" ]]; then
        echo ">>> $EMAIL is bound below the organisation, not here"
        continue
      fi

      # No Cloud Identity lookup first: IAM validates the principal
      # itself and says so plainly, while every read of the groups API
      # wants a quota project there is no project to provide.
      #
      # Retried, because membership of the group that permits this is
      # resolved per request and flaps for a while after it is granted:
      # a binding can be refused between two that succeed. Reporting
      # what GCP said rather than guessing at the cause, since the same
      # failure shape covers a group that does not exist, a role that
      # cannot be granted here, and a permission that will be there in
      # a minute.
      echo ">>> binding $ROLE to $EMAIL on organization $ORG"
      BOUND=0
      for attempt in 1 2 3; do
        if ERR=$(gcloud organizations add-iam-policy-binding "$ORG" \
                   --member="group:${EMAIL}" --role="$ROLE" \
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
    done

    if [[ "$MISSING" -ne 0 ]]; then
      exit 1
    fi

    echo
    echo ">>> bound. Only {{ SECURITY_REVIEWER_GROUP }} and"
    echo ">>> {{ PLATFORM_VIEWER_GROUP }} carry anyone: the rest"
    echo ">>> are break-glass, joined for a task and left."
    echo ">>> billing: as an administrator of the billing account, run"
    echo ">>>   gcloud billing accounts add-iam-policy-binding <account-id> \\"
    echo ">>>     --member=group:{{ BILLING_ADMIN_GROUP }}@${DOMAIN} --role=roles/billing.admin"

# =========================================================================
# Acting as the bootstrap identity
#
# Crossplane authenticates from a credentials file, so the plane needs
# ADC that impersonates the bootstrap identity -- no key, and revocable
# in one command. Membership of the platform-admin group is what permits
# it, so join before bringing a plane up and leave once it is down.
# =========================================================================

# Point ADC at the bootstrap identity (interactive, opens a browser).
gcp-adc-boot:
    #!/usr/bin/env zsh
    set -e
    SA=$(just _gcp-boot-sa)
    echo ">>> ADC will impersonate $SA"
    gcloud auth application-default login --impersonate-service-account="$SA"

# Revoke ADC. Run this as soon as the plane is gone.
gcp-adc-revoke:
    @gcloud auth application-default revoke --quiet

# Show what ADC is, without printing any secret.
gcp-adc-status:
    #!/usr/bin/env zsh
    set -e
    F="$HOME/.config/gcloud/application_default_credentials.json"
    if [[ ! -f "$F" ]]; then
      echo "no ADC"
      exit 0
    fi
    TYPE=$(grep -o '"type"[^,]*' "$F" | head -1 | sed 's/.*: *"//; s/"//')
    SA=$(grep -o 'serviceAccounts/[^:]*' "$F" | head -1 | sed 's|serviceAccounts/||')
    echo "type         ${TYPE:-unknown}"
    echo "impersonates ${SA:-nothing}"
    # Exercised, not described. A file that reads correctly still fails
    # once its source credential needs reauthentication, and that error
    # then surfaces inside a controller that cannot prompt for one.
    if ERR=$(gcloud auth application-default print-access-token 2>&1 >/dev/null); then
      echo "token        mints"
    else
      echo "token        FAILS -- run 'just gcp-adc-boot'"
      printf '%s\n' "$ERR" | sed 's/^/  /'
      exit 1
    fi

# =========================================================================
# The throwaway control plane
#
# Exists for as long as it takes to create the folder and the management
# project, and is then discarded. The composite it applies is orphaned
# rather than deleted with it.
# =========================================================================

# Stand up kind + Crossplane + the GCP provider, credentialed by ADC.
gcp-plane-up:
    #!/usr/bin/env zsh
    set -e
    F="$HOME/.config/gcloud/application_default_credentials.json"
    if [[ ! -f "$F" ]]; then
      echo "no ADC -- run 'just gcp-adc-boot' first" >&2
      exit 1
    fi
    if ! grep -q impersonated_service_account "$F"; then
      echo "ADC is not impersonating the bootstrap identity --" >&2
      echo "run 'just gcp-adc-boot'" >&2
      exit 1
    fi
    # Before kind and Helm, not after. A credential that needs
    # reauthentication fails only when the provider first calls out,
    # which is ten minutes later and reported as a folder that cannot
    # be observed.
    if ! ERR=$(gcloud auth application-default print-access-token 2>&1 >/dev/null); then
      echo "ADC cannot mint a token -- run 'just gcp-adc-boot'" >&2
      printf '%s\n' "$ERR" | sed 's/^/  /' >&2
      exit 1
    fi

    kind get clusters | grep -q '^{{ BOOT_CLUSTER }}$' \
      || kind create cluster --name '{{ BOOT_CLUSTER }}'

    # Crossplane from the boot-management-plane chart, providers from
    # applies on the real plane. Both, because the chart templates
    # Provider and Function CRs in the same release as the subchart that
    # installs their CRDs, so Helm rejects the render on a new cluster --
    # its provider block is an audit re-export, not an install path.
    # Argo is off: a plane that lives for minutes reconciles nothing.
    helm dependency build infra/helm/boot-management-plane >/dev/null
    helm upgrade --install boot-management-plane infra/helm/boot-management-plane \
      --kube-context {{ BOOT_CTX }} \
      --namespace crossplane-system --create-namespace \
      --set argo-cd.enabled=false --set providers.enabled=false --wait

    # Only what this plane needs: a folder, a project and its APIs
    # Narrowed to the providers whose kinds the composite composes,
    # which is what this plane has to reconcile before handing over: a
    # folder, a project and its APIs (cloudplatform), a network and a
    # GKE cluster (compute, container), helm to install onto that
    # cluster, and secretmanager for the container the plane's own
    # credential is stored in. dns, sql, storage and certificatemanager
    # compose nothing yet and belong to the management plane rather
    # than to a cluster that lives for minutes.
    #
    # The rule is what the composite composes, not what an instance
    # eventually needs -- a composed kind this plane cannot store fails
    # the whole pipeline, not just its own resource. Versions come from
    # the manifest Argo applies, so only the set is narrowed.
    MANIFEST=infra/platform/crossplane-providers/providers.yml
    for name in provider-gcp-cloudplatform provider-gcp-compute \
                provider-gcp-container provider-gcp-secretmanager \
                provider-helm function-patch-and-transform \
                function-go-templating function-auto-ready ; do
      PKG=$(grep -A2 "name: $name\$" "$MANIFEST" | grep 'package:' | awk '{print $2}')
      if [[ -z "$PKG" ]]; then
        echo "no package for $name in $MANIFEST" >&2
        exit 1
      fi
      KIND=Provider
      case "$name" in function-*) KIND=Function ;; esac
      echo ">>> installing $PKG"
      kubectl --context {{ BOOT_CTX }} apply -f - <<YAML
    apiVersion: pkg.crossplane.io/v1
    kind: $KIND
    metadata:
      name: $name
    spec:
      package: $PKG
    YAML
    done

    echo ">>> waiting for the provider and function"
    kubectl --context {{ BOOT_CTX }} wait --for=condition=healthy --timeout=600s \
      provider.pkg.crossplane.io/provider-gcp-cloudplatform
    kubectl --context {{ BOOT_CTX }} wait --for=condition=healthy --timeout=600s \
      provider.pkg.crossplane.io/provider-gcp-container
    kubectl --context {{ BOOT_CTX }} wait --for=condition=healthy --timeout=600s \
      provider.pkg.crossplane.io/provider-gcp-secretmanager
    kubectl --context {{ BOOT_CTX }} wait --for=condition=healthy --timeout=600s \
      provider.pkg.crossplane.io/provider-helm
    kubectl --context {{ BOOT_CTX }} wait --for=condition=healthy --timeout=600s \
      function.pkg.crossplane.io/function-patch-and-transform
    kubectl --context {{ BOOT_CTX }} wait --for=condition=healthy --timeout=600s \
      function.pkg.crossplane.io/function-go-templating
    kubectl --context {{ BOOT_CTX }} wait --for=condition=healthy --timeout=600s \
      function.pkg.crossplane.io/function-auto-ready

    kubectl --context {{ BOOT_CTX }} create secret generic gcp-creds \
      -n crossplane-system --from-file=creds="$F" \
      --dry-run=client -o yaml | kubectl --context {{ BOOT_CTX }} apply -f -

    # projectID is required by the provider config and unused by a
    # Folder, which is parented by an organisation rather than a project.
    PROJECT=$(just _gcp-seed-project)
    kubectl --context {{ BOOT_CTX }} apply -f - <<YAML
    apiVersion: gcp.m.upbound.io/v1beta1
    kind: ClusterProviderConfig
    metadata:
      name: default
    spec:
      projectID: "$PROJECT"
      credentials:
        source: Secret
        secretRef:
          namespace: crossplane-system
          name: gcp-creds
          key: creds
    YAML

    # How the boot plane installs onto the management cluster. Two
    # halves, because a GKE kubeconfig carries no usable credential:
    # `credentials` is the connection Secret the composed Cluster
    # writes, holding the endpoint and the CA, and `identity` is the
    # same impersonated ADC the GCP providers use, which is what mints
    # the token. So the plane holds no key for the cluster either.
    #
    # Applied before the Secret exists, which is harmless: nothing reads
    # this config until a Release names it, and by then gcp-plane-apply
    # has reconciled the cluster.
    kubectl --context {{ BOOT_CTX }} apply -f - <<YAML
    apiVersion: helm.m.crossplane.io/v1beta1
    kind: ClusterProviderConfig
    metadata:
      name: {{ CODE }}-mgmt
    spec:
      credentials:
        source: Secret
        secretRef:
          namespace: crossplane-system
          name: gke-{{ CODE }}-c-mgmt-kubeconfig
          key: kubeconfig
      identity:
        type: GoogleApplicationCredentials
        source: Secret
        secretRef:
          namespace: crossplane-system
          name: gcp-creds
          key: creds
    YAML

    kubectl --context {{ BOOT_CTX }} apply \
      -f infra/platform/crossplane-xrds/xmanagementplane-xrd.yml
    kubectl --context {{ BOOT_CTX }} wait --for=condition=established --timeout=120s \
      xrd/xmanagementplanes.platform.repldriven.com
    kubectl --context {{ BOOT_CTX }} apply \
      -f infra/platform/crossplane-xrds/xmanagementplane-composition.yml

    echo
    echo ">>> plane ready. Next: just gcp-plane-apply <parent> [display-name]"

# Print the installation manifest, resolved, e.g. just gcp-plane-manifest organizations/<org-id>
gcp-plane-manifest parent name="" folder="" mgmt="" billing="" recovery="":
    #!/usr/bin/env zsh
    set -e
    # stdout carries the manifest and nothing else, so this can be
    # redirected straight into a file; every message below goes to
    # stderr. That file is the installation's whole record -- see
    # docs/plan/cloud-just-migration.md -- and it lives in a private
    # repository, because two of the values here are identifiers worth
    # keeping out of a public one.
    #
    # Anything generated rather than derived is minted per call, so
    # capture the output rather than re-running this and expecting the
    # same answer.
    # Deterministic, because the code is what tells one installation
    # from another -- a suffix on top of it would only obscure that.
    # Pass a name where the folder is handed to you already named.
    NAME="{{ name }}"
    if [[ -z "$NAME" ]]; then
      NAME="fldr-{{ CODE }}"
      echo ">>> naming the folder $NAME" >&2
    fi
    # Refuse to make a second installation by accident. GCP allows the
    # duplicate name, so this is the only thing that would notice -- and
    # with a deterministic name it is what catches a rebuild that meant
    # to adopt.
    if [[ -z "{{ folder }}" ]]; then
      case "{{ parent }}" in
        organizations/*) SCOPE="--organization=${${:-{{ parent }}}#organizations/}" ;;
        folders/*)       SCOPE="--folder=${${:-{{ parent }}}#folders/}" ;;
        *) echo "parent must be organizations/<id> or folders/<id>" >&2; exit 1 ;;
      esac
      EXISTING=$(gcloud resource-manager folders list $SCOPE \
                   --filter="displayName=$NAME" \
                   --format='value(name)' 2>/dev/null | head -1 || true)
      if [[ -n "$EXISTING" ]]; then
        echo "a folder named $NAME already exists under {{ parent }}: folders/$EXISTING" >&2
        echo "adopt it:  just gcp-plane-apply {{ parent }} $NAME folders/$EXISTING" >&2
        echo "or choose a different name" >&2
        exit 1
      fi
    fi
    # Omitted unless passed, and normally not passed. The account is a
    # property of the identity -- it holds billing.user on exactly one --
    # so the manifest does not record it. Once a project exists the
    # provider's late-initialisation owns forProvider.billingAccount, so
    # a manifest without this leaves billing alone rather than unsetting
    # it. gcp-plane-apply supplies it, because creating a project is the
    # one moment it is needed.
    BILLING_LINE=""
    if [[ -n "{{ billing }}" ]]; then
      BILLING_LINE="billingAccountId: \"{{ billing }}\""
    fi

    # A project id is consumed permanently and cannot be undeleted into
    # usefulness, so it is generated once and then carried in the
    # manifest. That is what stops a rebuilt plane minting a second one.
    MGMT="{{ mgmt }}"
    if [[ -z "$MGMT" ]]; then
      MGMT="prj-{{ CODE }}-c-mgmt-$(openssl rand -hex 3)"
      echo ">>> naming the management project $MGMT" >&2
    fi

    # Minted here rather than added when backups are first wanted, so a
    # from-scratch installation has somewhere to keep what it would be
    # rebuilt from without anyone deciding to. Its own id for the same
    # reason the management project's is minted once: consumed
    # permanently, and not derivable afterwards.
    RECOVERY="{{ recovery }}"
    if [[ -z "$RECOVERY" ]]; then
      RECOVERY="prj-{{ CODE }}-c-recovery-$(openssl rand -hex 3)"
      echo ">>> naming the recovery project $RECOVERY" >&2
    fi

    # Set only when the project already exists, for the same reason the
    # folder's adoption field is: a pre-set external name turns a
    # first-run create into a failed observe.
    MGMT_ADOPT=""
    if [[ -n "{{ mgmt }}" ]]; then
      # Prefixed: that is the form Crossplane records as the external
      # name, and adoption matches on it exactly.
      case "$MGMT" in
        projects/*) MGMT_ADOPT="adopt: $MGMT"; MGMT="${MGMT#projects/}" ;;
        *)          MGMT_ADOPT="adopt: projects/$MGMT" ;;
      esac
    fi

    # Resolved from the parent where that names an organisation, rather
    # than rediscovered. An argument the caller supplied and the recipe
    # then ignores is a run that fails on a question already answered --
    # and discovery fails outright where more than one organisation is
    # visible, or where a token has expired.
    PARENT="{{ parent }}"
    ORG_ARG=""
    if [[ "$PARENT" == organizations/* ]]; then
      ORG_ARG="${PARENT#organizations/}"
    fi
    DOMAIN=$(just _gcp-domain "$ORG_ARG")

    FOLDER="{{ folder }}"
    if [[ -n "$FOLDER" && "$FOLDER" != folders/* ]]; then
      FOLDER="folders/$FOLDER"
    fi
    ADOPT=""
    if [[ -n "$FOLDER" ]]; then
      ADOPT="folderId: $FOLDER"
    fi

    # region, zone and regionCode are stated rather than left to the
    # XRD's defaults. They are load-bearing -- a name carries the
    # region abbreviation, and moving either rebuilds the subnet and
    # the cluster -- so a default that changed underneath a live
    # installation would move it silently.
    cat <<YAML
    apiVersion: platform.repldriven.com/v1alpha1
    kind: XManagementPlane
    metadata:
      name: {{ CODE }}
      namespace: crossplane-system
    spec:
      code: "{{ CODE }}"
      region: "{{ REGION }}"
      regionCode: "{{ REGION_CODE }}"
      zone: "{{ ZONE }}"
      $BILLING_LINE
      # Capability -> principal. Whole IAM member strings, because the
      # principal need not be a group. A capability left out binds
      # nothing.
      access:
        platformViewer: ["group:{{ PLATFORM_VIEWER_GROUP }}@${DOMAIN}"]
        platformAdmin: ["group:{{ PLATFORM_ADMIN_GROUP }}@${DOMAIN}"]
        clusterAdmin: ["group:{{ CLUSTER_ADMIN_GROUP }}@${DOMAIN}"]
        secretsAdmin: ["group:{{ SECRETS_ADMIN_GROUP }}@${DOMAIN}"]
      recovery:
        projectId: "$RECOVERY"
      management:
        projectId: "$MGMT"
        $MGMT_ADOPT
        source:
          repoURL: "{{ SOURCE_REPO }}"
          targetRevision: "{{ SOURCE_REVISION }}"
          pathPrefix: "{{ SOURCE_PATH_PREFIX }}"
        manifestRepoURL: "{{ MANIFEST_REPO_URL }}"
      createFolder:
        parent: "{{ parent }}"
        displayName: "$NAME"
        $ADOPT
    YAML

# Create or adopt the folder, e.g. just gcp-plane-apply organizations/<org-id>
gcp-plane-apply billing="":
    #!/usr/bin/env zsh
    set -e
    # The committed manifest, not a composite assembled here. It carries
    # the adopt values, so applying it twice adopts rather than building
    # a second installation -- and it is the same document Argo
    # reconciles from, so the two planes cannot disagree about what the
    # installation is.
    FILE={{ INSTALLATIONS_REPO }}/{{ CODE }}/installation.yml
    if [[ ! -f "$FILE" ]]; then
      echo "no manifest at $FILE" >&2
      echo "a first installation is minted, then committed, then" >&2
      echo "applied -- the ids it holds are consumed permanently:" >&2
      echo "  just gcp-plane-manifest organizations/<org-id> > $FILE" >&2
      exit 1
    fi

    # Supplied here and not in the committed manifest, because creating
    # a project is the one moment it is needed. Discovered from the
    # identity's own binding, so applying does not depend on remembering
    # it, and the file never carries it.
    BILLING="{{ billing }}"
    if [[ -z "$BILLING" ]]; then
      BILLING=$(just _gcp-billing-account)
    fi

    # Merged client-side rather than templated into the file: both are
    # true of this act of creation rather than of the installation, and
    # the file has to stay the thing Argo reads. --local needs no
    # cluster and no yq.
    #
    # bootstrap because this recipe is the boot plane, and the boot
    # plane is the only thing that may install the management plane onto
    # its own cluster.
    kubectl patch --local -f "$FILE" --type merge -o yaml \
      -p "{\"spec\":{\"billingAccountId\":\"$BILLING\",\"management\":{\"bootstrap\":true}}}" \
      | kubectl --context {{ BOOT_CTX }} apply -f -

    echo ">>> applied $FILE"
    echo ">>> waiting for the folder and management project"
    kubectl --context {{ BOOT_CTX }} -n crossplane-system wait \
      --for=condition=ready --timeout=300s \
      xmanagementplane/{{ CODE }} || true
    just gcp-plane-status

# Report the composite and the folder it made.
gcp-plane-status:
    #!/usr/bin/env zsh
    set -e
    kubectl --context {{ BOOT_CTX }} -n crossplane-system get \
      xmanagementplane {{ CODE }} -o yaml 2>/dev/null \
      | grep -E 'folderId|folderName|type: (Ready|Synced)|status: "(True|False)"|reason:' \
      || echo "no composite applied yet"
    echo
    kubectl --context {{ BOOT_CTX }} -n crossplane-system \
      get folder.cloudplatform.gcp.m.upbound.io 2>/dev/null || true

# Delete the throwaway plane. The folder is orphaned, not deleted.
gcp-plane-down:
    @kind delete cluster --name {{ BOOT_CLUSTER }}

# =========================================================================
# The management cluster
#
# The durable plane, inside the installation's folder -- as opposed to
# the throwaway one above, which exists only long enough to create it.
# =========================================================================

# Print the recovery project's id, found by id prefix. Empty where the
# installation has none.
[private]
_gcp-recovery-project:
    #!/usr/bin/env zsh
    set -e
    # Same id-prefix lookup as the management project below, for the
    # same reason: the suffix is random and nothing labels the project.
    # Empty rather than an error where there is none -- an installation
    # that keeps nothing has no recovery project, and a caller that can
    # do without it should not be stopped.
    if ! OUT=$(gcloud projects list \
                 --filter="projectId ~ ^prj-{{ CODE }}-c-recovery-" \
                 --format='value(projectId)' 2>&1); then
      echo "cannot list projects:" >&2
      printf '%s\n' "$OUT" | sed 's/^/  /' >&2
      exit 1
    fi
    printf '%s\n' "${OUT%%$'\n'*}"

# Print the management project's id, found by id prefix.
[private]
_gcp-mgmt-project:
    #!/usr/bin/env zsh
    set -e
    # By id prefix: the suffix is random, so it is not derivable, and
    # nothing labels the project. Not silenced -- an expired session
    # exits non-zero with empty stdout, which would read as "no
    # management project" for one that plainly exists.
    if ! OUT=$(gcloud projects list \
                 --filter="projectId ~ ^prj-{{ CODE }}-c-mgmt-" \
                 --format='value(projectId)' 2>&1); then
      echo "cannot list projects:" >&2
      printf '%s\n' "$OUT" | sed 's/^/  /' >&2
      exit 1
    fi
    ID=$(printf '%s\n' "$OUT" | head -1)
    if [[ -z "$ID" ]]; then
      echo "no management project for {{ CODE }}" >&2
      exit 1
    fi
    printf '%s\n' "$ID"

# Print the platform identity's email.
[private]
_gcp-platform-sa:
    @printf 'sa-{{ CODE }}-platform@%s.iam.gserviceaccount.com\n' "$(just _gcp-mgmt-project)"

# Let the platform identity bill the projects it creates (as a billing admin).
gcp-platform-billing-role billing="":
    #!/usr/bin/env zsh
    set -e
    # The one grant the composition cannot make. Everything the platform
    # identity may do inside the folder is a FolderIAMMember or a
    # ProjectIAMMember, but a billing account sits above the folder, and
    # the boot identity holds billing.user rather than billing.admin --
    # so it cannot delegate what it was given. Run this as an
    # administrator of the billing account: the direct one it keeps, or
    # a member of grp-gcp-billing-admin joined for the task.
    SA=$(just _gcp-platform-sa)

    BILLING="{{ billing }}"
    if [[ -z "$BILLING" ]]; then
      # Asked of the boot identity, so this names the account the
      # installation already bills to rather than whichever one the
      # person running this happens to see first.
      BILLING=$(just _gcp-billing-account)
    fi

    echo ">>> granting roles/billing.user on $BILLING"
    echo ">>> to $SA"
    if ! gcloud billing accounts add-iam-policy-binding "$BILLING" \
           --member="serviceAccount:${SA}" --role=roles/billing.user \
           >/dev/null; then
      echo "could not grant billing.user on $BILLING" >&2
      echo "needs roles/billing.admin there -- held directly by the" >&2
      echo "account's own administrator, or through {{ BILLING_ADMIN_GROUP }}" >&2
      exit 1
    fi
    echo ">>> granted. The platform identity can now bill the projects"
    echo ">>> it creates, which is what an instance needs."

# Generate the key an instance's FDB backups are encrypted under, and
# put it in that instance's entry. Once, ever.
gcp-fdb-backup-key env label supersede="":
    #!/usr/bin/env zsh
    set -e
    # Its own recipe rather than a value typed into gcp-secret-version,
    # because the encoding is not a thing to leave to whoever is
    # holding the terminal.
    #
    # fdbbackup reads encryptionKeyPath as raw bytes. The ExternalSecret
    # base64s the Secret Manager payload on its way into the Secret, so
    # what lands on disk is the payload verbatim -- meaning the entry
    # holds 32 raw bytes and not the base64 text of them. The previous
    # generation held the text, because a chart writing into `data`
    # needed base64 and Kubernetes decoded it again. Same key, two
    # encodings, and the one that is wrong fails at a restore.
    #
    # Piped, never written down: a file you delete afterwards is not
    # deleted on a copy-on-write filesystem. And not through
    # gcp-secret-version, which strips a trailing newline -- correct for
    # a typed secret, and one key in 256 ends in 0x0a.
    SECRET="sec-{{ CODE }}-{{ env }}-{{ label }}-fdb-backup-key"
    PROJECT=$(just _gcp-recovery-project || true)
    if [[ -z "$PROJECT" ]]; then
      echo "no recovery project for {{ CODE }}" >&2
      echo "the plane composes it from recoveryProjectId -- merge that first" >&2
      exit 1
    fi
    if ! gcloud secrets describe "$SECRET" --project="$PROJECT" >/dev/null 2>&1; then
      echo "no secret $SECRET in $PROJECT" >&2
      echo "the composite makes the container -- apply it first" >&2
      exit 1
    fi
    # Refused rather than versioned. An existing backup is readable only
    # under the key it was written with, so a later version does not
    # rotate anything -- it strands every backup taken before it. Rotate
    # by starting a new generation under a new entry and keeping this
    # one until nothing readable is left under it.
    #
    # supersede=yes is for the one case that is not a rotation: a key
    # nothing has been backed up under, so there is nothing to strand.
    # An entry written before this recipe existed holds base64 text
    # rather than raw bytes, and nobody can read it back to check --
    # secretsAdmin carries no versions.access, deliberately. So the way
    # out of a wrong value is to write a right one over it.
    #
    # It supersedes rather than replaces: the entry is read at `latest`
    # and nothing pins a version, so the old one is left enabled and
    # unused. Destroying it would need a role that can also destroy the
    # key, which is the thing this entry exists to keep.
    if VERSIONS=$(gcloud secrets versions list "$SECRET" --project="$PROJECT" \
                    --filter="state=ENABLED" --format='value(name)' 2>/dev/null) \
       && [[ -n "$VERSIONS" ]]; then
      if [[ "{{ supersede }}" != "yes" ]]; then
        echo "$SECRET already holds a key, and a later version does not" >&2
        echo "rotate it -- it strands every backup taken under the current" >&2
        echo "one. Where nothing has been backed up yet, say so:" >&2
        echo "  just gcp-fdb-backup-key {{ env }} {{ label }} yes" >&2
        exit 1
      fi
      echo ">>> superseding the key in $SECRET, on your word that nothing" >&2
      echo ">>> has been backed up under it" >&2
    fi
    openssl rand 32 \
      | gcloud secrets versions add "$SECRET" --project="$PROJECT" --data-file=- \
        >/dev/null
    echo ">>> $SECRET now holds a key. It is the only copy, deliberately."

# Add a version to a Secret Manager entry, reading the value off stdin.
gcp-secret-version secret project="" file="":
    #!/usr/bin/env zsh
    set -e
    # The general form of what every credential here needs. The
    # container is composed and the value is a person's, so this adds a
    # version to something that already exists and refuses to create
    # one: a missing container means the composite has not reconciled,
    # and making it by hand leaves an entry nothing declares.
    #
    # The value never reaches the command line, so it stays out of the
    # shell's history and out of the process table.
    SECRET="{{ secret }}"

    PROJECT="{{ project }}"
    if [[ -z "$PROJECT" ]]; then
      # From the name, which carries the installation and the instance:
      # sec-<code>-c-<what> is the plane's, and
      # sec-<code>-<env>-<label>-<what> an instance's. Anything else has
      # to be named, rather than guessed at.
      REST=${SECRET#sec-{{ CODE }}-}
      if [[ "$REST" == "$SECRET" ]]; then
        echo "cannot tell which project $SECRET is in" >&2
        echo "name it: just gcp-secret-version $SECRET <project-id>" >&2
        exit 1
      fi
      SECRET_ENV=${REST%%-*}
      if [[ "$SECRET_ENV" == "c" ]]; then
        PROJECT=$(just _gcp-mgmt-project)
      else
        REST=${REST#*-}
        PROJECT=$(just _gcp-instance-project "$SECRET_ENV" "${REST%%-*}")
      fi
    fi

    # An instance owns entries that do not live in its project: a
    # backup key is named for the instance whose backups it opens and
    # composed into the recovery project, because a key in the project
    # whose data it protects is not a second copy of anything. The name
    # cannot say both, so a miss where the name points is a look in the
    # recovery project rather than a failure -- and it says which one it
    # used, since a value written to the wrong project is a container
    # nothing reads and a failure much later.
    if ! gcloud secrets describe "$SECRET" --project="$PROJECT" >/dev/null 2>&1; then
      RECOVERY=$(just _gcp-recovery-project || true)
      if [[ -n "$RECOVERY" && "$RECOVERY" != "$PROJECT" ]] \
         && gcloud secrets describe "$SECRET" --project="$RECOVERY" >/dev/null 2>&1; then
        PROJECT="$RECOVERY"
        echo ">>> $SECRET is in the recovery project $PROJECT" >&2
      else
        echo "no secret $SECRET in $PROJECT" >&2
        echo "the composite makes the container -- apply it first" >&2
        exit 1
      fi
    fi

    if [[ -n "{{ file }}" ]]; then
      if [[ ! -f "{{ file }}" ]]; then
        echo "no file at {{ file }}" >&2
        exit 1
      fi
      # Byte for byte. A key or a certificate is not text to be tidied,
      # and a file is how a multi-line value gets here at all.
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
      # No trailing newline. Secret Manager stores the bytes it is
      # given, and a consumer that reads the value whole -- Keycloak's
      # file vault does -- sends the newline as part of the credential.
      # That surfaces as the provider rejecting the client, which reads
      # as a wrong secret rather than as a newline.
      printf '%s' "$VALUE" \
        | gcloud secrets versions add "$SECRET" --project="$PROJECT" --data-file=-
    fi

    echo ">>> added a version to $SECRET in $PROJECT"
    echo ">>> external-secrets refreshes hourly; delete its pod to hurry it"

# Store the GitHub App credentials the management plane reads the manifest with.
gcp-github-app-secret pem app_id installation_id:
    #!/usr/bin/env zsh
    set -e
    # A person's act, once per App, from the secretsAdmin capability --
    # GitHub has no API that creates an App, so nothing here can be
    # declared. See docs/recipes/crossplane-app-deployment.md for the console
    # steps that produce these three values.
    #
    # The container is composed by the installation; this adds a
    # version to it. A container that does not exist yet means the
    # composite has not reconciled, which is worth saying plainly
    # rather than failing on a gcloud error.
    if [[ ! -f "{{ pem }}" ]]; then
      echo "no private key at {{ pem }}" >&2
      exit 1
    fi
    # All three together, so the identifiers travel with the key rather
    # than through a second channel. --rawfile because a PEM is
    # multi-line and has to be JSON-escaped, and because it keeps the
    # key off the command line.
    jq -n --arg app "{{ app_id }}" --arg inst "{{ installation_id }}" \
      --rawfile key "{{ pem }}" \
      '{appID: $app, installationID: $inst, privateKey: $key}' \
      | just gcp-secret-version sec-{{ CODE }}-c-github-app

# Show what the platform identity may do, and where.
gcp-platform-status:
    #!/usr/bin/env zsh
    set -e
    SA=$(just _gcp-platform-sa)
    PROJECT=$(just _gcp-mgmt-project)
    echo "identity  $SA"
    echo "project   $PROJECT"

    # Each captured before printing: piping into sed takes sed's exit
    # status, so a denial would print nothing and read as "none".
    BILLING=$(just _gcp-billing-account 2>/dev/null || true)
    echo "billing role:"
    if [[ -z "$BILLING" ]]; then
      echo "  no billing account readable from here"
    elif OUT=$(gcloud billing accounts get-iam-policy "$BILLING" \
                 --flatten='bindings[].members' \
                 --filter="bindings.members:serviceAccount:$SA" \
                 --format='value(bindings.role)' 2>/dev/null); then
      printf '%s\n' "${OUT:-  none -- run 'just gcp-platform-billing-role'}" \
        | sed 's/^/  /'
    else
      echo "  not readable by this account"
    fi

    echo "folder roles:"
    FOLDER=$(gcloud resource-manager folders list \
               --organization="$(just _gcp-org-id)" \
               --filter="displayName=fldr-{{ CODE }}" \
               --format='value(name)' 2>/dev/null | head -1 || true)
    if [[ -z "$FOLDER" ]]; then
      echo "  no folder fldr-{{ CODE }}"
    elif OUT=$(gcloud resource-manager folders get-iam-policy "$FOLDER" \
                 --flatten='bindings[].members' \
                 --filter="bindings.members:serviceAccount:$SA" \
                 --format='value(bindings.role)' 2>/dev/null); then
      printf '%s\n' "${OUT:-  none}" | sed 's/^/  /'
    else
      echo "  not readable by this account"
    fi

    # Direct bindings only, so the folder roles above are not repeated
    # here despite inheriting into this project.
    echo "management project roles:"
    if [[ -z "$PROJECT" ]]; then
      echo "  no management project"
    elif OUT=$(gcloud projects get-iam-policy "$PROJECT" \
                 --flatten='bindings[].members' \
                 --filter="bindings.members:serviceAccount:$SA" \
                 --format='value(bindings.role)' 2>/dev/null); then
      printf '%s\n' "${OUT:-  none}" | sed 's/^/  /'
    else
      echo "  not readable by this account"
    fi

# Add a kubectl context for the management cluster, named MGMT_CTX.
gcp-mgmt-cluster-ctx project="":
    #!/usr/bin/env zsh
    set -e
    CLUSTER="gke-{{ CODE }}-c-mgmt"

    PROJECT="{{ project }}"
    if [[ -z "$PROJECT" ]]; then
      PROJECT=$(just _gcp-mgmt-project)
    fi

    # Read from the cluster rather than stated here, so this cannot
    # drift from the zone the installation manifest chose.
    ZONE=$(gcloud container clusters list --project="$PROJECT" \
             --filter="name=$CLUSTER" --format='value(location)' | head -1)
    if [[ -z "$ZONE" ]]; then
      echo "no cluster $CLUSTER in $PROJECT" >&2
      exit 1
    fi

    # get-credentials makes its context current, which silently
    # retargets a shell that was working against the throwaway plane.
    # Captured and put back: this recipe adds a context, it does not
    # choose one.
    WAS=$(kubectl config current-context 2>/dev/null || true)

    gcloud container clusters get-credentials "$CLUSTER" \
      --zone="$ZONE" --project="$PROJECT"
    kubectl config rename-context \
      "gke_${PROJECT}_${ZONE}_${CLUSTER}" "{{ MGMT_CTX }}" >/dev/null 2>&1 || true

    if [[ -n "$WAS" && "$WAS" != "gke_${PROJECT}_${ZONE}_${CLUSTER}" ]]; then
      kubectl config use-context "$WAS" >/dev/null
    fi

    echo
    echo ">>> context {{ MGMT_CTX }} -> $CLUSTER in $PROJECT ($ZONE)"
    echo ">>> still on ${WAS:-none}; use --context {{ MGMT_CTX }}"
    echo ">>> fetching credentials needs only container.viewer, which you"
    echo ">>> hold. Calling the API needs container.admin: join"
    echo ">>> grp-gcp-{{ CODE }}-cluster-admin for the task, and leave."

# Print an instance project's id, discovered by prefix.
[private]
_gcp-instance-project env label:
    #!/usr/bin/env zsh
    set -e
    # By id prefix, as the management project is, and for the same
    # reason: the suffix is random and nothing labels the project.
    # Not silenced -- an expired session exits non-zero with empty
    # stdout, which would read as "no such instance" for one that
    # plainly exists.
    if ! OUT=$(gcloud projects list \
                 --filter="projectId ~ ^prj-{{ CODE }}-{{ env }}-{{ label }}-" \
                 --format='value(projectId)' 2>&1); then
      echo "cannot list projects:" >&2
      printf '%s\n' "$OUT" | sed 's/^/  /' >&2
      exit 1
    fi
    ID=$(printf '%s\n' "$OUT" | head -1)
    if [[ -z "$ID" ]]; then
      echo "no project for instance {{ CODE }}-{{ env }}-{{ label }}" >&2
      exit 1
    fi
    printf '%s\n' "$ID"

# Add a kubectl context for an instance cluster, named for the cluster.
gcp-instance-cluster-ctx env label project="":
    #!/usr/bin/env zsh
    set -e
    # The cluster carries no kind prefix, so the context is the
    # cluster's own name -- there is nothing to shorten, where the
    # management context shortens gke-{{ CODE }}-c-mgmt.
    CLUSTER="{{ CODE }}-{{ env }}-{{ label }}"

    PROJECT="{{ project }}"
    if [[ -z "$PROJECT" ]]; then
      PROJECT=$(just _gcp-instance-project "{{ env }}" "{{ label }}")
    fi

    # Read from the cluster rather than stated here, so this cannot
    # drift from the zone the instance manifest chose.
    ZONE=$(gcloud container clusters list --project="$PROJECT" \
             --filter="name=$CLUSTER" --format='value(location)' | head -1)
    if [[ -z "$ZONE" ]]; then
      echo "no cluster $CLUSTER in $PROJECT" >&2
      exit 1
    fi

    # get-credentials makes its context current, which silently
    # retargets a shell that was working against the plane. Captured
    # and put back: this recipe adds a context, it does not choose one.
    WAS=$(kubectl config current-context 2>/dev/null || true)

    gcloud container clusters get-credentials "$CLUSTER" \
      --zone="$ZONE" --project="$PROJECT"
    kubectl config rename-context \
      "gke_${PROJECT}_${ZONE}_${CLUSTER}" "$CLUSTER" >/dev/null 2>&1 || true

    if [[ -n "$WAS" && "$WAS" != "gke_${PROJECT}_${ZONE}_${CLUSTER}" ]]; then
      kubectl config use-context "$WAS" >/dev/null
    fi

    echo
    echo ">>> context $CLUSTER -> $CLUSTER in $PROJECT ($ZONE)"
    echo ">>> still on ${WAS:-none}; use --context $CLUSTER"
    echo ">>> fetching credentials needs only container.viewer, which you"
    echo ">>> hold. Calling the API needs container.admin: join"
    echo ">>> grp-gcp-{{ CODE }}-cluster-admin for the task, and leave."
