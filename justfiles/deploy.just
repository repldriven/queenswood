# Render the Helm chart locally (does not install)
helm-template tag="dev":
    helm dependency update infra/helm/queenswood
    helm template queenswood infra/helm/queenswood \
      --set image.tag={{ tag }}

# Lint and schema-validate the Helm chart without a cluster
helm-validate tag="dev":
    # CRDs (FoundationDBCluster etc.) are skipped via -ignore-missing-schemas.
    helm dependency update infra/helm/queenswood
    helm lint infra/helm/queenswood
    helm template queenswood infra/helm/queenswood \
      --set image.tag={{ tag }} \
      | kubeconform -strict -ignore-missing-schemas -summary

# Install or upgrade the chart
helm-install tag="dev" registry="ghcr.io/repldriven/queenswood" values_file="" pull_policy="":
    #!/usr/bin/env zsh
    # `registry` lets `kind-up` point at the local registry so containerd
    # pulls from there. `values_file` layers an extra values file on top
    # of values.yaml -- kind-up passes values-dev.yaml to enable the
    # bundled dev Keycloak. `pull_policy` overrides image.pullPolicy.
    #
    # kind-up sets `Always`, not `IfNotPresent`, so the dev loop works:
    # rebuild an image while the cluster runs, push it to the local
    # registry under the same `dev` tag, bounce the pod, and the new
    # build is what starts. With `IfNotPresent` containerd keeps the
    # copy it already has and a bounce silently reruns the old image --
    # which reads as "my fix didn't work" rather than "the image never
    # moved". Re-pulling from a registry on the same host is cheap, and
    # unchanged layers are deduplicated. (values-dev.yaml's `Never`
    # default is for image-load flows that inject images straight into
    # the node, bypassing a registry entirely.)
    set -e
    # `values_file` is a space-separated list layered left-to-right
    # (later files win), e.g. "values-dev.yaml values-monolith.yaml".
    extra_values=()
    for vf in {{ values_file }}; do
      extra_values+=(-f infra/helm/queenswood/$vf)
    done
    extra_sets=()
    if [[ -n "{{ pull_policy }}" ]]; then
      extra_sets=(--set image.pullPolicy={{ pull_policy }})
    fi
    helm dependency update infra/helm/queenswood
    helm upgrade --install queenswood infra/helm/queenswood \
      -n queenswood --create-namespace \
      "${extra_values[@]}" \
      --set image.tag={{ tag }} \
      --set image.registry={{ registry }} \
      "${extra_sets[@]}" \
      --wait --timeout 10m

