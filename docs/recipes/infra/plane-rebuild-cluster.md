# Rebuilding the plane's cluster

<!-- tessl-plugin: deployment -->

## Status

**Verified**, against one installation with one instance, the instance
`state: down`. Step 10 carries that run's timings.

## Problem

You need to replace the cluster a management plane runs on — a field
that identifies the cluster has changed, or its name is wrong — while
the folder, the projects, the identities, the network and every instance
the plane manages stay where they are.

## Solution

The plane builds its own successor, the successor takes the estate over,
and the cluster it replaces is deleted afterwards. No boot plane and no
seed identity: the platform identity already holds `container.admin` and
`compute.admin` inside the folder, which is all a second cluster in the
management project needs. A boot plane exists because nothing inside the
folder can act before the folder does, which is not the case here.

### Prerequisites

- A plane that is up, with nothing unready, and `MGMT_CTX` reaching it.
- The change forcing the rebuild ready to merge, giving the cluster a
  name **and** a composition slot the composite is not already using.
  Step 2 is that check.
- Write access to this repository. The installations repository is only
  read.
- The capability each step names. Ours is a Google group; yours may
  differ.
- Nothing in flight on an instance: for the length of this two planes
  reconcile one estate, and then one of them stops.
- Every project in the estate carrying `adopt` in the manifest that
  declares it, which is the one precondition a rebuild has that nothing
  else does. upjet records a project's external name after its own
  create, so a plane that never created one has nothing to record, tries
  to create it, and is answered `409 Requested entity already exists`
  for ever. `just queenswood-installation-manifest` writes the
  management project's; an instance's and the recovery project's are
  added by hand once those projects exist, and nothing has ever needed
  them before a rebuild:

  ```bash
  grep -rn 'projectId' "$QW_INSTALLATIONS_REPO/$QW_CODE" | grep -v adopt
  ```

  Every id that lists wants an `adopt` beside it, spelled
  `projects/<id>`, merged before the swap. It is a no-op for the plane in
  charge, whose managed resources already carry that external name.

Where no plane is running at all — its cluster already gone — this is
not the procedure. Nothing is left to build a successor, so raise a boot
plane and install, which adopts the folder, the projects and the
identities that survived; see
[plane-install](plane-install.md).

Every step below reads these, and a `just` recipe defaults the code where
the shell does not — which is why an unset one shows up as a context
called `-mgmt` rather than as an error:

```bash
# the installation code, e.g. qw01
export QW_CODE=qw01
export WORK=$(mktemp -d)
```

`NEXT` joins them in step 4, once the plane has said what it built.

Nothing is restored here. The plane holds no state of its own: every
fact it reconciles from is in GCP, in git or in Secret Manager, and the
managed resources on it are a cache of what the cloud already says. What
stands in for a restore is adoption, and adoption is by name — which is
why the naming rule in [cloud-naming](../practices/cloud-naming.md) is
load-bearing rather than tidy.

### 1. Record what the plane holds

**As the installation's platform viewer.** Ours is
`grp-gcp-<code>-platform-viewer@`, populated rather than joined.

```bash
just crossplane-slots > "$WORK/slots-before.txt"
just crossplane-external-names > "$WORK/names-before.txt"
just crossplane-unready
just argo-apps-status
```

`WORK` is a scratch directory rather than the working tree, and outside
the installations repository too.
Both files are the estate's own identifiers — folder ids, project ids,
every principal — which belong in neither, and the hook that catches
them reports after the fact.

Every slot with its management policies, every resource whose cloud
identifier is not its Kubernetes name, and a plane with nothing
outstanding. Step 7 diffs against these two files: each line is a cloud
resource the successor has to adopt rather than create.

Start from a plane that is already healthy. A resource that was not
`Synced` before the rebuild will not be diagnosable after it.

### 2. Confirm the successor gets a name and a slot of its own

**No cloud capability.** The composition, and `slots-before.txt`.

