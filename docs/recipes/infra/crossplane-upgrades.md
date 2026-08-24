# Upgrading and reconfiguring Crossplane

<!-- tessl-plugin: deployment -->

## Status

**Verified**, 2026-08-24, on both paths: a configuration change (the
core's own resources) and a version change (chart 2.3.4 to 2.4.0).

## Problem

You want to change Crossplane on a management plane: its chart version,
what its own pods may take, or any other chart value.

## Solution

### Who you need to be

- **`grp-gcp-<code>-platform-viewer@`** — every step but 5.
- **`grp-gcp-<code>-cluster-admin@`** — step 5.
- Write access to this repository — step 1.

### What every command reads

```bash
# the installation code, e.g. qw01
export CODE=qw01
export WORK=$(mktemp -d)
export REL="release.helm.m.crossplane.io/crossplane-$CODE-c-mgmt"
export VALUES="$WORK/crossplane-values.json"
```

### 1a. A version change

See what the new chart would render:

```bash
# the version now, and the one being moved to
export FROM=2.3.4
export TO=2.4.0

helm repo add crossplane-stable https://charts.crossplane.io/stable
helm repo update crossplane-stable
for V in "$FROM" "$TO"; do
  helm template crossplane crossplane-stable/crossplane --version "$V" \
    -n crossplane-system > "$WORK/render-$V.yaml"
done
diff "$WORK/render-$FROM.yaml" "$WORK/render-$TO.yaml"
```

Read it for objects appearing or disappearing, and for RBAC being
narrowed. Image tags will differ.

Then two files, to the same number:

- `infra/helm/boot-management-plane/Chart.yaml` — the `crossplane`
  dependency version.
- `infra/platform/crossplane-xrds/xmanagementplane-composition.yml` —
  `management-crossplane`'s `chart.version`.

Read the Crossplane release notes for the versions it crosses, and
check the provider and function packages this plane runs still declare
support for the core version being moved to.

### 1b. A configuration change

One file:

- `infra/platform/crossplane-xrds/xmanagementplane-composition.yml` —
  `management-crossplane`'s `values:` block.

The chart's own keys are flat — `resourcesCrossplane` and
`resourcesRBACManager`, each with `limits` and `requests`.

### 1c. Either way

```bash
just check-versions
```

Merge before going further.

### 2. Wait for the plane to render it

```bash
kubectl --context "$CODE-mgmt" -n crossplane-system get "$REL" \
  -o jsonpath='{.spec.forProvider.chart.version}{"\n"}'

kubectl --context "$CODE-mgmt" -n crossplane-system get "$REL" \
  -o jsonpath='{.spec.forProvider.values}' | python3 -m json.tool
```

A version change shows in the first, anything else in the second. Until
your change is there the composite has not reconciled it, and there is
nothing yet to upgrade to.

### 3. Take the values and the version from that object

```bash
kubectl --context "$CODE-mgmt" -n crossplane-system get "$REL" \
  -o jsonpath='{.spec.forProvider.values}' > "$VALUES"
VERSION=$(kubectl --context "$CODE-mgmt" -n crossplane-system get "$REL" \
  -o jsonpath='{.spec.forProvider.chart.version}')
```

### 4. Compare both halves with what is running

The chart now, against the one step 3 read:

```bash
helm --kube-context "$CODE-mgmt" list -n crossplane-system
echo "$VERSION"
```

Then the values, where there are any:

```bash
JQ='paths(scalars) as $p | "\($p|map(tostring)|join(".")) = \(getpath($p))"'

helm --kube-context "$CODE-mgmt" get values crossplane \
  -n crossplane-system -o json | jq -r "$JQ" | sort > "$WORK/running.txt"
jq -r "$JQ" "$VALUES" | sort > "$WORK/desired.txt" \
  && diff "$WORK/running.txt" "$WORK/desired.txt"
```

Lines marked `>` are your change. Lines marked `<` are values the
running release has and the composed object does not: drift from an
earlier hand-upgrade, which applying this file would remove. Stop there,
merge that drift into the composition, and start again.

### 5. Upgrade

Nothing else should be syncing. An Application mid-operation will
outlive this, but a composite mid-reconcile resumes rather than
continues.

```bash
helm --kube-context "$CODE-mgmt" upgrade crossplane \
  crossplane-stable/crossplane --version "$VERSION" \
  -n crossplane-system -f "$VALUES"
```

### 6. Verify, in this order

```bash
# the core is up, and its init container applied the CRDs
kubectl --context "$CODE-mgmt" -n crossplane-system get pods

# every package still installed and healthy
kubectl --context "$CODE-mgmt" get providers.pkg.crossplane.io
kubectl --context "$CODE-mgmt" get functions.pkg.crossplane.io

# what landed, and what a rollback would return to
helm --kube-context "$CODE-mgmt" history crossplane -n crossplane-system

# and reconciliation resumed
kubectl --context "$CODE-mgmt" -n crossplane-system get xmanagementplane
```

The packages are the ones to watch: the core owns their Deployments, so
an upgrade rolls them, and a package that declines the new core version
reports `Unhealthy` rather than failing loudly.

### If it goes wrong

```bash
helm --kube-context "$CODE-mgmt" history crossplane -n crossplane-system
```

Then roll back to a release revision — the `REVISION` column above, a
small integer. Not a chart version:

```bash
helm --kube-context "$CODE-mgmt" rollback crossplane <revision> \
  -n crossplane-system
```

A composite reporting `ReconcileError` while the core is down is the
core being down. Judge it after the pods are back, not during.

## Rules

**MUST:**

- Merge the change before upgrading the plane.
- Change the version in both the boot chart and the composition.
  `check-versions` fails on one without the other.
- Build the values file from the composed `Release`, never by hand.
- Pin `--version` to the same object's `chart.version`.
- Render both chart versions before merging a version change, and read
  the release notes for the versions it crosses.
- Check every provider and function is healthy afterwards.
- Join `cluster-admin` for the upgrade itself and leave again.

**MUST NOT:**

- Omit `-f`, on any change. Helm replaces a release's values with what
  it is given, so an upgrade without the file resets them to the
  chart's defaults.
- Set `management.bootstrap: true` to make the composition
  authoritative.
- Judge a composite while the core is restarting.
- Expect a merged change to reach a running plane on its own.

**MAY:**

- Use `--reuse-values` where the release has values and no drift to
  preserve.
- Leave a plane on an older chart than git describes, deliberately.
  Nothing detects it.

## Discussion

**Why merging is not enough.** A boot plane installs Crossplane, and the
`Release` describing it carries `Observe` alone once the plane is
running: the plane reconciles the composite that describes itself and
never acts on the release that installed it. A merged change reaches the
next plane a boot plane builds, and never this one — with no error,
because the `Release` reports `Synced` while doing nothing.

**Why `helm upgrade` works at all.** `Observe` is Crossplane declining
to act, not a lock. The release is an ordinary Helm release whose state
lives in Secrets, and any client with cluster access can upgrade it.

**What this does not share with Argo's.** `management-argo` carries a
values block whose one key is the Application pointing the plane at
git, so an upgrade that omits it deletes that Application and prunes
what lies beneath. What this release's values hold is resource sizing,
so losing them costs a throttled reconciler rather than an installation
— which is why the two are separate documents rather than one with
branches.

**What stops while you do it.** The pod being replaced is the one
reconciling every managed resource, so for the length of the restart
nothing is reconciled. Nothing is deleted and nothing drifts: a
composite picks up where it left off rather than where it was. That is
the difference from Argo, whose restart costs a pause in syncing and
nothing else.

**Where the CRDs come from.** Not the chart. It ships none — a
`crossplane-init` init container applies them from the image as the pod
starts, so they move with the version rather than with Helm. Neither
the usual warning about `crds/` never upgrading nor Argo's arrangement
of rendering them as templates applies here.

**What an upgrade does to the packages.** The core owns every provider
and function Deployment, so a core upgrade rolls all of them. A package
declaring support for a narrower core range goes `Unhealthy` rather
than refusing the upgrade, which is why they are checked afterwards
rather than trusted.

**Why no bot bumps this.** Renovate has the `crossplane` and `argo-cd`
charts disabled. It reads the boot chart's dependency and cannot see the
composition, so a bump it made alone would fail `check-versions` rather
than land. Crossplane carries a second reason: a boot plane and the
plane it builds must agree across the handover, where a composite
created by one is adopted by the other.

**Why not `management.bootstrap: true`.** It switches both Releases to
`Observe, Create, Update, LateInitialize`, which makes the composition
authoritative and looks like the declarative answer. It also hands this
Crossplane the Helm release that installs it. A bad render then leaves
nothing standing that can fix it except a fresh boot plane.

**When to rebuild instead.** A rebuild through a boot plane installs
from the composition and leaves no divergence. Worth it for a major, or
where the core and the packages need to move together — see
[crossplane-app-deployment](crossplane-app-deployment.md).

**What this does not cover.** The provider and function packages
themselves, which are ordinary merges in
`infra/platform/crossplane-providers/providers.yml`, and what those pods
may take, which is the `DeploymentRuntimeConfig` objects in
`infra/helm/management-plane`. Argo applies both from git, so neither
needs any of this.

## References

- [argocd-upgrades](argocd-upgrades.md) — the same tier, the opposite
  hazards
- [crossplane](crossplane.md) — what the engine does with what it
  installs
- [crossplane-providers](crossplane-providers.md) — the packages, and
  what late-initialisation owns
- [crossplane-app-deployment](crossplane-app-deployment.md) — building a
  plane, and the rebuild path
