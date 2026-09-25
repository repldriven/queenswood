# The throwaway control plane, discarded once the management project
# and its cluster exist.
BOOT_CLUSTER := "boot-mgmt"
BOOT_CTX := "kind-boot-mgmt"

# Stand up the boot cluster: kind, Crossplane, the GCP providers.
boot-cluster-up:
    #!/usr/bin/env zsh
    set -e
    # Before kind and Helm, not after: a credential needing
    # reauthentication fails when the provider first calls out, ten
    # minutes later, reported as a folder that cannot be observed.
    F="$HOME/.config/gcloud/application_default_credentials.json"
    if [[ ! -f "$F" ]]; then
      echo "no ADC -- run 'just seed-impersonate' first" >&2
      exit 1
    fi
    if ! grep -q impersonated_service_account "$F"; then
      echo "ADC is not impersonating the seed identity --" >&2
      echo "run 'just seed-impersonate'" >&2
      exit 1
    fi
    if ! ERR=$(gcloud auth application-default print-access-token 2>&1 >/dev/null); then
      echo "ADC cannot mint a token -- run 'just seed-impersonate'" >&2
      printf '%s\n' "$ERR" | sed 's/^/  /' >&2
      exit 1
    fi

    kind get clusters | grep -q '^{{ BOOT_CLUSTER }}$' \
      || kind create cluster --name '{{ BOOT_CLUSTER }}'

    helm dependency update infra/helm/boot-management-plane >/dev/null

    CHART=infra/helm/boot-management-plane
    for dep in crossplane argo-cd; do
      WANT=$(awk -v d="  - name: $dep" '$0 == d {f = 1; next}
                 f && /version:/ {gsub(/"/, "", $2); print $2; exit}' \
               "$CHART/Chart.yaml")
      if [[ ! -f "$CHART/charts/$dep-$WANT.tgz" ]]; then
        echo "helm did not resolve $dep $WANT -- fetched:" >&2
        ls "$CHART/charts/" >&2
        exit 1
      fi
    done
    helm upgrade --install boot-management-plane infra/helm/boot-management-plane \
      --kube-context {{ BOOT_CTX }} \
      --namespace crossplane-system --create-namespace \
      --set argo-cd.enabled=false --set providers.enabled=false --wait

    MANIFEST=infra/platform/crossplane-providers/providers.yml
    for name in provider-gcp-cloudplatform provider-gcp-compute \
                provider-gcp-container provider-gcp-secretmanager \
                provider-gcp-storage provider-helm \
                function-patch-and-transform \
                function-go-templating function-auto-ready \
                function-environment-configs ; do
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
      provider.pkg.crossplane.io/provider-gcp-storage
    kubectl --context {{ BOOT_CTX }} wait --for=condition=healthy --timeout=600s \
      provider.pkg.crossplane.io/provider-helm
    kubectl --context {{ BOOT_CTX }} wait --for=condition=healthy --timeout=600s \
      function.pkg.crossplane.io/function-patch-and-transform
    kubectl --context {{ BOOT_CTX }} wait --for=condition=healthy --timeout=600s \
      function.pkg.crossplane.io/function-go-templating
    kubectl --context {{ BOOT_CTX }} wait --for=condition=healthy --timeout=600s \
      function.pkg.crossplane.io/function-auto-ready
    kubectl --context {{ BOOT_CTX }} wait --for=condition=healthy --timeout=600s \
      function.pkg.crossplane.io/function-environment-configs

    kubectl --context {{ BOOT_CTX }} create secret generic gcp-creds \
      -n crossplane-system --from-file=creds="$F" \
      --dry-run=client -o yaml | kubectl --context {{ BOOT_CTX }} apply -f -

    PROJECT=$(just _seed-project)
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
          name: {{ CODE }}-c-mgmt-kubeconfig
          key: kubeconfig
      identity:
        type: GoogleApplicationCredentials
        source: Secret
        secretRef:
          namespace: crossplane-system
          name: gcp-creds
          key: creds
    YAML

    # Every kind the manifest composes, directly or through another
    # composite: a composition naming a kind whose XRD is absent fails
    # the whole composite with "no matches for kind".
    for KIND in xmanagementplane xsubsidiary xpubliczone xnetwork xcluster; do
      XRD=infra/platform/crossplane-xrds/$KIND-xrd.yml
      if ! NAME=$(awk '/^metadata:/ {f = 1; next} f && /^  name:/ {print $2; exit}' "$XRD"); then
        exit 1
      fi
      if [[ -z "$NAME" ]]; then
        echo "no metadata.name in $XRD" >&2
        exit 1
      fi
      kubectl --context {{ BOOT_CTX }} apply -f "$XRD"
      kubectl --context {{ BOOT_CTX }} wait --for=condition=established --timeout=120s \
        "xrd/$NAME"
      kubectl --context {{ BOOT_CTX }} apply \
        -f infra/platform/crossplane-xrds/$KIND-composition.yml
    done

    echo
    echo ">>> plane ready. Next: just boot-mgmt-apply"

# Apply the committed manifest to the boot cluster, as its bootstrap.
boot-mgmt-apply billing="" yes="":
    #!/usr/bin/env zsh
    set -e
    FILE={{ INSTALLATIONS_REPO }}/{{ CODE }}/installation.yml
    if [[ ! -f "$FILE" ]]; then
      echo "no manifest at $FILE" >&2
      echo "a first installation is rendered, then committed, then" >&2
      echo "applied -- the ids it holds are consumed permanently:" >&2
      echo "  just queenswood-installation-manifest organizations/<org-id> > $FILE" >&2
      exit 1
    fi

    ENV_FILE={{ INSTALLATIONS_REPO }}/{{ CODE }}/environment.yml

    SUB_FILE={{ INSTALLATIONS_REPO }}/{{ CODE }}/subsidiary.yml
    if [[ ! -f "$SUB_FILE" ]]; then
      echo "no boundary at $SUB_FILE" >&2
      echo "the folder is declared and committed before the plane is" >&2
      echo "built in it -- see docs/recipes/infra/boundary-install.md" >&2
      exit 1
    fi

    BILLING="{{ billing }}"
    if [[ -z "$BILLING" ]]; then
      BILLING=$(just _gcp-billing-account "$(just _seed-sa)")
    fi

    DOC=$(kubectl patch --local -f "$FILE" --type merge -o yaml \
      -p "{\"spec\":{\"billingAccountId\":\"$BILLING\",\"management\":{\"bootstrap\":true}}}")

    echo "applying    $SUB_FILE"
    echo "            $FILE"
    if [[ -f "$ENV_FILE" ]]; then echo "            $ENV_FILE"; fi
    echo "to          {{ BOOT_CTX }}"
    echo "billing     $BILLING"
    echo
    print -r -- "$DOC" | sed 's/^/  /'
    echo
    echo "management.bootstrap is true here, so this cluster will create"
    echo "and update the Crossplane and Argo releases on the management"
    echo "cluster. Never against an installation that already has one."
    echo
    if [[ -z "{{ yes }}" ]]; then
      printf 'proceed? [y/N] '
      read -r REPLY || REPLY=""
      if [[ "$REPLY" != [yY]* ]]; then
        echo "abandoned"
        exit 1
      fi
    fi

    if [[ -f "$ENV_FILE" ]]; then
      kubectl --context {{ BOOT_CTX }} apply -f "$ENV_FILE"
      echo ">>> applied $ENV_FILE"
    else
      echo ">>> no $ENV_FILE; the folder and project will have no access"
      echo ">>> bindings, and no folder to compose unless subsidiary.yml"
      echo ">>> names one itself"
    fi

    kubectl --context {{ BOOT_CTX }} apply -f "$SUB_FILE"
    echo ">>> applied $SUB_FILE"
    kubectl --context {{ BOOT_CTX }} -n crossplane-system wait \
      --for=condition=ready --timeout=300s \
      xsubsidiary/{{ CODE }} || true

    print -r -- "$DOC" | kubectl --context {{ BOOT_CTX }} apply -f -

    echo ">>> applied $FILE"
    echo ">>> waiting for the folder and management project"
    kubectl --context {{ BOOT_CTX }} -n crossplane-system wait \
      --for=condition=ready --timeout=300s \
      xmanagementplane/{{ CODE }} || true
    just boot-mgmt-status

# Report the composite and the folder it made.
boot-mgmt-status:
    #!/usr/bin/env zsh
    set -e
    kubectl --context {{ BOOT_CTX }} -n crossplane-system get \
      xmanagementplane {{ CODE }} -o yaml 2>/dev/null \
      | grep -E 'folderId|folderName|type: (Ready|Synced)|status: "(True|False)"|reason:' \
      || echo "no composite applied yet"
    echo
    kubectl --context {{ BOOT_CTX }} -n crossplane-system \
      get folder.cloudplatform.gcp.m.upbound.io 2>/dev/null || true

# Delete the boot cluster. The folder is orphaned, not deleted.
boot-cluster-down:
    @kind delete cluster --name {{ BOOT_CLUSTER }}