Two names have to differ from what is live, and for different reasons.

**The cluster's**, because GCP holds one name once. A change that keeps
it — a ForceNew field such as `zone`, `region`, `datapathProvider` or
`inTransitEncryptionConfig`, applied on its own — cannot be done this
way at all: nothing can stand a second cluster up beside the first, so
that is a delete and an install rather than a swap.

A ForceNew field riding along with a rename is free, though, and it is
worth looking for one. The successor is created rather than altered, so
it takes every immutable field the composition sets — which is the only
way the plane's cluster acquires one it was built without.

**The composition slot's**, because a composed resource is identified by
it. Reuse the slot the live cluster sits in and Crossplane matches the
existing object to it and keeps its `metadata.name`: the new name is
ignored, the composite reports `Synced`, and nothing whatever happens.
See [crossplane-design](crossplane-design.md).

### 3. Merge the change, and let the plane build the successor

**No cloud capability.** Write access to this repository.

```bash
just check-versions
```

Merge. The plane's Argo picks the composition up and its Crossplane
composes the successor — the cluster, its pool and the node identity —
in the same project, on the same subnet, from the same manifest.

The cluster it is running on leaves its slot at the same moment. The
managed resource is deleted from the plane; the cluster is not, because
`retain` withholds `Delete` from it. From here it runs with nothing
reconciling it, which is what makes it the way back.

```bash
just crossplane-unready
kubectl --context "$QW_CODE-mgmt" -n crossplane-system \
  get cluster.container.gcp.m.upbound.io
```

Ten minutes or so for the cluster, and the pool after it. The successor
is empty when this finishes: the `Release`s that install Crossplane and
Argo are `Observe` on the plane reconciling them, deliberately, so no
plane installs onto anything.

### 4. Reach the successor

**As the installation's cluster admin.** Ours is
`grp-gcp-<code>-cluster-admin@` — join for steps 4 and 5, then leave.

By hand rather than with `just plane-ctx`, which renames whatever it
fetches to `MGMT_CTX` — and `MGMT_CTX` still has to reach the plane in
charge until step 8.

Ask the plane which cluster it built rather than deriving the name. The
one it is running on left its slot in step 3, so it is not here — every
`Cluster` listed is one the plane composes, and the successor is the one
in the management project:

```bash
COLS='NAME:.metadata.name,PROJECT:.spec.forProvider.project'
COLS="$COLS,READY:.status.conditions[?(@.type==\"Ready\")].status"

kubectl --context "$QW_CODE-mgmt" -n crossplane-system \
  get cluster.container.gcp.m.upbound.io -o custom-columns="$COLS"
```

For this composition that is `<code>-c-mgmt`, the general form being
`<code>-<env>-<label>` with the plane's two fixed at `c` and `mgmt`.

```bash
export NEXT_CLUSTER=<the name that listed>
export PROJECT=$(just _mgmt-project)
export ZONE=$(gcloud container clusters list --project="$PROJECT" \
                --filter="name=$NEXT_CLUSTER" --format='value(location)')
gcloud container clusters get-credentials "$NEXT_CLUSTER" \
  --zone="$ZONE" --project="$PROJECT"
export NEXT="gke_${PROJECT}_${ZONE}_${NEXT_CLUSTER}"
kubectl config use-context "$QW_CODE-mgmt"
```

`get-credentials` makes its own context current, so put the plane in
charge back before doing anything else.

Check `NEXT` before every step that writes. An unset variable makes
`--context ""` mean the current context, which is the
plane in charge, and nothing says so — a write meant for the successor
lands on the estate instead. The nodes are the tell, and they are the
tell because of the name this rebuild is for:

```bash
kubectl --context "$NEXT" get nodes -o name | head -1
```

`gke-gke-…` is the cluster being replaced. `gke-<code>-…` is the
successor.

### 5. Install Crossplane and Argo onto it

**As the installation's cluster admin.**