# Create kind cluster, push images to local registry, and install the chart
kind-up tag="dev":
    #!/usr/bin/env zsh
    # Uses a host-side registry to deduplicate layers across rebuilds — dramatically
    # faster than `kind load docker-image` for iterative rebuilds.
    set -e
    bash {{ justfile_directory() }}/infra/kind/with-registry.sh queenswood
    # just docker-build-all {{ tag }}
    # console lives at bases/* (Svelte SPA, not a Polylith service
    # project), so the projects/*-service/ glob misses it.
    # docker-build-all's `default` group in infra/docker/bake.hcl already
    # builds it -- we just need to push it alongside the services.
    for svc in projects/*-service/(:t) console; do
      docker tag {{ DOCKER_REGISTRY }}/$svc:{{ tag }} localhost:5001/$svc:{{ tag }}
      docker push localhost:5001/$svc:{{ tag }}
    done
    just helm-install {{ tag }} localhost:5001 values-dev.yaml Always

# Build/push/install only the modular-monolith on kind (channel bus, no Pulsar)
kind-up-monolith tag="dev":
    #!/usr/bin/env zsh
    # One monolith pod over the core.async channel bus. Only the
    # four images the profile needs are built/pushed; values-monolith.yaml
    # disables every other service and the Pulsar subchart, layered over
    # values-dev.yaml so the bundled dev Keycloak stays enabled.
    set -e
    bash {{ justfile_directory() }}/infra/kind/with-registry.sh queenswood
    # The three JVM services share the parameterised service Dockerfile.
    for svc in monolith-service migrator-service bootstrap-service; do
      just docker-build $svc {{ tag }}
    done
    # console has its own Dockerfile; build it via its bake target.
    REGISTRY={{ DOCKER_REGISTRY }} TAG={{ tag }} \
      docker buildx bake -f infra/docker/bake.hcl console
    for svc in monolith-service migrator-service bootstrap-service console; do
      docker tag {{ DOCKER_REGISTRY }}/$svc:{{ tag }} localhost:5001/$svc:{{ tag }}
      docker push localhost:5001/$svc:{{ tag }}
    done
    # Drop the one-shot Jobs so helm recreates them on new code: their
    # name embeds the tag+chart version and the pod spec is immutable, so
    # a plain upgrade skips them and they never re-run. --ignore-not-found
    # + `|| true` keeps the first run (no namespace yet) from tripping set -e.
    kubectl delete job -n queenswood \
      -l 'app.kubernetes.io/component in (migrator,bootstrap)' \
      --ignore-not-found 2>/dev/null || true
    # Always pull so a re-pushed `:{{ tag }}` actually lands on the node —
    # IfNotPresent keeps the stale cached image and silently ignores the
    # new push (the `crictl rmi` dance). The registry is local, so the
    # re-pull is cheap.
    just helm-install {{ tag }} localhost:5001 "values-dev.yaml values-monolith.yaml" Always

# Create kind cluster and install the chart from ghcr.io/repldriven/queenswood (no local build).
kind-up-remote tag="latest":
    #!/usr/bin/env zsh
    # For the iterative build-test loop, use `kind-up` instead. This
    # path skips the host-side registry + docker buildx; containerd
    # pulls images directly from ghcr on first pod schedule.
    set -e
    kind get clusters | grep -q '^queenswood$' || kind create cluster --name queenswood
    just helm-install {{ tag }} ghcr.io/repldriven/queenswood

# Push rebuilt images to the kind registry and roll the Deployments.
kind-redeploy tag="dev" *services:
    #!/usr/bin/env zsh
    # The fast loop after `just docker-build-all`: no cluster teardown,
    # no helm upgrade. bake writes `output=type=docker`, so a rebuild
    # only lands in the local Docker store -- the cluster pulls from
    # localhost:5001 and sees nothing until the image is pushed there.
    # kind-up installs with pullPolicy Always precisely so a same-tag
    # push plus a restart runs the new build.
    #
    # No services named = every service project plus the console.
    #
    # Deployments only. The migrator and bootstrap Jobs are named for
    # the image tag, so an unchanged tag leaves them alone -- applying
    # new FDB record metadata means deleting those Jobs and reinstalling
    # the chart, not bouncing a pod.
    set -e
    svcs=({{ services }})
    if [[ ${#svcs} -eq 0 ]]; then
      svcs=(projects/*-service/(:t) console)
    fi
    for svc in $svcs; do
      docker tag {{ DOCKER_REGISTRY }}/$svc:{{ tag }} localhost:5001/$svc:{{ tag }}
      docker push localhost:5001/$svc:{{ tag }}
    done
    # Everything gets pushed -- the Jobs need images too -- but only
    # Deployments can be rolled. migrator/bootstrap are Jobs, and
    # monolith-service is disabled outside values-monolith.yaml, so say
    # what was skipped rather than failing on the first one.
    rolled=()
    for svc in $svcs; do
      if kubectl -n queenswood get deploy/queenswood-$svc >/dev/null 2>&1; then
        kubectl -n queenswood rollout restart deploy/queenswood-$svc
        rolled+=($svc)
      else
        echo "skip $svc (no Deployment -- a Job, or disabled in this profile)"
      fi
    done
    # Restart all before waiting on any, so the rollouts overlap.
    for svc in $rolled; do
      kubectl -n queenswood rollout status deploy/queenswood-$svc --timeout=5m
    done

# Tear down the kind cluster
kind-down:
    kind delete cluster --name queenswood

# Run a local dev Keycloak in Docker (foreground; Ctrl+C tears it down)
keycloak-up:
    # Listens on host :8090 to match VITE_KEYCLOAK_URL in
    # bases/console/.env.example. 8090 (not 8081) keeps the
    # port out of the way of monolith, which binds 8081 for
    # the clearbank-simulator dev component. Admin console
    # at http://localhost:8090/admin (admin / admin). Realm JSON
    # imports the queenswood-admin + queenswood-console clients
    # plus the Google IdP (placeholder creds — sign-in via Google
    # only works once you wire real credentials in).
    #
    # The test pair, not the chart's. This container exists for the
    # host SPAs on their Vite ports, which is what the test pair's
    # localhost entries name and what the dev profile's own
    # testcontainer already imports — and its queenswood-admin carries
    # the client secret that profile expects, where the deployed pair's
    # authenticates with private_key_jwt and has no secret at all. The
    # deployed pair also carries no localhost redirect URI any more, so
    # nothing here would be able to sign in.
    docker run --rm \
      --name queenswood-keycloak-dev \
      -p 8090:8080 \
      -e KEYCLOAK_ADMIN=admin \
      -e KEYCLOAK_ADMIN_PASSWORD=admin \
      -v "$PWD/components/test-resources/test-resources/queenswood-realm.json:/opt/keycloak/data/import/realm.json:ro" \
      -v "$PWD/components/test-resources/test-resources/queenswood-ops-realm.json:/opt/keycloak/data/import/realm-ops.json:ro" \
      quay.io/keycloak/keycloak:26.0 \
      start-dev --import-realm

# Force-stop a stray dev Keycloak container (Ctrl+C is normally enough)
keycloak-down:
    docker rm -f queenswood-keycloak-dev 2>/dev/null || true

# Print Keycloak admin credentials + console URL for the active env
keycloak-admin-login:
    #!/usr/bin/env zsh
    set -euo pipefail
    KCTX=$(just _gke-ctx)
    # The operator mints this from the Keycloak CR's name
    # (<release>-keycloak), with basic-auth key names -- not a
    # chart-rendered Secret with admin-user / admin-password, which has
    # never existed.
    SECRET=queenswood-keycloak-initial-admin
    user=$(kubectl --context $KCTX -n {{ QUEENSWOOD_ENV }} get secret $SECRET \
      -o jsonpath='{.data.username}' 2>/dev/null | base64 -d)
    pass=$(kubectl --context $KCTX -n {{ QUEENSWOOD_ENV }} get secret $SECRET \
      -o jsonpath='{.data.password}' 2>/dev/null | base64 -d)
    if [[ -z "$user" || -z "$pass" ]]; then
      echo "$SECRET missing or empty in namespace {{ QUEENSWOOD_ENV }} — is the Keycloak chart installed?" >&2
      exit 1
    fi
    echo "Admin console: https://keycloak.{{ QUEENSWOOD_DOMAIN }}/admin/"
    echo "User:          $user"
    echo "Password:      $pass"
