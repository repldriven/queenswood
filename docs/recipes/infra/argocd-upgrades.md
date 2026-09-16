# Upgrading and reconfiguring Argo CD

<!-- tessl-plugin: deployment -->

## Status

**Verified**, 2026-08-24, on both paths: a configuration change (Argo's
own resource requests) and a version change (chart 10.2.1 to 10.4.0).

## Problem

You want to change Argo CD on a management plane: its chart version, a
resource request, or any other chart value.

## Solution

### Prerequisites

- A management plane running in the installation's folder.
- Step 1 — write access to this repository.
- The capability each step names. Ours is a Google group; yours may differ.

```bash
# the installation code, e.g. qw01
export QW_CODE=qw01
export WORK=$(mktemp -d)
export REL="release.helm.m.crossplane.io/argocd-$QW_CODE-c-mgmt"
export VALUES="$WORK/argocd-values.json"
```

### 1a. A version change

See what the new chart would render, against the values this plane
actually runs:

```bash
# the version now, and the one being moved to
export FROM=10.4.0
export TO=10.9.1

helm repo add argo https://argoproj.github.io/argo-helm
helm repo update argo
helm --kube-context "$QW_CODE-mgmt" get values argocd -n argocd -o json \
  > "$WORK/running.json"
for V in "$FROM" "$TO"; do
  helm template argocd argo/argo-cd --version "$V" -n argocd \
    -f "$WORK/running.json" > "$WORK/render-$V.yaml"
done
diff "$WORK/render-$FROM.yaml" "$WORK/render-$TO.yaml"
```

Read it for objects appearing or disappearing, and for CRD fields being
removed. Image tags will differ, and so will the `checksum/cm` and
`checksum/cmd-params` annotations — that is Helm saying a ConfigMap
moved and the pods will restart, not a finding.

Then two files, to the same number:

- `infra/helm/boot-management-plane/Chart.yaml` — the `argo-cd`
  dependency version.
- `infra/platform/crossplane-xrds/xmanagementplane-composition.yml` —
  `management-argo`'s `chart.version`.

The rendered diff shows nothing about how the product behaves. Read the
Argo CD release notes for the app versions it crosses.

### 1b. A configuration change

One file:

- `infra/platform/crossplane-xrds/xmanagementplane-composition.yml` —
  `management-argo`'s `values:` block.

### 1c. Either way

```bash
just check-versions
```

Merge before going further.

### 2. Wait for the plane to render it

**As the installation's platform viewer.** Ours is
`grp-gcp-<code>-platform-viewer@`, populated rather than joined.

The plane does not upgrade the release, but it does compose the object
describing it — with every patch applied. Wait until that object
carries what you merged — the version, the values, or both:

```bash
kubectl --context "$QW_CODE-mgmt" -n crossplane-system get "$REL" \
  -o jsonpath='{.spec.forProvider.chart.version}{"\n"}'

kubectl --context "$QW_CODE-mgmt" -n crossplane-system get "$REL" \
  -o jsonpath='{.spec.forProvider.values}' | python3 -m json.tool
```

A version change shows in the first, anything else in the second. Until
your change is there the composite has not reconciled it, and there is
nothing yet to upgrade to.

### 3. Take the values and the version from that object

```bash
kubectl --context "$QW_CODE-mgmt" -n crossplane-system get "$REL" \
  -o jsonpath='{.spec.forProvider.values}' > "$VALUES"
VERSION=$(kubectl --context "$QW_CODE-mgmt" -n crossplane-system get "$REL" \
  -o jsonpath='{.spec.forProvider.chart.version}')
```

That file is the complete set of values a boot plane would install with,
`extraObjects` included. It is JSON, which `helm -f` reads. Do not add
to it or retype it.

### 4. Compare both halves with what is running

The version and the values move separately, so check both. The chart
now, against the one step 3 read:

```bash
helm --kube-context "$QW_CODE-mgmt" list -n argocd
echo "$VERSION"
```

Then the values:

```bash
JQ='paths(scalars) as $p | "\($p|map(tostring)|join(".")) = \(getpath($p))"'

helm --kube-context "$QW_CODE-mgmt" get values argocd -n argocd -o json \
  | jq -r "$JQ" | sort > "$WORK/running.txt"
jq -r "$JQ" "$VALUES" | sort > "$WORK/desired.txt" \
  && diff "$WORK/running.txt" "$WORK/desired.txt"
```

One line per value, so a change is a line rather than a brace. Chained,
because an unreadable `$VALUES` otherwise leaves an empty file and
`diff` reports every value of the running release as deleted — which
reads as catastrophe and is a missing file.