The successor's composite will `Observe` these two releases rather than
install them, so they have to be the releases it expects: same chart,
same version, same values, same release name, same namespace. All five
come off the plane in charge, which composes the objects describing
them.

List them rather than spell them. An object's name is
per-installation, a release's is not, and the two do not follow one
pattern:

```bash
REL='release.helm.m.crossplane.io'
COLS='OBJECT:.metadata.name'
COLS="$COLS,RELEASE:.metadata.annotations.crossplane\.io/external-name"
COLS="$COLS,CHART:.spec.forProvider.chart.name"
COLS="$COLS,VERSION:.spec.forProvider.chart.version"
COLS="$COLS,NS:.spec.forProvider.namespace"

kubectl --context "$QW_CODE-mgmt" -n crossplane-system \
  get "$REL" -o custom-columns="$COLS"
```

Five columns, which is everything the two installs need. Then the
objects by the chart they carry rather than by a name template, and the
values off each one:

```bash
CP_SEL='{.items[?(@.spec.forProvider.chart.name=="crossplane")].metadata.name}'
ARGO_SEL='{.items[?(@.spec.forProvider.chart.name=="argo-cd")].metadata.name}'

CP=$(kubectl --context "$QW_CODE-mgmt" -n crossplane-system \
  get "$REL" -o jsonpath="$CP_SEL")
ARGO=$(kubectl --context "$QW_CODE-mgmt" -n crossplane-system \
  get "$REL" -o jsonpath="$ARGO_SEL")

VER='{.spec.forProvider.chart.version}'

for R in "$CP" "$ARGO"; do
  kubectl --context "$QW_CODE-mgmt" -n crossplane-system \
    get "$REL/$R" -o jsonpath='{.spec.forProvider.values}' \
    > "$WORK/$R.values.json"
done

CP_VER=$(kubectl --context "$QW_CODE-mgmt" -n crossplane-system \
  get "$REL/$CP" -o jsonpath="$VER")
ARGO_VER=$(kubectl --context "$QW_CODE-mgmt" -n crossplane-system \
  get "$REL/$ARGO" -o jsonpath="$VER")
```

The version comes off the object rather than out of a file or a doc,
even though `just check-versions` is what keeps the boot chart and the
composition agreeing on it. The composite will compare what is installed
against this object, so this object is what to install.

Spell the kind `release.helm.m.crossplane.io`: the short name resolves
to provider-helm's cluster-scoped `Release` and reports the object as
not found. The release name is the `RELEASE` column, never the object's
own name — an object is named for the installation, because the plane
holds one per installation, and a release names one release inside one
cluster, so it is `crossplane` and `argocd`.

Crossplane first, because Argo's own bootstrap installs resources of
kinds Crossplane owns:

```bash
helm repo add crossplane-stable https://charts.crossplane.io/stable
helm repo update crossplane-stable
helm --kube-context "$NEXT" upgrade --install crossplane \
  crossplane-stable/crossplane --version "$CP_VER" \
  -n crossplane-system --create-namespace -f "$WORK/$CP.values.json"
```

Then Argo, in two passes. The chart renders its CRDs as templates
rather than shipping them in a `crds/` directory, so they are applied in
the same pass as everything else — and helm resolves a REST mapping for
every object in a manifest before applying any of it. The `Application`
in `extraObjects` is a resource of a kind this release installs, so on a
cluster that has never had Argo it cannot be built. The first pass
leaves it out:

```bash
helm repo add argo https://argoproj.github.io/argo-helm
helm repo update argo
helm --kube-context "$NEXT" upgrade --install argocd argo/argo-cd \
  --version "$ARGO_VER" -n argocd --create-namespace \
  -f "$WORK/$ARGO.values.json" --set-json 'extraObjects=[]'

kubectl --context "$NEXT" get crd applications.argoproj.io
```

The second passes the file unchanged, and is what puts
`management-plane` on the cluster:

```bash
helm --kube-context "$NEXT" upgrade --install argocd argo/argo-cd \
  --version "$ARGO_VER" -n argocd --create-namespace \
  -f "$WORK/$ARGO.values.json"
```

Never without `-f`, on either pass. The values file carries
`extraObjects`, and the `management-plane` Application lives there — it
is what pulls the providers, the provider configuration, this
repository's XRDs and finally the installation's own manifest. Argo
installed without it comes up reconciling nothing at all, and the first
pass empties that one key rather than dropping the file.

What the composite observes afterwards is a release, not a history, so
two passes and one leave the same thing behind.

### 6. Let the successor take the estate

**As the installation's platform viewer.**

Both releases first, since nothing below reports anything until they are
there:

```bash
helm --kube-context "$NEXT" list -A
```

Then the Applications, which arrive with Argo and not before:

```bash
kubectl --context "$NEXT" -n argocd get application
```

Four waves, in order: the provider and function packages, the provider
configuration and secret store, the XRDs and Compositions, then the
installation's own manifest from the private repository. Crossplane on
the successor then composes a fresh managed resource for everything the
estate is made of, and each one observes its cloud resource by external
name and adopts it.

The composites answer only once the third wave has established the
XRDs, which is when these two start being the right things to read:

```bash
just argo-apps-status "$NEXT"
just crossplane-unready "$NEXT"
```

The identities need nothing. Workload Identity's pool is per project
rather than per cluster, and the Kubernetes service account names are
pinned by the runtime configuration, so the successor's pods
authenticate as the same identities the plane's did. An access binding
adopts whatever its object is called, because an IAM member's external
name is the project, the role and the member rather than the name of
the resource holding it.

Two of an instance's records adopt on a second pass rather than the
first. Their names carry a value GCP generated — the ACME challenge
label, and the address the A records point at — so the composition
reads them from another resource's observed status and the templated
step composes nothing at all until it is there. On a plane that has
never seen them that is one reconcile with those records missing, not a
reconcile that composes them wrong.

Both planes are reconciling the estate at this point, from the same
revision and to the same desired state. That is expected, and step 8 is
where it stops.

### 7. Check it adopted rather than recreated

**As the installation's platform viewer.**

```bash
just crossplane-slots "$NEXT" > "$WORK/slots-after.txt"
just crossplane-external-names "$NEXT" > "$WORK/names-after.txt"
diff "$WORK/slots-before.txt" "$WORK/slots-after.txt"
diff "$WORK/names-before.txt" "$WORK/names-after.txt"
```

Every slot from step 1 present with the same policies, and the only
differences the ones the change accounts for — the cluster, its pool and
its node identity, plus whatever the old slot names were. A new slot
nothing renamed is a resource being built beside one that already
exists, which is what this step is for. Stop there rather than after it
finishes.

Then the two planes field by field, which the slot lists cannot check:

```bash
just crossplane-drift "$QW_CODE-mgmt" "$NEXT"
```

Equal counts and no differing fields is adoption complete. A field only
the plane in charge sets is one late-initialisation filled there and the
composition does not state: the successor cannot learn it, and whatever
the provider does with it absent is what it will do from now on.

A `down` instance's servers are stopped, and a stopped Cloud SQL server
refuses every write — so anything the successor tries to *change* there
fails with `Invalid request since instance is not running`. Adoption
itself needs no write: a resource whose desired state already matches is
observed, taken over, and late-initialised from what came back. So that
error does not mean waiting for the instance; it means an update is being
attempted at all, and the fix is to make it unnecessary rather than to
start a server.

The instances are the half nobody thinks of. The successor reapplies
each unit's composite from the manifests repository, so every instance's
project, cluster, database, buckets, zone and secrets are adopted by
managed resources that have never seen them — and an instance that was
`down` stays down, because its state is a field in a file.

### 8. Swap

**As the installation's cluster admin.** Ours is
`grp-gcp-<code>-cluster-admin@` — join for this step, then leave.

Break-glass, and the moment the successor becomes the plane. Only once
step 7 is clean.

