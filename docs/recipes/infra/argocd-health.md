# Argo CD health

<!-- tessl-plugin: deployment -->

## Status

**Verified**, 2026-08-27, on this installation's plane: the entries
registered and replaced the four that preceded them, and
`gcp-plane-statusless-kinds` reported nothing missing from either list.
`ARGOCD_K8S_CLIENT_QPS` is merged and has not yet reached a plane.

## Problem

You want Argo's health verdict to be true for the kinds a plane serves.

## Solution

### Prerequisites

- A management plane running in the installation's folder.
- Each environment's Applications under a parent of their own, before
  step 1 registers a verdict for the kind.
- Step 1 — write access to this repository.
- Steps 2 to 5 — `platformViewer`, e.g.
  `grp-gcp-<code>-platform-viewer@`.

```bash
# the installation code, e.g. qw01
export CODE=qw01
```

### 1. Register the checks

`infra/helm/management-plane/templates/argocd-cm.yaml`, in one
`resource.customizations` block:

- `<group>/*` per composite group, from `compositeGroups` in the
  chart's values.
- `argoproj.io/Application`.
- `*.crossplane.io/*` and `*.upbound.io/*`, transcribed from
  `argoproj/argo-cd#29382`.

Merge before going further.

### 2. Wait for the plane to apply it

```bash
kubectl --context "$CODE-mgmt" -n argocd get application management-plane
```

`SYNC STATUS` is `Synced`, `HEALTH STATUS` is `Healthy`.

### 3. Check the entries landed

```bash
kubectl --context "$CODE-mgmt" -n argocd get configmap argocd-cm \
  -o json | jq -r '.data["resource.customizations"]' | grep -E '^\S.*:$'
```

One line per group in `compositeGroups`, plus `argoproj.io/Application`
and the two wildcards.

```bash
kubectl --context "$CODE-mgmt" -n argocd get configmap argocd-cm \
  -o json | jq -r '.data | keys[]
    | select(startswith("resource.customizations.health."))'
```

Nothing.

### 4. Check the lists cover the plane

```bash
just gcp-plane-statusless-kinds
```

`none` under both `missing from` headings. A kind named there goes into
`has_no_conditions` in `argocd-cm.yaml` and in
`scripts/crossplane-statusless-kinds.py`, and upstream.

### 5. Check the exclusions took effect

```bash
kubectl --context "$CODE-mgmt" -n argocd get application installation \
  -o json | jq -r '[.status.resources[].kind] | unique | .[]'
```

No `ProviderConfigUsage` or `ClusterProviderConfigUsage`. Still there a
few minutes after the sync: restart the application controller.

### 6. Raise the client QPS

`ARGOCD_K8S_CLIENT_QPS: 300` on the application controller, in
`management-argo`'s values in
`infra/platform/crossplane-xrds/xmanagementplane-composition.yml`.
Apply it by the configuration-change path in
[argocd-upgrades](argocd-upgrades.md).

## Failures

**An Application `Healthy` over a composite that never composed.** Argo
grades a resource by its API group, and a group it has no check for is
graded Healthy unconditionally rather than reported as ungraded. An
Application applying a composite that fails to compose is
indistinguishable from one that worked.

**A managed resource `Healthy` while it is still provisioning.** The
precedence bug: no status yet, so the nil branch answers Healthy before
the kind list is reached. Every managed resource on a plane running
Argo's compiled-in scripts does this, and the composite above it looks
finished the moment it was applied.

**A kind `Progressing` for good after an Argo upgrade.** The other half
of the same bug. A kind that carries no status *and* is missing from
the list is graded Healthy by accident today, so the list looks
complete while it is not — and a release correcting the precedence
grades that kind Progressing for ever, taking its Application with it.
Step 4 is what finds them before that happens.

**A `resource.customizations.health.<group>_<kind>` key still there
after step 1.** Server-side apply removes a field when the manager that
owned it stops declaring it, so one that survives is owned by something
else. An exact key beats a wildcard, so a stale one goes on grading its
kind by whatever an earlier generation wrote.

