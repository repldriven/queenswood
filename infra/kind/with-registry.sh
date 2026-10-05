#!/usr/bin/env bash
# Bring up a kind cluster wired to a host-side Docker registry.
#
# Adapted from https://kind.sigs.k8s.io/docs/user/local-registry/.
# The host runs a `registry:3` container on 127.0.0.1:5001; the kind
# nodes' containerd is patched to mirror `localhost:5001` to the
# registry container's internal address. Net effect: `docker push
# localhost:5001/foo:dev` from the host puts an image somewhere kind
# pulls cheaply, no `kind load docker-image` step needed, and
# rebuilds dedupe layers across pushes.
#
# Re-runnable: if the registry container or kind cluster already
# exists, the corresponding step is a no-op.
#
# Usage: bash infra/kind/with-registry.sh [cluster-name]
set -o errexit
set -o nounset
set -o pipefail

CLUSTER_NAME="${1:-queenswood}"
REG_NAME='kind-registry'
REG_PORT='5001'
REG_IMAGE='registry:3'

# 1. Local registry container (skip if running on REG_IMAGE). registry:2's
#    `garbage-collect --delete-untagged` deletes the manifests an attested
#    image's index points to, leaving every tag unpullable, so a registry
#    on any other image is recreated on REG_IMAGE over the same storage.
REG_VOLUME="${REG_NAME}"
CURRENT_IMAGE="$(docker inspect -f '{{.Config.Image}}' "${REG_NAME}" 2>/dev/null || true)"
if [ -n "${CURRENT_IMAGE}" ] && [ "${CURRENT_IMAGE}" != "${REG_IMAGE}" ]; then
  REG_VOLUME="$(docker inspect -f \
    '{{range .Mounts}}{{if eq .Destination "/var/lib/registry"}}{{.Name}}{{end}}{{end}}' \
    "${REG_NAME}")"
  docker rm -f "${REG_NAME}" >/dev/null
fi
if [ "$(docker inspect -f '{{.State.Running}}' "${REG_NAME}" 2>/dev/null || true)" \
     != 'true' ]; then
  docker run \
    -d --restart=always \
    -p "127.0.0.1:${REG_PORT}:5000" \
    --network bridge \
    --name "${REG_NAME}" \
    -v "${REG_VOLUME}:/var/lib/registry" \
    "${REG_IMAGE}"
fi

# 2. kind cluster with the containerd registry-config dir enabled.
#    `config_path = "/etc/containerd/certs.d"` tells containerd to
#    look in that directory for per-registry mirror config (we drop
#    a hosts.toml in there a few lines down).
if ! kind get clusters | grep -q "^${CLUSTER_NAME}$"; then
  cat <<EOF | kind create cluster --name "${CLUSTER_NAME}" --config=-
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
containerdConfigPatches:
- |-
  [plugins."io.containerd.grpc.v1.cri".registry]
    config_path = "/etc/containerd/certs.d"
EOF
fi

# 3. Per-node mirror config: rewrite localhost:5001 → kind-registry:5000
#    so containerd reaches the host-side registry over the kind
#    docker network instead of trying to dial 127.0.0.1 inside the node.
REGISTRY_DIR="/etc/containerd/certs.d/localhost:${REG_PORT}"
for node in $(kind get nodes --name "${CLUSTER_NAME}"); do
  docker exec "${node}" mkdir -p "${REGISTRY_DIR}"
  cat <<EOF | docker exec -i "${node}" cp /dev/stdin "${REGISTRY_DIR}/hosts.toml"
[host."http://${REG_NAME}:5000"]
EOF
done

# 4. Connect the registry container to the `kind` docker network so
#    the containerd mirror lookup actually resolves.
if [ "$(docker inspect -f='{{json .NetworkSettings.Networks.kind}}' \
        "${REG_NAME}")" = 'null' ]; then
  docker network connect "kind" "${REG_NAME}"
fi

# 5. Document the registry via the standard `local-registry-hosting`
#    ConfigMap (KEP-1755). Tilt and other tools auto-detect this and
#    prefer push-to-registry over load.
cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: local-registry-hosting
  namespace: kube-public
data:
  localRegistryHosting.v1: |
    host: "localhost:${REG_PORT}"
    help: "https://kind.sigs.k8s.io/docs/user/local-registry/"
EOF