```bash
kubectl --context "$QW_CODE-mgmt" -n crossplane-system \
  scale deploy crossplane --replicas=0
kubectl --context "$QW_CODE-mgmt" -n crossplane-system \
  scale deploy --all --replicas=0
kubectl --context "$QW_CODE-mgmt" -n crossplane-system get deploy
just plane-ctx
```

The core first and then everything: the core reconciles the provider
packages, so a provider scaled down while it runs comes straight back.
Nothing puts these back on its own — the `Release` that installed
Crossplane is `Observe` on the plane running it, and Argo manages no
`Deployment` in that namespace.

Scaling rather than deleting. Deleting the composite would delete what
it composed, subject to each resource's policies, and the ones carrying
`Delete` are the access bindings — including the Workload Identity
binding Crossplane authenticates with, taken away halfway through taking
it away.

`plane-ctx` now fetches the successor and renames its context to
`MGMT_CTX`, so every recipe and every habit points at the plane in charge
again. What that name pointed at is kept as `MGMT_CTX-previous`, which is
how the cluster being retired stays reachable — step 9 needs it, and so
does going back.

### 9. Delete what it replaced

**As the installation's cluster admin.** Ours is
`grp-gcp-<code>-cluster-admin@` — join for this step, then leave.

Three things make it safe, and they are conditions rather than a waiting
period. `crossplane-drift` accounts for every resource; the Application
reading the private manifests repository is `Synced`, which is what
proves the credential rather than the chart; and the retired cluster
holds nothing that exists nowhere else:

```bash
for NS in crossplane-system argocd external-secrets; do
  diff <(kubectl --context "$QW_CODE-mgmt-previous" -n $NS get secret -o name \
           | sort) \
       <(kubectl --context "$QW_CODE-mgmt" -n $NS get secret -o name | sort)
done
```

`sh.helm.release.v1.*` on the retired side only is the expected answer
and nothing else should be: it is Helm's rollback history for the two
charts, which goes with the cluster it belongs to. Anything else listed
is state to move before deleting.

Deleting it ends the way back — scaling those controllers up and
reverting the merge — so weigh that against paying for a cluster that
reconciles nothing. What replaces it afterwards is a boot plane and an
adopt, which the prerequisite about `adopt` is what makes possible.

```bash
kubectl --context "$QW_CODE-mgmt-previous" get nodes -o name | head -1
gcloud container clusters delete <the cluster it replaced> \
  --project="$PROJECT" --zone=<zone>
```

The first line is the check that the name still means what you think:
`gke-gke-…` is the cluster being deleted.

The node pool goes with it, and so does the kubeconfig `Secret` it wrote.

Then whatever else the change renamed and nothing now holds — a node
identity and its two bindings, for a rename that moved them. Reported by
nothing, reconciled by nothing, and still there.

**As the installation's platform admin** for that part, which is a
different capability: no human one holds `iam.serviceAccountAdmin`, the
platform identity does, and `platformAdmin` is `serviceAccountTokenCreator`
on it. So the delete is made as that identity rather than as you:

```bash
PLATFORM="sa-$QW_CODE-platform@$PROJECT.iam.gserviceaccount.com"

gcloud iam service-accounts delete \
  "sa-$QW_CODE-c-nodes@$PROJECT.iam.gserviceaccount.com" \
  --project="$PROJECT" --impersonate-service-account="$PLATFORM"
```

The flag rather than ADC impersonation, so nothing outlives the command.
Deleting the account takes its bindings with it.

### 10. Record what happened

- **RTO** — wall clock from the merge to the successor reconciling the
  estate, and no service down for any of it.
- **That adoption held.** Until a plane has adopted an estate, that it
  can is an assumption about every external name at once.
- **Anything here that was wrong**, which is likely.

The first run, for comparison. One installation, one instance, the
instance `down`:

- **1m21s** from the merge to the plane having applied the composition.
- **21m** for GKE to build the cluster and its pool, timed from the merge
  that granted `actAs` on the default compute service account — until
  that landed the create was refused rather than slow.
- **6m** to install the two charts by hand.
- **15m** for the four waves, nearly all of it wave 1 pulling twelve
  provider images, during which the API server was unreachable for a
  while as GKE resized the control plane.
- **under a minute** for the estate to adopt once the installation's
  manifest applied: 121 managed resources, each observing its own and
  taking it over.
- **2h34m** end to end, of which the mechanical path is about an hour.
  The rest was four merges fixing what the run found.

## Failures

**The composite reports `Synced` and no second cluster appears.** The
change reused the live cluster's composition slot, so Crossplane matched
the existing object to it and kept its name. Give the slot a new name
and merge again.

**Nothing starts building, and the cluster reads `Synced: False` with
no activity in the console.** Read `LastAsyncOperation` on the managed
resource: a refused create reports there while the composite above goes
on reading `Synced`. *The user does not have access to service account
`<project-number>-compute@…`* is the one to expect from a plane that
predates the binding for it — the composite grants the platform
identity `actAs` on the default compute service account, because a
cluster's initial node pool runs as it, and a plane whose project the
seed created is not owner there. Merge that and the create is retried.

**A command aimed at the successor changed nothing there, and something
moved on the plane in charge.** `$NEXT` is unset in that shell, so
`--context ""` meant the current context. Nothing reports it: the
command succeeds against the wrong cluster. Check the node names before
every write, and read both clusters afterwards — a pod deleted on the
plane in charge recovers by itself, and a `spec` patched there is drift
its Argo reverts, so the usual damage is only lost time.

**`the server doesn't have a resource type "applications"`, or the same
for `"composite"`.** Ahead of the waves rather than broken. The first
kind arrives with Argo; `composite` is a category carried by the CRDs
the XRDs generate, so it names nothing until the third wave establishes
them. What reports before either is `helm list` and the pods in
`crossplane-system`.

**The successor is built and stays empty.** Expected until step 5. The
`Release`s are `Observe` on whichever plane reconciles them, so nothing
installs onto a cluster it is not on.

**The successor's composite reports the Crossplane or Argo `Release` not
found.** The helm release is named something other than the object's
`crossplane.io/external-name`, or is in another namespace. An `Observe`
release it cannot find is one it will never install.

**`no matches for kind "Application" in version "argoproj.io/v1alpha1"`,
and `ensure CRDs are installed first`, from the Argo install itself.**
Nothing is applied — no CRDs, no namespace — so it is recoverable by
doing it in two passes: the kind is one this release installs, and helm
builds every object in a manifest before applying any of it. Only the
first install of Argo on a cluster has this problem, which is why
[argocd-upgrades](argocd-upgrades.md) says an upgrade must always carry
the file.

**The successor's API server stops answering during wave 1, and GKE
reports the cluster `RECONCILING`.** `TLS handshake timeout`, then
`context deadline exceeded`, from every read. Expected, and it clears
itself: the provider packages register thousands of CRDs in a couple of
minutes, the API object count jumps, and GKE resizes the control plane
to match. Nothing in the estate is at risk while it happens, because the
plane in charge is a different cluster — which is the case for reading
`gcloud container clusters describe` rather than concluding anything from
a `kubectl` that cannot connect.

**Argo comes up on the successor with no Applications.** The values file
was omitted or rebuilt by hand, so `extraObjects` went with it and the
`management-plane` Application was never created.

**Provider pods on the successor cannot authenticate.** Expected while
the second wave is still syncing: the runtime configuration pinning the
Kubernetes service account name has to land before the pods that name
it. Persisting past that, the name is wrong and Workload Identity is
bound to something nothing runs as.

