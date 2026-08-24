# Upgrading and reconfiguring Argo CD

<!-- tessl-plugin: deployment -->

## Status

**Verified.** Followed end to end on this installation's management
plane, to give Argo's own components resource requests. Eight defects
in the steps were found by following them and are fixed here; the
release went from revision 7 to 8 with the chart version unchanged, and
every Application stayed `Synced`.

## Problem

You want to change Argo CD on a management plane: its chart version, a
resource request, or any other chart value.

## Solution

Every command below reads these:

```bash
export CODE=qw01   ## example, qw01
export WORK=$(mktemp -d)
export REL="release.helm.m.crossplane.io/argocd-$CODE-c-mgmt"
export VALUES="$WORK/argocd-values.json"
```

`$WORK` keeps the values file out of a repository. It carries the
management project's id, and a checkout is the one place that must not
acquire one.

### 1a. A version change

See what the new chart would render, against the values this plane
actually runs:

```bash
export FROM=10.2.1 TO=10.4.0   ## example

helm repo add argo https://argoproj.github.io/argo-helm
helm repo update argo
helm --kube-context "$CODE-mgmt" get values argocd -n argocd -o json \
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

The plane does not upgrade the release, but it does compose the object
describing it — with every patch applied. Wait until that object
carries what you merged — the version, the values, or both:

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

That file is the complete set of values a boot plane would install with,
`extraObjects` included. It is JSON, which `helm -f` reads. Do not add
to it or retype it.

### 4. Compare it with what is running

```bash
JQ='paths(scalars) as $p | "\($p|map(tostring)|join(".")) = \(getpath($p))"'

helm --kube-context "$CODE-mgmt" get values argocd -n argocd -o json \
  | jq -r "$JQ" | sort > "$WORK/running.txt"
jq -r "$JQ" "$VALUES" | sort > "$WORK/desired.txt" \
  && diff "$WORK/running.txt" "$WORK/desired.txt"
```

One line per value, so a change is a line rather than a brace. Chained,
because an unreadable `$VALUES` otherwise leaves an empty file and
`diff` reports every value of the running release as deleted — which
reads as catastrophe and is a missing file.

Lines marked `>` are your change. Lines marked `<` are values the
running release has and the composed object does not: drift from an
earlier hand-upgrade, which applying this file would remove. Stop there,
merge that drift into the composition, and start again.

### 5. Upgrade

```bash
helm repo add argo https://argoproj.github.io/argo-helm
helm repo update argo
helm --kube-context "$CODE-mgmt" upgrade argocd argo/argo-cd \
  --version "$VERSION" -n argocd -f "$VALUES"
```

### 6. Verify, in this order

```bash
# the bootstrap Application still exists
kubectl --context "$CODE-mgmt" -n argocd get application management-plane

# every component came back
kubectl --context "$CODE-mgmt" -n argocd get pods

# and Argo still reconciles
kubectl --context "$CODE-mgmt" -n argocd get applications
```

The first is the one that matters. Everything else is recoverable.

### If it goes wrong

```bash
helm --kube-context "$CODE-mgmt" history argocd -n argocd
helm --kube-context "$CODE-mgmt" rollback argocd <revision> -n argocd
```

If `management-plane` is gone, recreate it from the same object's
`values.extraObjects` before anything else: without it the plane reads
no git at all.

## Rules

**MUST:**

- Merge the change before upgrading the plane.
- Change the version in both the boot chart and the composition.
  `check-versions` fails on one without the other.
- Build the values file from the composed `Release`, never by hand.
- Spell the kind as `release.helm.m.crossplane.io`. The short name
  resolves to provider-helm's cluster-scoped `Release` and reports
  the object as not found.
- Pin `--version` to the same object's `chart.version`.
- Render both chart versions against the running values before
  merging a version change, and read the Argo CD release notes for
  the app versions it crosses.
- Confirm `management-plane` still exists before anything else.

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

**When to rebuild instead.** A rebuild through a boot plane installs
from the composition and leaves no divergence, which is worth it when
several changes have accumulated, or when nobody is sure what the plane
is running any more. See
[crossplane-app-deployment](crossplane-app-deployment.md).

**What this does not cover.** Argo applies `infra/helm/management-plane`
from git on every sync, so anything in that chart — the
`DeploymentRuntimeConfig` objects shaping providers and functions among
them — is an ordinary merge that lands on the running plane with none
of this. Only the release Argo itself runs from is `Observe`.

## References

- [argocd](argocd.md) — how Argo applies what it does own
- [crossplane-app-deployment](crossplane-app-deployment.md) — building a
  plane, and the rebuild path
- [queenswood-installation](queenswood-installation.md) — what a plane
  cannot change about itself