**A parent whose waves gate nothing.** The waves are doing what waves
do. `Application` is an ungraded kind — no Lua under
`resource_customizations/argoproj.io/`, and the Go switch on
`argoproj.io` handles `Workflow` alone — and gitops-engine treats a nil
health as an immediate success, the way it does a `Secret`. So every
wave succeeds the moment it is applied and the next begins, and the
parent reads Healthy throughout however its children are doing.

**A grade that never moves, on a resource Crossplane created.** Managed
resources are tree descendants rather than an Application's own, so
their grade never reaches it. The composite is the thing whose verdict
matters.

## Rules

**MUST:**

- Register a health check for every XR group a plane serves, and add a
  group to `compositeGroups` with the XRD that introduces it.
- Carry corrected copies of `*.crossplane.io/*` and `*.upbound.io/*`
  while the plane runs an Argo release without the fix, and delete them
  when it runs one with it.
- Put a wildcard group's check in `resource.customizations`. A
  ConfigMap key cannot contain a `*`.
- Re-run step 4 after a Crossplane upgrade, and step 3 after an Argo
  one.
- Read `Synced` before `Ready`, in a pass of its own.
- Give an environment's Applications a parent of their own before
  registering a check for `argoproj.io/Application`.

**MUST NOT:**

- Read `Healthy` on an ungraded group as evidence of anything.
- Patch one status-less kind rather than the script that grades it.
  There are several, in both groups.
- Expect a managed resource's grade to reach the Application above it.
- Set `application.resourceTrackingMethod` without reading what the
  plane already uses. Changing it re-tracks every resource.

## Discussion

We write a health check for every group we act on the verdict of,
because Argo's answer for a group it does not know is not "unknown" but
"Healthy" — and that answer propagates, since a wave advances on health
and gitops-engine counts a nil verdict as success.

**Why a missing check is silence rather than an error.** Argo grades by
API group and has no notion of a group it ought to know about. An
ungraded resource is not reported as ungraded: it gets the same word a
working one gets, in the same column. Nothing distinguishes the two,
which is why the list of groups belongs in the chart beside the XRDs
rather than in somebody's memory.

**The status-less trap.** The compiled-in script has to answer two
questions with one piece of Lua: a resource that will have a status and
does not have one yet is Progressing, and a resource that will never
have one is Healthy. It separates them with a list of kinds. The
precedence bug means the nil check answers first, so every status-less
kind grades Healthy whether or not it is on that list — which makes the
list look complete while it is not, and hides the omission until the
bug is fixed. Correcting the precedence without completing the list
turns a silent success into a permanent `Progressing`. Carrying our own
corrected copies is what makes this installation indifferent to which
of the two readings the installed release has, and patching a single
kind is not: the affected kinds run to four in the crossplane list
alone, and the upbound script has its own.

**Why one block and not four keys.** A ConfigMap key cannot contain a
`*`, and two of the four entries are wildcard groups, so the dotted
`resource.customizations.health.<group>_<kind>` form cannot carry them.
The nested `resource.customizations` block can, and takes the exact
groups too, so all four live in one place.

**Which upstream file each copy is.** They are
`resource_customizations/_.crossplane.io/_/health.lua` and its upbound
twin. A `_` path segment is how that tree spells the wildcard a
directory name cannot carry, so `_.crossplane.io/_` is the
`*.crossplane.io/*` entry here — the same wildcard in a third encoding,
since a ConfigMap key cannot express it at all. The copies are
temporary: when the fix reaches a release this plane runs, delete both
entries and point `HAS_NO_CONDITIONS` in
`scripts/crossplane-statusless-kinds.py` at the upstream lists.

**How a composite is graded.** Two passes over `status.conditions`,
`Synced` before `Ready`. One loop answers whichever condition the array
happened to hold first, and a composite that went out of sync after it
was once ready then reports the stale success.

**Why the `Application` case costs something.** Registering it is not
turning waves on — they were always ordering, on a signal that was
always success. Giving the kind a verdict makes the signal real, and a
real signal can say no: a child that hangs now hangs its parent, and a
hung sync replays a stale revision. So the fix wants the Applications
it gates to be ones that can fail without taking a plane's own
manifests with them.

## References

- [argocd](argocd.md) — Applications, waves, and reading a sync that is
  not applying.
- [argocd-upgrades](argocd-upgrades.md) — how a values change reaches a
  running plane.
- [crossplane](crossplane.md) — the composites whose verdict this is
  about.