**A resource is `Ready` and never `Synced`, and the message names a
parameter the request is missing.** `charset and collation cannot both
be empty for update request`, on a Cloud SQL database that plainly
exists. The creating plane had those fields in its spec through
late-initialisation, so it never sent such a request; the successor
adopted a resource whose observed state carries neither, computed a
diff, and is refused for ever. The fix is in the composition — state the
field, matching what the cloud already assigned — and the way to find
the rest is `just crossplane-drift`.

**`Invalid request since instance is not running`, on a database of an
instance that is `down`.** `state: down` sets Cloud SQL
`activationPolicy: NEVER` and a stopped server refuses every write, so
this says an update is being attempted — not that the instance has to
come up. Find what the update is for and remove the reason: here it was
the empty `charset` of the entry above, and stating it left no diff to
apply, after which the resource went `Synced` against a stopped server
and late-initialised the rest. One refusal replacing another is progress,
so read the message rather than the condition.

**A project reports `Error 409: Requested entity already exists`, and
everything in that project waits behind it.** The manifest declaring it
carries no `adopt`, so the successor composed a `Project` with no
external name and tried to create one that already exists. Nothing is
damaged — GCP refused — but the project never goes Ready and every
`ProjectService` and binding referencing it stalls behind it. Add
`adopt` and merge; see the prerequisite above.

**An instance's resource is created rather than adopted.** Its external
name is not what the composition derives, so a fresh managed resource
observed nothing and built a second one. Stop before it finishes: the
first is still there, and two of them is worse than either. A project is
the one case where this is harmless, because a project id is globally
unique and the create is refused rather than duplicated.

**The plane you scaled down comes back.** Something is reconciling those
`Deployment`s that should not be — the composite's `Release` is meant to
be `Observe` on the plane it installed. Read `crossplane-slots` on the
successor before scaling anything again.

## Rules

**MUST:**

- Give the successor a cluster name and a composition slot of their own.
  A reused slot keeps the live object and ignores the new name, and the
  composite reports `Synced` while nothing happens.
- Record the slot list and the external names before, and diff them
  after. Adoption is the whole procedure, and nothing else reports
  whether it happened.
- Merge an `adopt` for every project in the estate before swapping. One
  whose manifest lacks it cannot be adopted by any plane that did not
  create it.
- Compare the two planes with `just crossplane-drift` before swapping. A
  field only the creating plane sets is one late-initialisation filled
  there, which an adopting plane has no way to learn.
- Install Crossplane and Argo onto the successor with the release name,
  namespace, chart version and values the composed `Release`s carry, and
  never without `-f`: `extraObjects` holds the Application that pulls
  everything else.
- Install Argo in two passes on a cluster that has never had it, the
  first with `extraObjects` emptied and the second with the file
  unchanged. The chart's CRDs are templates, and helm builds every
  object before applying any, so an `Application` cannot be part of the
  release that installs its own kind.
- Install Crossplane before Argo, and read the release names from each
  object's `crossplane.io/external-name` rather than from its Kubernetes
  name.
- Prove the instances adopted, not only the plane, before swapping —
  counting a `down` instance's stopped servers as adopted, since nothing
  about them can reconcile until it is up.
- Scale the old plane's Crossplane core down before its provider pods —
  the core puts a provider back — and only after the successor is
  holding the estate.
- Keep the cluster it replaced until the successor has been trusted:
  while it stands, scaling it up and reverting the merge is the way
  back.

**MUST NOT:**

- Never rebuild a plane this way where the change keeps the cluster's
  name. Nothing can stand a second cluster up under one name, so a
  ForceNew field on its own is a delete and an install.
- Never use `just plane-ctx` before the swap: it renames whatever it
  fetches to `MGMT_CTX`, which until then has to reach the plane in
  charge.
- Never run a step 5 to 7 command without checking `$NEXT` is set. An
  unset one is the current context, which is the plane in charge, and
  the command succeeds there rather than failing.
- Never delete the old plane's composite to stop it. Its access
  bindings carry `Delete`, including the one its Crossplane
  authenticates with.
