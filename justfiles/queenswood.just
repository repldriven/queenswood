# Where a management plane reconciles its own configuration from. A
# fork sets these.
SOURCE_REPO := "https://github.com/repldriven/queenswood"
SOURCE_REVISION := "main"

# Where this project sits inside that repository. "." for a fork, a
# directory for a mirror that vendors it.
SOURCE_PATH_PREFIX := "."

# The repository this installation's manifest is committed to. Empty
# where there is none, and then nothing fetches a credential for it.
MANIFEST_REPO_URL := "https://github.com/repldriven/installations"

# The instance these recipes act on, and the folder's parent for a
# first installation. Pass one to override for a single run.
ENV := env_var_or_default("QW_ENV", "")
LABEL := env_var_or_default("QW_LABEL", "")
PARENT := env_var_or_default("QW_PARENT", "")

# Which set of templates a unit is rendered from: minimal leans on the
# XRD's defaults, full states them.
SPEC := env_var_or_default("QW_SPEC", "minimal")

# Print the project id of the instance `env`-`label`.
[private]
_instance-project env label:
    #!/usr/bin/env zsh
    set -e
    ID=$(just _gcp-project "projectId ~ ^prj-{{ CODE }}-{{ env }}-{{ label }}-")
    if [[ -z "$ID" ]]; then
      echo "no project for instance {{ CODE }}-{{ env }}-{{ label }}" >&2
      exit 1
    fi
    printf '%s\n' "$ID"

# Print `key` from the installation's committed environment.
[private]
_environment-value key:
    #!/usr/bin/env zsh
    set -e
    FILE={{ INSTALLATIONS_REPO }}/{{ CODE }}/environment.yml
    if [[ ! -f "$FILE" ]]; then
      echo "no environment at $FILE" >&2
      echo "the contract is committed before anything reads it -- see" >&2
      echo "docs/recipes/infra/contract-install.md" >&2
      exit 1
    fi
    VALUE=$(sed -n 's/^  {{ key }}: *"\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' "$FILE" | head -1)
    if [[ -z "$VALUE" ]]; then
      echo "no {{ key }} in $FILE; re-render it" >&2
      exit 1
    fi
    printf '%s\n' "$VALUE"

# Print the domain the installation answers under, from its environment.
[private]
_environment-domain:
    #!/usr/bin/env zsh
    set -e
    FILE={{ INSTALLATIONS_REPO }}/{{ CODE }}/environment.yml
    if [[ ! -f "$FILE" ]]; then
      echo "no environment at $FILE" >&2
      echo "the contract is committed before anything reads it -- see" >&2
      echo "docs/recipes/infra/contract-install.md" >&2
      exit 1
    fi
    VALUE=$(python3 -c 'import sys,yaml; d=yaml.safe_load(open(sys.argv[1])) or {}; print(((d.get("data") or {}).get("dns") or {}).get("domain") or "")' "$FILE")
    if [[ -z "$VALUE" ]]; then
      echo "no dns.domain in $FILE" >&2
      echo "an installation states the name it answers under before an" >&2
      echo "environment can take a subdomain of it -- see ADR-0028" >&2
      exit 1
    fi
    printf '%s\n' "$VALUE"

# Validate an instance's env and label, and print <code>-<env>-<label>.
[private]
_queenswood-instance env label:
    #!/usr/bin/env zsh
    set -e
    if [[ -z "{{ env }}" || -z "{{ label }}" ]]; then
      echo "no instance named; export QW_ENV and QW_LABEL, or pass them" >&2
      exit 1
    fi
    if [[ ! "{{ env }}" =~ ^[a-z]$ ]]; then
      echo "env must be one lowercase letter, not {{ env }}" >&2
      # just has no named arguments, so QW_SPEC=full written after the
      # recipe lands in env and would otherwise reach GCP as a name.
      echo "a variable goes before the recipe: QW_SPEC=full just ..." >&2
      exit 1
    fi
    if [[ ! "{{ label }}" =~ ^[a-z][a-z0-9-]{0,10}[a-z0-9]$ ]]; then
      echo "label must match [a-z][a-z0-9-]{0,10}[a-z0-9], not {{ label }}" >&2
      exit 1
    fi
    printf '%s\n' "{{ CODE }}-{{ env }}-{{ label }}"

