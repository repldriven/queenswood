#!/usr/bin/env bash
# Assert the pinned versions that cannot read versions.json still agree with
# it. flake.nix and the CI workflows read versions.json directly; Dockerfile
# ARG defaults and Helm values cannot, so they are checked here instead.
#
# A FoundationDB client can only talk to a cluster on the same protocol
# version, so a drifted copy is not a cosmetic inconsistency -- it surfaces
# as a connection failure that says nothing about versions.
set -uo pipefail

cd "$(dirname "$0")/.."

versions=versions.json
fdb=$(jq -r '.foundationdb.version' "$versions")
keycloak=$(jq -r '.keycloak.version' "$versions")
fdb_minor=${fdb%.*}
fail=0

# Report one comparison. An empty `got` means the pattern matched nothing,
# which is a failure in its own right: it means the file moved on and this
# check silently stopped looking at anything.
check() {
  local what="$1" want="$2" got="$3"
  if [ -z "$got" ]; then
    printf '  %-52s \033[31mnot found\033[0m (expected %s)\n' "$what" "$want"
    fail=1
  elif [ "$got" = "$want" ]; then
    printf '  %-52s \033[32m%s\033[0m\n' "$what" "$got"
  else
    printf '  %-52s \033[31m%s\033[0m (expected %s)\n' "$what" "$got" "$want"
    fail=1
  fi
}

echo "Pinned copies of FoundationDB $fdb, against $versions:"

# The testcontainers image takes its version as a build arg with no
# default, and `fdb-version-matches-versions-json-test` asserts that
# against this file -- so the only Dockerfile pinning a version is the
# service one.
f=infra/docker/service/Dockerfile
check "$f (ARG FDB_VERSION)" "$fdb" \
  "$(sed -n 's/^ARG FDB_VERSION=\([0-9][0-9.]*\).*/\1/p' "$f" | head -1)"