- Never use this on an instance's cluster, which has a live plane above
  it and data under it — that is
  [instance-rebuild-cluster](instance-rebuild-cluster.md) — and never as
  a way to retire an installation, which takes the recovery project and
  the backups with it.

Commands: `just crossplane-slots`, `just crossplane-external-names`,
`just crossplane-drift`, `just crossplane-unready`, `just
argo-apps-status`, `just check-versions`, `just plane-ctx`.

## Discussion

A plane cannot delete and recompose the cluster it is running on, which
is why this is a swap rather than the rebuild an instance gets. Every
other cluster in an installation has something above it: an instance's
is composed by the plane, so deleting the managed resource is enough and
Crossplane recomposes it — that is
[instance-rebuild-cluster](instance-rebuild-cluster.md), easier because
the thing doing the work is not the thing being replaced. The plane's
cluster has nothing above it, so the work is done by the plane itself,
before it hands over.

**What makes it cheap is that everything else is project-scoped.** The
successor sits in the management project, on the management subnet,
under the same folder. Workload Identity's pool is the project's, the
IAM bindings name identities rather than clusters, and the secrets are
in Secret Manager — so the successor inherits every capability the plane
had by being in the same project, and none of it is composed twice.

**Why two releases are installed by hand.** The composed `Release`s are
`Observe` on the plane that reconciles them, which is the seam this uses
rather than a hole in it: a plane watches what installed it and never
acts on it, precisely so that a composition edit cannot have Crossplane
upgrading itself mid-reconcile. Something outside always installs those
two — a boot plane at install time, a person here — and
[argocd-upgrades](argocd-upgrades.md) already builds a values file from
the composed object for the same reason.

**Adoption is the recovery.** What makes this survivable is that nothing
on the plane is the source of anything: the folder id is in the
environment, the project ids are in the manifest as `adopt`, and every
other name the composition derives from the code. A successor observes
each of them and takes them over. The corollary is that a name which
can be neither derived nor re-observed cannot be adopted — which is why
`crossplane.io/external-name` is for a name Kubernetes cannot express
rather than one that is merely tidier. What a successor cannot take over
at all is what late-initialisation taught the plane that created a
resource: that lives in a spec rather than in the cloud, so a field the
composition leaves to late-init exists on one plane and not the other,
and only the composition closes the gap. It is also why the resources whose
names come from another's status are composed by a guarded template
rather than a patch.

**The slot is what makes this a one-off rather than a routine.** A
successor needs a new slot, and slots are written in the composition, so
each rebuild is an edit to this repository rather than a value in a
manifest. A plane that could declare a generation — the slot named from
it by a templated step, and the cluster with it — would make this a
merge and a swap with no composition edit at all, and would let a
ForceNew change take the path above instead of a delete and an install.
It needs one more thing than the generation: `XCluster` derives its
network and subnet names from its own label, so a successor sharing the
network it is replacing a cluster in needs the network's name to come
from a field of its own.

**What this does not rebuild.** The cluster, not the plane: the folder,
the management and recovery projects, the identities, the network, the
secrets and every instance survive it, and the installation keeps its
code, its name and its domain. Rebuilding the management *project* is a
different procedure and its id is consumed permanently. Retiring an
installation is a third, and nothing writes it down.

## References

- [plane-install](plane-install.md) — building a
  plane where none is running, which adopts what survived
- [instance-rebuild-cluster](instance-rebuild-cluster.md) — the same act
  under a live plane, with data under it
- [crossplane-design](crossplane-design.md) — a composed resource is
  identified by its composition name
- [crossplane-live](crossplane-live.md) — what a change to a live
  resource does, and what a rename is
- [argocd-upgrades](argocd-upgrades.md) — building a values file from
  the composed `Release`, and what omitting one costs
- [ADR-0024](../../adr/0024-instances-are-their-own-composites.md) — the
  plane and the instances on it, and what each composes
- [plane-cluster-naming](../../plan/plane-cluster-naming.md) — the
  rename this was first written for