# Render the installation's environment, beside its manifest. Takes the
# folder's parent to compose one, or a folder id to adopt one instead.
queenswood-environment-manifest parent=PARENT name="" folder="" recovery="" billing="" domain="":
    #!/usr/bin/env zsh
    set -e
    ROOT={{ INSTALLATIONS_REPO }}/{{ CODE }}
    FILE="$ROOT/environment.yml"
    mkdir -p "$ROOT"
    # Committed is the line, not merely present: a plane reads from git,
    # so an uncommitted file was never applied and the id it holds means
    # nothing. A committed one may be the only record of a consumed id.
    if [[ -e "$FILE" ]]; then
      if git -C {{ INSTALLATIONS_REPO }} rev-parse --git-dir >/dev/null 2>&1 \
         && git -C {{ INSTALLATIONS_REPO }} cat-file -e \
              "HEAD:{{ CODE }}/environment.yml" 2>/dev/null; then
        echo "{{ CODE }} already has a committed environment:" >&2
        echo "  $FILE" >&2
        echo "edit it" >&2
        exit 1
      fi
      echo ">>> replacing the uncommitted $FILE" >&2
    fi

    BILLING="{{ billing }}"
    if [[ -z "$BILLING" ]]; then
      if MGMT=$(just _mgmt-project 2>/dev/null) && [[ -n "$MGMT" ]]; then
        if ! OUT=$(gcloud billing projects describe "$MGMT" \
                     --format='value(billingAccountName)' 2>&1); then
          echo "cannot read the billing account of $MGMT:" >&2
          printf '%s\n' "$OUT" | sed 's/^/  /' >&2
          exit 1
        fi
        BILLING="${OUT#billingAccounts/}"
      else
        BILLING=$(just _gcp-billing-account "$(just _seed-sa)")
      fi
    fi
    if [[ -z "$BILLING" ]]; then
      echo "no billing account; link one, or pass it, then re-run" >&2
      exit 1
    fi

    FOLDER="{{ folder }}"
    if [[ -n "$FOLDER" ]]; then
      case "$FOLDER" in
        folders/*) : ;;
        *) FOLDER="folders/$FOLDER" ;;
      esac
      FOLDER_BLOCK=$(printf '    folderId: "%s"' "$FOLDER")
      echo ">>> adopting $FOLDER" >&2
    else
      if [[ -z "{{ parent }}" ]]; then
        echo "no folder to adopt and no parent to compose one under" >&2
        echo "export QW_PARENT, or pass a folder to adopt:" >&2
        echo "  just queenswood-environment-manifest '' '' folders/<folder-id>" >&2
        exit 1
      fi
      NAME="{{ name }}"
      if [[ -z "$NAME" ]]; then
        NAME="fldr-{{ CODE }}"
        echo ">>> naming the folder $NAME" >&2
      fi
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
        echo "adopt it:  just queenswood-environment-manifest '' '' folders/$EXISTING" >&2
        echo "or choose a different name" >&2
        exit 1
      fi
      FOLDER_BLOCK=$(printf '    parent: "%s"\n    displayName: "%s"' "{{ parent }}" "$NAME")
    fi

    # Captured, not substituted inline: an empty domain renders every
    # principal as `group:grp-...@`, which IAM rejects only on apply.
    if ! DOMAIN=$(just _gcp-domain); then
      exit 1
    fi
    if [[ -z "$DOMAIN" ]]; then
      echo "no organisation domain; the access mapping would name" >&2
      echo "principals with no domain at all" >&2
      exit 1
    fi

    # Comment and key together, so an installation with no domain gets
    # neither rather than a paragraph about an absent field.
    DNS_BLOCK=""
    if [[ -n "{{ domain }}" ]]; then
      DNS_BLOCK=$(printf '%s\n' \
        '  # The name this installation answers under. Each environment' \
        '  # answers on a subdomain of it, delegated to a zone this' \
        '  # installation composes -- so this is what we were given,' \
        '  # never the apex above it, which belongs to no installation' \
        '  # at all. See ADR-0028.' \
        '  #' \
        '  # Add a zone: {name, project} beside it only where a parent' \
        '  # runs Cloud DNS and hands over a zone rather than a' \
        '  # delegation. Without one this installation composes its own' \
        '  # zones, which is the ordinary case and the only one that' \
        '  # works whatever the parent runs.' \
        '  dns:' \
        "    domain: \"{{ domain }}\"")
    fi

    # Resolved here, not in the template, which takes whole member
    # strings: a user or a principalSet:// has no domain to append.
    PLATFORM_VIEWER=$(just _gcp-member "{{ PLATFORM_VIEWER }}" "$DOMAIN")
    PLATFORM_ADMIN=$(just _gcp-member "{{ PLATFORM_ADMIN }}" "$DOMAIN")
    CLUSTER_ADMIN=$(just _gcp-member "{{ CLUSTER_ADMIN }}" "$DOMAIN")
    SECRETS_ADMIN=$(just _gcp-member "{{ SECRETS_ADMIN }}" "$DOMAIN")

    CODE="{{ CODE }}" BILLING="$BILLING" \
    MANIFEST_REPO_URL="{{ MANIFEST_REPO_URL }}" \
    REGION="{{ REGION }}" REGION_CODE="{{ REGION_CODE }}" ZONE="{{ ZONE }}" \
    DOMAIN="$DOMAIN" FOLDER_BLOCK="$FOLDER_BLOCK" DNS_BLOCK="$DNS_BLOCK" \
    PLATFORM_VIEWER="$PLATFORM_VIEWER" \
    PLATFORM_ADMIN="$PLATFORM_ADMIN" \
    CLUSTER_ADMIN="$CLUSTER_ADMIN" \
    SECRETS_ADMIN="$SECRETS_ADMIN" \
    python3 -c 'import os,sys,string; d=string.Template(open(sys.argv[1]).read()).substitute(os.environ); sys.stdout.write("\n".join(l for l in d.split("\n") if l.strip()) + "\n")' \
      infra/platform/templates/environment.yml.tmpl > "$FILE"

    echo ">>> wrote $FILE"
    echo
    echo "  billing   $BILLING"
    echo "  access"
    echo "    platformViewer  $PLATFORM_VIEWER"
    echo "    platformAdmin   $PLATFORM_ADMIN"
    echo "    clusterAdmin    $CLUSTER_ADMIN"
    echo "    secretsAdmin    $SECRETS_ADMIN"

# Print the subsidiary manifest. Takes nothing: it is the code alone.
queenswood-subsidiary-manifest:
    #!/usr/bin/env zsh
    set -e
    CODE="{{ CODE }}" \
    python3 -c 'import os,sys,string; d=string.Template(open(sys.argv[1]).read()).substitute(os.environ); sys.stdout.write("\n".join(l for l in d.split("\n") if l.strip()) + "\n")' \
      infra/platform/templates/subsidiary.yml.tmpl

# Print the installation manifest, resolved. Takes the folder's parent.
queenswood-installation-manifest parent=PARENT name="" folder="" mgmt="" billing="" recovery="":
    #!/usr/bin/env zsh
    set -e
    if [[ -z "{{ parent }}" ]]; then
      echo "no parent for the folder" >&2
      echo "export QW_PARENT, or pass it:" >&2
      echo "  just queenswood-installation-manifest organizations/<org-id>" >&2
      exit 1
    fi
    NAME="{{ name }}"
    if [[ -z "$NAME" ]]; then
      NAME="fldr-{{ CODE }}"
      echo ">>> naming the folder $NAME" >&2
    fi
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
        echo "adopt it:  just boot-mgmt-apply {{ parent }} $NAME folders/$EXISTING" >&2
        echo "or choose a different name" >&2
        exit 1
      fi
    fi
    BILLING_LINE=""
    if [[ -n "{{ billing }}" ]]; then
      BILLING_LINE="billingAccountId: \"{{ billing }}\""
    fi

    MGMT="{{ mgmt }}"
    if [[ -z "$MGMT" ]]; then
      MGMT="prj-{{ CODE }}-c-mgmt-$(openssl rand -hex 3)"
      echo ">>> naming the management project $MGMT" >&2
    fi

    RECOVERY="{{ recovery }}"
    if [[ -z "$RECOVERY" ]]; then
      RECOVERY="prj-{{ CODE }}-c-recovery-$(openssl rand -hex 3)"
      echo ">>> naming the recovery project $RECOVERY" >&2
    fi
    RECOVERY_BLOCK=$(printf '  recovery:\n    projectId: "%s"' "$RECOVERY")

    MGMT_ADOPT=""
    if [[ -n "{{ mgmt }}" ]]; then
      case "$MGMT" in
        projects/*) MGMT_ADOPT="adopt: \"$MGMT\""; MGMT="${MGMT#projects/}" ;;
        *)          MGMT_ADOPT="adopt: \"projects/$MGMT\"" ;;
      esac
    fi

    DOMAIN=$(just _gcp-domain)

    FOLDER="{{ folder }}"
    if [[ -n "$FOLDER" && "$FOLDER" != folders/* ]]; then
      FOLDER="folders/$FOLDER"
    fi
    ADOPT=""
    if [[ -n "$FOLDER" ]]; then
      ADOPT="folderId: \"$FOLDER\""
    fi

    CODE="{{ CODE }}" BILLING_LINE="$BILLING_LINE" DOMAIN="$DOMAIN" \
    RECOVERY_BLOCK="$RECOVERY_BLOCK" MGMT="$MGMT" MGMT_ADOPT="$MGMT_ADOPT" \
    SOURCE_REPO="{{ SOURCE_REPO }}" SOURCE_REVISION="{{ SOURCE_REVISION }}" \
    SOURCE_PATH_PREFIX="{{ SOURCE_PATH_PREFIX }}" \
    MANIFEST_REPO_URL="{{ MANIFEST_REPO_URL }}" \
    PARENT="{{ parent }}" NAME="$NAME" ADOPT="$ADOPT" \
    python3 -c 'import os,sys,string; d=string.Template(open(sys.argv[1]).read()).substitute(os.environ); sys.stdout.write("\n".join(l for l in d.split("\n") if l.strip()) + "\n")' \
      infra/platform/templates/installation.yml.tmpl

# Write an environment's zone manifest. `domain` defaults to <label> under the installation's.
queenswood-zone-manifest env label domain="" project="":
    #!/usr/bin/env zsh
    set -e
    FILE={{ INSTALLATIONS_REPO }}/{{ CODE }}/{{ label }}.zone.yml
    if [[ -e "$FILE" ]]; then
      echo "$FILE already exists; edit it" >&2
      exit 1
    fi

    DOMAIN="{{ domain }}"
    if [[ -z "$DOMAIN" ]]; then
      if ! APEX=$(just _environment-domain); then
        exit 1
      fi
      DOMAIN="{{ label }}.$APEX"
    fi
    PROJECT="{{ project }}"
    if [[ -z "$PROJECT" ]]; then
      PROJECT=$(just _mgmt-project)
    fi

    CODE="{{ CODE }}" ENV="{{ env }}" LABEL="{{ label }}" \
    DOMAIN="$DOMAIN" PROJECT="$PROJECT" \
    python3 -c 'import os,sys,string; sys.stdout.write(string.Template(open(sys.argv[1]).read()).substitute(os.environ))' \
      infra/platform/templates/zone.yml.tmpl > "$FILE"

    echo ">>> wrote $FILE for $DOMAIN"
    echo ">>> read it, commit and merge, then: just queenswood-zone-nameservers {{ env }} {{ label }}"

# Print the four nameservers assigned to an environment's zone, to delegate to.
queenswood-zone-nameservers env label:
    #!/usr/bin/env zsh
    set -e
    ZONE="dz-{{ CODE }}-{{ env }}-{{ label }}"
    if ! OUT=$(kubectl --context {{ MGMT_CTX }} -n crossplane-system \
                 get managedzone "$ZONE" \
                 -o jsonpath='{.status.atProvider.nameServers}' 2>&1); then
      echo "cannot read $ZONE on {{ MGMT_CTX }}:" >&2
      printf '%s\n' "$OUT" | sed 's/^/  /' >&2
      exit 1
    fi
    if [[ -z "$OUT" || "$OUT" == "[]" ]]; then
      echo "$ZONE has no nameservers yet; it is still being composed" >&2
      exit 1
    fi
    printf '%s\n' "$OUT" | tr -d '[]"' | tr ',' '\n'

    echo >&2
    echo ">>> add these to apex.yml as an NS record, then: just dns-apex-apply" >&2

# Write the installation's local-development project manifest. `project` defaults to a minted id.
queenswood-local-manifest project="":
    #!/usr/bin/env zsh
    set -e
    ROOT={{ INSTALLATIONS_REPO }}/{{ CODE }}
    FILE="$ROOT/local.yml"
    if [[ ! -d "$ROOT" ]]; then
      echo "no installation at $ROOT" >&2
      echo "the plane's manifest is committed there first -- see" >&2
      echo "docs/recipes/infra/management-plane-install.md" >&2
      exit 1
    fi
    if [[ -n "{{ project }}" && ! "{{ project }}" =~ ^prj-{{ CODE }}-d-local-[0-9a-f]{6}$ ]]; then
      echo "project must be prj-{{ CODE }}-d-local-<six hex>, not {{ project }}" >&2
      exit 1
    fi
    # Committed is the line, not merely present: a plane reads from git,
    # so an uncommitted file was never applied and the id it holds means
    # nothing. A committed one may be the only record of a consumed id.
    if [[ -e "$FILE" ]]; then
      if git -C {{ INSTALLATIONS_REPO }} rev-parse --git-dir >/dev/null 2>&1 \
         && git -C {{ INSTALLATIONS_REPO }} cat-file -e \
              "HEAD:{{ CODE }}/local.yml" 2>/dev/null; then
        echo "{{ CODE }} already has a committed local project:" >&2
        echo "  $FILE" >&2
        echo "edit it" >&2
        exit 1
      fi
      echo ">>> replacing the uncommitted $FILE" >&2
      echo ">>> nothing was built from it, so the id is minted afresh" >&2
    fi

    PROJECT="{{ project }}"
    if [[ -z "$PROJECT" ]]; then
      PROJECT="prj-{{ CODE }}-d-local-$(openssl rand -hex 3)"
      echo ">>> naming the project $PROJECT" >&2
    fi

    CODE="{{ CODE }}" PROJECT="$PROJECT" \
    python3 -c 'import os,sys,string; sys.stdout.write(string.Template(open(sys.argv[1]).read()).substitute(os.environ))' \
      infra/platform/templates/local.yml.tmpl > "$FILE"

    echo ">>> wrote $FILE"
    echo ">>> read it, commit and merge, then create the OAuth client in $PROJECT"

# Render the whole unit for the instance `env`-`label`, from the
# `spec` set of templates.
queenswood-instance-manifest env=ENV label=LABEL project="" domain="" spec=SPEC:
    #!/usr/bin/env zsh
    set -e
    if ! INSTANCE=$(just _queenswood-instance "{{ env }}" "{{ label }}"); then
      exit 1
    fi
    TEMPLATES=infra/platform/templates/unit/{{ spec }}
    if [[ ! -d "$TEMPLATES" ]]; then
      echo "no template set {{ spec }}" >&2
      echo "sets are:" >&2
      ls infra/platform/templates/unit | sed 's/^/  /' >&2
      exit 1
    fi
    ROOT={{ INSTALLATIONS_REPO }}/{{ CODE }}
    UNIT_FILE="$ROOT/{{ label }}.unit.yml"
    UNIT_DIR="$ROOT/units/{{ label }}"

    # Committed is the line, not merely present: a plane reads from git,
    # so an uncommitted file was never applied and the id it holds means
    # nothing. A committed one may be the only record of a consumed id.
    if [[ -e "$UNIT_FILE" || -d "$UNIT_DIR" ]]; then
      COMMITTED=""
      if git -C {{ INSTALLATIONS_REPO }} rev-parse --git-dir >/dev/null 2>&1; then
        for TRACKED in "{{ CODE }}/{{ label }}.unit.yml" \
                       "{{ CODE }}/units/{{ label }}/instance.yml"; do
          if git -C {{ INSTALLATIONS_REPO }} cat-file -e "HEAD:$TRACKED" 2>/dev/null; then
            COMMITTED="$TRACKED"
          fi
        done
      fi
      if [[ -n "$COMMITTED" ]]; then
        echo "$INSTANCE already has a unit, committed:" >&2
        echo "  $COMMITTED" >&2
        echo "edit it, or choose another label" >&2
        exit 1
      fi
      echo ">>> replacing the uncommitted unit for $INSTANCE" >&2
      echo ">>> nothing was built from it, so the id is minted afresh" >&2
      rm -rf "$UNIT_DIR"
    fi
    if [[ ! -d "$ROOT" ]]; then
      echo "no installation at $ROOT" >&2
      echo "the plane's manifest is committed there first:" >&2
      echo "  just queenswood-installation-manifest organizations/<org-id>" >&2
      exit 1
    fi

    PROJECT="{{ project }}"
    if [[ -z "$PROJECT" ]]; then
      PROJECT="prj-$INSTANCE-$(openssl rand -hex 3)"
      echo ">>> naming the project $PROJECT" >&2
    fi

    if ! REGION=$(just _environment-value region); then
      exit 1
    fi

    DOMAIN="{{ domain }}"
    if [[ -z "$DOMAIN" ]]; then
      if ! APEX=$(just _environment-domain); then
        exit 1
      fi
      DOMAIN="{{ label }}.$APEX"
    fi
    # This environment's own zone, not the installation's: the name is
    # delegated to it, and its records are composed into it. See
    # ADR-0028.
    ZONE_NAME="dz-{{ CODE }}-{{ env }}-{{ label }}"

    if ! MGMT=$(just _mgmt-project); then
      exit 1
    fi
    RECOVERY=$(just _recovery-project || true)
    if [[ -z "$RECOVERY" ]]; then
      echo "no recovery project for {{ CODE }}" >&2
      echo "the plane composes it from spec.recovery.projectId --" >&2
      echo "merge that into the installation's manifest first" >&2
      exit 1
    fi
    RECOVERY_SUFFIX="${RECOVERY##*-}"

    ES_VERSION=$(sed -n '/^externalSecrets:/,/^[^ ]/s/^ *version: *"\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' \
                   infra/helm/management-plane/values.yaml | head -1)
    if [[ -z "$ES_VERSION" ]]; then
      echo "no externalSecrets.version in the management-plane values" >&2
      exit 1
    fi

    mkdir -p "$UNIT_DIR"
    for T in unit instance external-secrets config queenswood values; do
      if [[ "$T" == "unit" ]]; then
        OUT="$UNIT_FILE"
      else
        OUT="$UNIT_DIR/$T.yml"
      fi
      CODE="{{ CODE }}" ENV="{{ env }}" LABEL="{{ label }}" \
      INSTANCE="$INSTANCE" PROJECT="$PROJECT" \
      REGION="$REGION" DOMAIN="$DOMAIN" ZONE_NAME="$ZONE_NAME" \
      MGMT="$MGMT" RECOVERY="$RECOVERY" \
      BACKUP_BUCKET="bkt-$INSTANCE-backups-$RECOVERY_SUFFIX" \
      BACKUP_GENERATION="fdb/continuous/$(date +%Y-%m-%d)" \
      ORG_DOMAIN="$APEX" \
      SOURCE_REPO="{{ SOURCE_REPO }}" SOURCE_REVISION="{{ SOURCE_REVISION }}" \
      MANIFEST_REPO_URL="{{ MANIFEST_REPO_URL }}" \
      EXTERNAL_SECRETS_VERSION="$ES_VERSION" \
      python3 -c 'import os,sys,string; sys.stdout.write(string.Template(open(sys.argv[1]).read()).substitute(os.environ))' \
        "$TEMPLATES/$T.yml.tmpl" > "$OUT"
      echo ">>> wrote $OUT"
    done

    echo
    echo "$INSTANCE"
    echo "  project   $PROJECT"
    echo "  domain    $DOMAIN"
    echo "  zone      $ZONE_NAME in $MGMT"
    echo "  recovery  $RECOVERY"
    echo "  spec      {{ spec }}"
    echo
    echo "commit these, then merge. The composite builds while you"
    echo "create the OAuth client and write the three secret versions;"
    echo "googleClientId in values.yml is empty until you have one."

# Put the Google OAuth client's secret in `env`-`label`'s entry, from
# `file` or the prompt. The client is made by hand in the console.
queenswood-instance-google-secret env=ENV label=LABEL file="":
    #!/usr/bin/env zsh
    set -e
    if ! INSTANCE=$(just _queenswood-instance "{{ env }}" "{{ label }}"); then
      exit 1
    fi
    PROJECT=$(just _instance-project "{{ env }}" "{{ label }}")
    just gcp-secret-version "sec-$INSTANCE-google-oauth" "$PROJECT" "{{ file }}"

# Put the submission provider's SMTP password in `env`-`label`'s entry,
# from `file` or the prompt. The provider issues it in its own console.
queenswood-instance-smtp-secret env=ENV label=LABEL file="":
    #!/usr/bin/env zsh
    set -e
    if ! INSTANCE=$(just _queenswood-instance "{{ env }}" "{{ label }}"); then
      exit 1
    fi
    PROJECT=$(just _instance-project "{{ env }}" "{{ label }}")
    just gcp-secret-version "sec-$INSTANCE-smtp" "$PROJECT" "{{ file }}"

# Generate the bootstrap admin `env`-`label`'s Keycloak database is
# created with, and put it in that instance's entry. Once per database,
# unless `supersede` says otherwise.
queenswood-instance-keycloak-admin env=ENV label=LABEL username="admin" supersede="":
    #!/usr/bin/env zsh
    set -e
    SECRET="sec-{{ CODE }}-{{ env }}-{{ label }}-keycloak-admin"
    PROJECT=$(just _instance-project {{ env }} {{ label }} || true)

    VERSIONS=""
    if [[ -n "$PROJECT" ]]; then
      # Not silenced, and checked apart from being empty: a denial
      # exits non-zero with empty stdout, which would read as none.
      if ! VERSIONS=$(gcloud secrets versions list "$SECRET" --project="$PROJECT" \
                        --filter="state=ENABLED" --format='value(name)' 2>&1); then
        echo "cannot list versions of $SECRET:" >&2
        printf '%s\n' "$VERSIONS" | sed 's/^/  /' >&2
        echo "secretsAdmin carries the viewer role this needs" >&2
        exit 1
      fi
    fi
    if [[ -n "$VERSIONS" ]]; then
      if [[ "{{ supersede }}" != "yes" ]]; then
        echo "$SECRET already holds a credential, and a later version does" >&2
        echo "not change the account the database was created with. Reset" >&2
        echo "that account in Keycloak to match, or reset the realm beside" >&2
        echo "a fresh FoundationDB -- then say so:" >&2
        echo "  just queenswood-instance-keycloak-admin {{ env }} {{ label }} {{ username }} yes" >&2
        exit 1
      fi
      echo ">>> superseding the credential in $SECRET, on your word that the" >&2
      echo ">>> account in the database is being changed to match" >&2
    fi

    PASSWORD=$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)
    jq -n --arg u "{{ username }}" --arg p "$PASSWORD" \
      '{username: $u, password: $p}' \
      | just gcp-secret-version "$SECRET" "$PROJECT"
    echo ">>> $SECRET now holds a bootstrap admin. Nothing reads it until"
    echo ">>> keycloak.bootstrapAdmin.secretName in the instance's values"
    echo ">>> names the Secret the ExternalSecret writes."

# Generate the key `env`-`label`'s FDB backups are encrypted under, and
# put it in that instance's entry. Once ever, unless `supersede` says
# otherwise.
queenswood-recovery-backup-key env=ENV label=LABEL supersede="":
    #!/usr/bin/env zsh
    set -e
    # Piped, never written down, and never through gcp-secret-version,
    # which strips a trailing newline -- one key in 256 ends in 0x0a.
    SECRET="sec-{{ CODE }}-{{ env }}-{{ label }}-fdb-backup-key"
    PROJECT=$(just _recovery-project || true)
    if [[ -z "$PROJECT" ]]; then
      echo "no recovery project for {{ CODE }}" >&2
      echo "the plane composes it from spec.recovery.projectId --" >&2
      echo "merge that into the installation's manifest first" >&2
      exit 1
    fi
    if ! gcloud secrets describe "$SECRET" --project="$PROJECT" >/dev/null 2>&1; then
      echo "no secret $SECRET in $PROJECT" >&2
      echo "the composite makes the container -- apply it first" >&2
      exit 1
    fi
    # Not silenced, and checked apart from being empty: reading a denial
    # as "no versions" adds the one version that strands every backup.
    if ! VERSIONS=$(gcloud secrets versions list "$SECRET" --project="$PROJECT" \
                      --filter="state=ENABLED" --format='value(name)' 2>&1); then
      echo "cannot list versions of $SECRET:" >&2
      printf '%s\n' "$VERSIONS" | sed 's/^/  /' >&2
      echo "secretsAdmin carries the viewer role this needs" >&2
      exit 1
    fi
    if [[ -n "$VERSIONS" ]]; then
      if [[ "{{ supersede }}" != "yes" ]]; then
        echo "$SECRET already holds a key, and a later version does not" >&2
        echo "rotate it -- it strands every backup taken under the current" >&2
        echo "one. Where nothing has been backed up yet, say so:" >&2
        echo "  just queenswood-recovery-backup-key {{ env }} {{ label }} yes" >&2
        exit 1
      fi
      echo ">>> superseding the key in $SECRET, on your word that nothing" >&2
      echo ">>> has been backed up under it" >&2
    fi
    openssl rand 32 \
      | gcloud secrets versions add "$SECRET" --project="$PROJECT" --data-file=- \
        >/dev/null
    echo ">>> $SECRET now holds a key. It is the only copy, deliberately."

# Add a kubectl context for `env`-`label`'s cluster, named for it.
queenswood-instance-ctx env label project="":
    #!/usr/bin/env zsh
    set -e
    CLUSTER="{{ CODE }}-{{ env }}-{{ label }}"

    PROJECT="{{ project }}"
    if [[ -z "$PROJECT" ]]; then
      PROJECT=$(just _instance-project "{{ env }}" "{{ label }}")
    fi

    ZONE=$(gcloud container clusters list --project="$PROJECT" \
             --filter="name=$CLUSTER" --format='value(location)' | head -1)
    if [[ -z "$ZONE" ]]; then
      echo "no cluster $CLUSTER in $PROJECT" >&2
      exit 1
    fi

    # get-credentials makes its context current, which would silently
    # retarget a shell working against the plane -- so it is put back.
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