What to expect, by what you changed:

- **A version change** — the `CHART` column differs from `$VERSION`,
  and the values diff is **empty**. An empty diff is the right answer
  here, not a step that failed.
- **A configuration change** — the `CHART` column matches `$VERSION`,
  and the values diff is your change.

Lines marked `>` are your change. Lines marked `<` are values the
running release has and the composed object does not: drift from an
earlier hand-upgrade, which applying this file would remove. Stop there,
merge that drift into the composition, and start again.

### 5. Upgrade

**As the installation's cluster admin.** Ours is
`grp-gcp-<code>-cluster-admin@` — join for this step, then leave.

```bash
helm repo add argo https://argoproj.github.io/argo-helm
helm repo update argo
helm --kube-context "$QW_CODE-mgmt" upgrade argocd argo/argo-cd \
  --version "$VERSION" -n argocd -f "$VALUES" --force-conflicts
```

### 6. Verify, in this order

**As the installation's platform viewer again.**

```bash
# the bootstrap Application still exists
kubectl --context "$QW_CODE-mgmt" -n argocd get application management-plane

# what landed, and what a rollback would return to
helm --kube-context "$QW_CODE-mgmt" history argocd -n argocd

# every component came back
kubectl --context "$QW_CODE-mgmt" -n argocd get pods

# and Argo still reconciles
kubectl --context "$QW_CODE-mgmt" -n argocd get applications
```

The newest revision reads `deployed`, against the chart version you
pinned — which is the only place a version change shows up as having
happened.

## Failures

**No `management-plane` Application after the upgrade.** The first check
in step 6, and the one that matters: without it the plane reads no git
at all, and nothing else it reconciles will recover on its own. Recreate
it from `values.extraObjects` on the same composed `Release` step 3
read, before anything else.

**A newest revision that is not `deployed`, or not the version you
pinned.** Roll back, and to a release revision rather than a chart
version — the `REVISION` column is a small integer counting this
release's upgrades, and the revision below the newest is where a
rollback goes:

```bash
helm --kube-context "$QW_CODE-mgmt" history argocd -n argocd
helm --kube-context "$QW_CODE-mgmt" rollback argocd <revision> -n argocd
```

**A `helm upgrade` refused with `conflict occurred while applying
object argocd/argocd-cm`, naming `argocd-controller` and a field under
`.data`.** Two managers write that ConfigMap: the chart through Helm,
and the management-plane chart through Argo, which applies with
`ServerSideApply=true` and owns whatever it declares —
`resource.customizations` and `resource.exclusions`. Helm applies
server-side from 4.0, so it refuses to overwrite another manager's
field rather than silently taking it. `--force-conflicts` in step 5 is
what proceeds; Argo reclaims the field on its next sync, so the end
state is what the composition says either way.

**A `helm upgrade` refused with `invalid ownership metadata`.** A
resource in the release was applied by hand — during an incident, say —
and Helm will not adopt one it did not create. Annotate it back into
the release with `meta.helm.sh/release-name` and
`meta.helm.sh/release-namespace` rather than deleting it, since what
gets deleted may be carrying state.

## Rules

**MUST:**

- Merge the change before upgrading the plane.
- Change the version in both the boot chart and the composition, and
  run `just check-versions`. It fails on one without the other.
- Build the values file from the composed `Release`, never by hand.
- Spell the kind as `release.helm.m.crossplane.io`. The short name
  resolves to provider-helm's cluster-scoped `Release` and reports
  the object as not found.
- Pin `--version` to the same object's `chart.version`, re-read after
  the merge rather than carried over from an earlier attempt.
- Pass `--force-conflicts`, since Helm applies server-side and Argo
  owns the fields it declares in `argocd-cm`.
- Render both chart versions against the running values before
  merging a version change, and read the Argo CD release notes for
  the app versions it crosses.
- Confirm `management-plane` still exists before anything else.
- Join `cluster-admin` for the upgrade itself and leave again.
  Everything else here is a viewer's.

**MUST NOT:**

- Upgrade with a values file that omits `extraObjects`.
- Set `management.bootstrap: true` to make the composition
  authoritative.
- Expect a merged change to reach a running plane on its own.

**MAY:**

- Use `--reuse-values` where the release has no drift to preserve.
- Leave a plane on an older chart than git describes, deliberately.
  Nothing detects it.

## Discussion