# The JVM client coordinate. deps/fdb is the workspace's single source for it
# -- every brick reaches fdb-java through that shim -- but EDN cannot read
# JSON, so the one remaining copy is asserted here. The `:exclusions` entry
# names fdb-java without a version and so cannot match this pattern.
check "deps/fdb/deps.edn (fdb-java)" "$fdb" \
  "$(sed -n 's/.*org\.foundationdb\/fdb-java {:mvn\/version "\([^"]*\)".*/\1/p' \
       deps/fdb/deps.edn | head -1)"

values=infra/helm/queenswood/values.yaml

# Read with awk rather than a YAML parser so this needs only jq, which both
# the devshell and the CI runners already have. The two tools named `yq` --
# nixpkgs ships the Python jq-wrapper, the runners ship mikefarah's Go one --
# take incompatible expressions, and depending on either invites a check that
# passes locally and misbehaves in CI.
check "$values (fdb.version)" "$fdb" \
  "$(awk '/^fdb:/ {f = 1; next}
          f && /^[^ ]/ {f = 0}
          f && /^  version:/ {gsub(/["]/, "", $2); print $2; exit}' "$values")"

# The operator copies libfdb_c_<minor>.so out of the monitor image, so the
# enabled initContainers key must be the cluster's minor and its tag must be
# on that same minor. Exactly one key is left non-null; the rest are nulled to
# avoid pulling monitor images we never run. An entry is enabled when the key
# line carries no value -- `"7.4":` opens a block, `"7.1": null` does not.
check "$values (enabled initContainer)" "$fdb_minor" \
  "$(awk '/^  initContainers:/ {f = 1; next}
          f && /^[^ ]/ {f = 0}
          f && /^    "/ && $2 == "" {
            k = $1; gsub(/["]|:$/, "", k); out = out sep k; sep = ","
          }
          END {print out}' "$values")"

check "$values (initContainer $fdb_minor tag)" "$fdb" \
  "$(awk -v key="\"$fdb_minor\":" '
          $1 == key {f = 1; next}
          f && /^    "/ {f = 0}
          f && /^        tag:/ {print $2; exit}' "$values")"

# Clojure itself cannot be single-sourced through deps/clojure-core-async the way
# core.async is: the CLI merges its own root deps.edn into every project's
# :deps, so Clojure is always a direct dependency and a coordinate one level
# down never competes. Drop a project's pin and it does not inherit the
# workspace version, it silently takes whatever Clojure the caller's CLI
# ships. So every project repeats the pin, and the copies are asserted
# against the root deps.edn here rather than left to drift.
clj=$(sed -n 's/.*org\.clojure\/clojure {:mvn\/version "\([^"]*\)".*/\1/p' \
        deps.edn | head -1)

echo
echo "Pinned copies of Clojure $clj, against deps.edn:"

for f in projects/*/deps.edn; do
  check "$f (org.clojure/clojure)" "$clj" \
    "$(sed -n 's/.*org\.clojure\/clojure {:mvn\/version "\([^"]*\)".*/\1/p' \
         "$f" | head -1)"
done


# Keycloak: the server image, and the operator whose CRDs are vendored from
# the matching release. These are not merely "nice to keep in step" -- the
# operator's KeycloakRealmImport CRD is generated from the server's realm
# schema, and an older operator rejects fields a newer server exports.
# Keycloak 26.7 emits webAuthnPolicyResidentKey; the 26.6.1 CRD does not
# declare it, so the API server refused the whole import with a strict
# decoding error. Only the restore path hit it, because the chart's
# committed realm JSON carries no such field -- which is the worst way to
# find out, an hour into a teardown cycle.
echo "keycloak $keycloak"
check "queenswood values.yaml (server image)" "$keycloak" \
  "$(sed -n 's|^  image: quay.io/keycloak/keycloak:\(.*\)$|\1|p' \
      infra/helm/queenswood/values.yaml | head -1)"
check "keycloak-operator Chart.yaml appVersion" "$keycloak" \
  "$(sed -n 's/^appVersion: "\(.*\)"$/\1/p' \
      infra/helm/queenswood/charts/keycloak-operator/Chart.yaml | head -1)"
check "keycloak-operator vendored image" "$keycloak" \
  "$(grep -oE 'quay.io/keycloak/keycloak-operator:[0-9.]+' \
      infra/helm/queenswood/charts/keycloak-operator/templates/operator.yaml \
      | head -1 | cut -d: -f2)"
check "keycloak-operator vendored CRD accepts server schema" "yes" \
  "$(grep -q 'webAuthnPolicyResidentKey:' \
      infra/helm/queenswood/charts/keycloak-operator/crds/crd-keycloakrealmimports.yaml \
      && echo yes || echo no)"

# A release is the chart's version, and the images it installs default to
# its appVersion, so the two are one number `just release` bumps together;
# a chart whose fields differ would install another release's images.
qwchart=infra/helm/queenswood/Chart.yaml
qwversion=$(awk '/^version:/ {print $2}' "$qwchart")
echo "queenswood chart $qwversion"
check "queenswood Chart.yaml appVersion" "$qwversion" \
  "$(sed -n 's/^appVersion: "\(.*\)"$/\1/p' "$qwchart" | head -1)"

# The management plane installs Crossplane onto its own cluster through a
# composed helm Release, so the chart version is pinned in a Composition --
# where Renovate cannot see it. Renovate does bump the xp-mp chart's
# dependency, so that is the version this follows, and a bump that moves one
# and not the other fails here rather than installing two Crossplanes a
# release apart on the boot plane and the management plane.
xpchart=infra/helm/boot-management-plane/Chart.yaml
crossplane=$(awk '/^  - name: crossplane$/ {f = 1; next}
                  f && /version:/ {gsub(/["]/, "", $2); print $2; exit}' "$xpchart")

echo
echo "Pinned copies of Crossplane $crossplane, against $xpchart:"

check "management plane composition (crossplane chart)" "$crossplane" \
  "$(awk '/name: crossplane$/ {f = 1; next}
          f && /version:/ {gsub(/["]/, "", $2); print $2; exit}' \
       infra/platform/crossplane-xrds/xmanagementplane-composition.yml)"

# Argo is the same duplication for the same reason, minus the handover:
# Renovate sees the chart's dependency and not the Composition, so the two
# move together by hand or not at all.
argocd=$(awk '/^  - name: argo-cd$/ {f = 1; next}
              f && /version:/ {gsub(/["]/, "", $2); print $2; exit}' "$xpchart")

echo
echo "Pinned copies of Argo CD $argocd, against $xpchart:"

check "management plane composition (argo-cd chart)" "$argocd" \
  "$(awk '/name: argo-cd$/ {f = 1; next}
          f && /version:/ {gsub(/["]/, "", $2); print $2; exit}' \
       infra/platform/crossplane-xrds/xmanagementplane-composition.yml)"

if [ "$fail" -ne 0 ]; then
  cat <<EOF

Update the files above to match their source, or change the source if the
intent was to move the pin. None of these are cosmetic: the FoundationDB
client, the client baked into the service image and the deployed cluster
share a protocol version, and the Keycloak operator's CRDs are generated
from the server's realm schema -- an older operator rejects fields a newer
server exports.
EOF
  exit 1
fi

echo "All pinned copies agree."
exit $fail
