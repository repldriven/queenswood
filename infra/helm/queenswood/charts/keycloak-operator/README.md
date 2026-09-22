# keycloak-operator

Cluster-wide install of the official Keycloak Operator, vendored from
upstream so the CRDs land ahead of the templates that use them.

## Layout

- `crds/` — the four CRDs (`keycloaks`, `keycloakrealmimports`,
  `keycloakoidcclients`, `keycloaksamlclients`). Helm installs
  everything here before templates, and never templates it.
- `templates/operator.yaml` — the operator: ServiceAccount, roles and
  bindings, Service, Deployment.

## Sourced from

Tag `26.7.4` of
[`keycloak/keycloak-k8s-resources`](https://github.com/keycloak/keycloak-k8s-resources/tree/26.7.4/kubernetes),
`kubernetes/cluster-wide/` — verbatim but for one deviation: every
`namespace: keycloak-operator` is templated to
`{{ .Release.Namespace }}`, so the operator runs wherever the chart is
installed. The rights it manages a `Keycloak` with are cluster-scoped,
so that `Keycloak` may be in any namespace.

## Refreshing

The version lives in `versions.json` and is asserted by
`just check-versions`. Change it there first, then re-vendor at the
same tag:

```
TAG=$(jq -r .keycloak.version versions.json)
BASE=https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/$TAG/kubernetes/cluster-wide
D=$(mktemp -d)
for f in kustomization.yml kubernetes.yml \
         keycloakoidcclients.k8s.keycloak.org-v1.yml \
         keycloakrealmimports.k8s.keycloak.org-v1.yml \
         keycloaks.k8s.keycloak.org-v1.yml \
         keycloaksamlclients.k8s.keycloak.org-v1.yml; do
  curl -sLo "$D/$f" "$BASE/$f"
done

# CRDs verbatim.
for f in keycloaks keycloakrealmimports keycloakoidcclients keycloaksamlclients; do
  cp "$D/$f.k8s.keycloak.org-v1.yml" crds/crd-$f.yaml
done

# The operator, through kustomize with only kubernetes.yml as input so
# the CRDs are not duplicated into templates/.
printf 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nnamespace: keycloak-operator\nresources:\n  - kubernetes.yml\ntransformers:\n  - |-\n    apiVersion: builtin\n    kind: NamespaceTransformer\n    metadata:\n      name: notImportantHere\n    setRoleBindingSubjects: allServiceAccounts\n    fieldSpecs:\n    - path: metadata/namespace\n      create: true\n' > "$D/kustomization.yml"
# Template the namespace, prepend the provenance header, then write
# templates/operator.yaml. The quirk's `namespace: keycloak` stays.
kubectl kustomize "$D" \
  | sed 's/^\( *\)namespace: keycloak-operator$/\1namespace: {{ .Release.Namespace }}/'
```

**The kustomize step is not optional.** Upstream's raw `kubernetes.yml`
leaves ServiceAccount subjects without a namespace and relies on its
own `NamespaceTransformer` to fill them in. Applied raw, the operator
holds no permissions and silently reconciles nothing.

## Keep it in step with the server

The operator's `KeycloakRealmImport` CRD is generated from the
server's realm schema. An operator older than the Keycloak it manages
rejects fields the server exports — 26.6.1 against a 26.7.0 server
refused every realm import produced by `kc.sh export`, because 26.7
emits `webAuthnPolicyResidentKey` and the older CRD does not declare
it. The chart's committed realm JSON carries no such field, so only
the restore path failed. `just check-versions` now asserts both sides.

## Known upstream quirk

`keycloak-operator-clusterrole-binding` names its subject in namespace
`keycloak` rather than `keycloak-operator`; upstream's
NamespaceTransformer does not rewrite an explicitly set namespace, so
its own build has the same result. The binding therefore matches no
ServiceAccount. It grants only `config.openshift.io/ingresses`, which
does not exist outside OpenShift, so it is inert here and is left
verbatim rather than becoming a deviation every upgrade must reapply.