`provider-helm` reconciles on spec drift alone, so what makes it
reinstall is the `Release`'s own fields changing — a chart `version`
bump does it, even patch-level, and editing what the chart renders does
not. That is why a version is the thing pinned and compared here.

**Why the values file goes to a temporary directory.** `$WORK` keeps it
out of a repository. It carries the management project's id, and a
checkout is the one place that must not acquire one.

**Why merging is not enough.** A boot plane installs Argo, and the
`Release` describing it carries `Observe` alone once the plane is
running: the plane reconciles the composite that describes itself and
never acts on the release that installed it. A merged change therefore
reaches the next plane a boot plane builds, and never this one — with
no error, because the `Release` reports `Synced` while doing nothing.
That is the policy working.

**Why `helm upgrade` works at all.** `Observe` is Crossplane declining
to act, not a lock. The release is an ordinary Helm release whose state
lives in `sh.helm.release.v1.argocd.*` Secrets, and any client with
cluster access can upgrade it. Crossplane observes the result
afterwards and does nothing.

**Why the values file comes from the composed object.** The composition
gives
this release exactly one value: `extraObjects`, holding the
`management-plane` Application — the thing that points the plane at
git. Helm deletes what a previous manifest carried and a new one does
not. That Application has `resources-finalizer.argocd.argoproj.io` and
`prune: true`, so deleting it cascades through the child Applications
to the composites, and an instance's cluster and node pool are managed
resources that permit `Delete`. A values file that forgets one key
reaches GCP. Taking the file from the `Release` rather than writing one
means the key cannot be forgotten: it arrives with everything else, and
already patched — the repository, the revision and the path inside the
bootstrap Application are filled in on the composed object even though
the release itself is only observed.

**Why merge first.** The manifest is the record of what a plane should
be. Upgrading first leaves a plane running something git does not
describe, and the next rebuild reverts it without anyone deciding to.

**Why not `management.bootstrap: true`.** It switches both Releases to
`Observe, Create, Update, LateInitialize`, which makes the composition
authoritative and looks like the declarative answer. It also hands the
plane's Crossplane the Helm release installing the Argo that applies
what that Crossplane reads. A bad render then leaves nothing standing
that can fix it except a fresh boot plane.

**Why no bot bumps this.** Renovate has the `argo-cd` and `crossplane`
charts disabled outright. It reads the boot chart's dependency and
cannot see the composition, so a bump it made alone would leave the two
copies disagreeing and fail `check-versions` rather than land — which
makes `check-versions` the thing that permits hand-bumping rather than
merely tidying after it. The consequence is that nothing will remind
you: every other dependency here arrives on a Monday morning, and this
one waits until somebody looks.

**What the CRDs do, against expectation.** Helm installs a chart's
`crds/` directory and never upgrades it, so the usual warning about a
chart's CRDs lagging its version applies almost everywhere. Not here:
this chart renders them as ordinary templates under `crds.install`,
so an upgrade updates them like anything else. Worth knowing before
someone adds a step to check.

**Why `cluster-admin` is step 5 alone.** `platform-viewer` carries
`container.viewer`, which reads Kubernetes objects and writes none —
enough to read the composed `Release`, the running values and every
verification. The upgrade replaces Deployments, a StatefulSet, the CRDs
and Helm's own release Secrets, which needs `roles/container.admin`. So
it is joined for that one step and left again, the same shape
[instance-rebuild-cluster](instance-rebuild-cluster.md) uses for its
break-glass moments.

**What the restart costs.** Every component is replaced, so for a minute
or two nothing syncs: an Application mid-operation resumes on the other
side, and one that was about to start simply starts later. Argo holds no
state of its own — what it knows is git and the cluster — so there is
nothing to lose in the gap. That is what makes this the safe half of the
tier. Crossplane's own upgrade is the other half, and stops every
managed resource being reconciled rather than merely being read.

**When to rebuild instead.** A rebuild through a boot plane installs
from the composition and leaves no divergence, which is worth it when
several changes have accumulated, or when nobody is sure what the plane
is running any more. See
[management-plane-install](management-plane-install.md).

**What this does not cover.** Argo applies `infra/helm/management-plane`
from git on every sync, so anything in that chart — the
`DeploymentRuntimeConfig` objects shaping providers and functions among
them — is an ordinary merge that lands on the running plane with none
of this. Only the release Argo itself runs from is `Observe`.

## References

- [argocd-apps](argocd-apps.md) — how Argo applies what it does own
- [management-plane-install](management-plane-install.md) — building a
  plane, and the rebuild path
- [management-plane-install](management-plane-install.md) — what a plane
  cannot change about itself
