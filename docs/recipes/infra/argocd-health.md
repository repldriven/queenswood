# Argo CD health

<!-- tessl-plugin: deployment -->

## Status

**Verified**, 2026-09-16, on this installation's plane, running Argo CD
3.5.3: step 2 reported nothing missing against Argo's own lists, and
step 1 gave no exact keys. Step 1's three groups are what it reports
once the chart that drops the two overrides has synced; it gave five
before that.

## Problem

You need Argo CD to report the correct health status for your
installation.

## Solution

### Prerequisites

- A management plane running in the installation's folder.
- The capability each step names. Ours is a Google group; yours may differ.

```bash
# the installation code, e.g. qw01
export QW_CODE=qw01
```

### 1. Check Argo has the health checks

**As the installation's platform viewer.** Ours is
`grp-gcp-<code>-platform-viewer@`, populated rather than joined.

```bash
just argo-health-checks "$QW_CODE-mgmt"
```

`GROUPS` exactly these three, and nothing else:

```
platform.repldriven.com/*:
queenswood.repldriven.com/*:
argoproj.io/Application:
```

Argo's own scripts assess `*.crossplane.io/*` and `*.upbound.io/*` from
3.5.3 on, so neither appears here.

`EXACT KEYS` `none`.

### 2. Check every status-less Crossplane kind is handled

```bash
just argo-health-kinds
```

`none` under both `missing from` headings.

## Failures

**A group in `compositeGroups` with no line under `GROUPS` in step 1.**
The chart renders one entry per group in that list, so a missing line is an XRD
loaded onto the plane whose group was never added beside it. Every
composite of that kind reads Healthy, including one that failed to
compose.

**A key under `EXACT KEYS` in step 1.**
Server-side apply removes a field when the manager that owned it stops
declaring it, so one that survives is owned by something else. An exact
key beats a wildcard, so a stale one goes on assessing its kind by
whatever an earlier generation wrote.

**A kind named under `missing from` in step 2.** It carries no status
and no list names it, so it reports Healthy today from the nil branch
and goes `Progressing` for ever once the precedence is corrected,
taking its Application with it. Nothing here overrides Argo's scripts,
so the fix is upstream, in
`resource_customizations/_.crossplane.io/_/health.lua` or its upbound
twin; add it to `LISTED_CROSSPLANE` or `LISTED_UPBOUND` in
`justfiles/argo.just` when the release carrying it is the one the plane
runs.

**An Application `Healthy` over a composite that never composed.** Argo
assesses a resource by its API group, and a group it has no check for
is reported Healthy unconditionally rather than as unassessed. An
Application applying a composite that fails to compose is
indistinguishable from one that worked.

**A managed resource `Healthy` while it is still provisioning.** A
plane on an Argo CD older than 3.5.3 does this to every managed
resource, and the composite above it looks finished the moment it was
applied. The plane's version is what tells them apart, so read it before
reading anything into a green estate.

**A parent whose waves gate nothing.** The waves are doing what waves
do. `Application` has no health check of its own: Argo removed the
assessment for the kind in 1.8, there is no Lua under
`resource_customizations/argoproj.io/`, and the Go switch on
`argoproj.io` handles `Workflow` alone. gitops-engine then treats a
missing health as an immediate success, the way it does a `Secret`. So
every wave succeeds the moment it is applied and the next begins, and
the parent reads Healthy throughout however its children are doing.

**A health status that never moves, on a resource Crossplane created.**
Managed resources are tree descendants rather than an Application's
own, so their health never reaches it. The composite is the thing whose
status matters.

## Rules

**MUST:**

- Re-read the checks with `just argo-health-checks` after an Argo
  upgrade, and re-run `just argo-health-kinds` after a Crossplane
  one. An upgrade is what moves either answer.
- Add an XRD's API group to `compositeGroups` in
  `infra/helm/management-plane/values.yaml`, in the same change as the
  XRD, where the group is not there already. One entry covers every
  kind in a group.
- Give an environment's Applications a parent of their own before
  registering a check for `argoproj.io/Application`.
- Read `Synced` before `Ready`, in a pass of its own, when writing a
  check.

**MUST NOT:**

- Read `Healthy` on a group with no check as evidence of anything.
- Patch one status-less kind rather than the script that checks it.
  There are several, in both groups.
- Expect a managed resource's health to reach the Application above it.

**SHOULD:**

- Raise a status-less kind upstream rather than overriding Argo's
  scripts in the chart. An override is defensible only for a kind
  upstream does not list, and it has to be deleted again when it does.

## Discussion

We write a health check for every group whose health status we act on,
because Argo's answer for a group it does not know is not "unknown" but
"Healthy" — and that answer propagates, since a wave advances on health
and gitops-engine counts a missing status as success.

**Why a missing check is silence rather than an error.** Argo assesses
by API group and has no notion of a group it ought to know about. A
resource with no check is not reported as unassessed: it gets the same
word a working one gets, in the same column. Nothing distinguishes the
two, which is why the list of groups belongs in the chart beside the
XRDs rather than in somebody's memory.

**The status-less trap.** The script Argo ships for Crossplane has to
answer two questions with one piece of Lua: a resource that will have a
status and does not have one yet is Progressing, and a resource that
will never have one is Healthy. It separates them with a list of kinds,
and its condition reads as `A or (B and C)` where `(A or B) and C` was
meant — so the nil check answers first, and every status-less kind
reports Healthy whether or not the list names it. That makes the list
look complete while it is not, and hides the omission until the bug is
fixed: correcting the precedence without completing the list turns a
silent success into a permanent `Progressing`. Which is why
`argoproj/argo-cd#29382` fixes both halves at once.

**Where the lists live now.** 3.5.3 carries that fix, so the chart
overrides neither group and step 2 diffs against Argo's own lists:
`LISTED_CROSSPLANE` and `LISTED_UPBOUND` in `justfiles/argo.just` are
transcribed from
`resource_customizations/_.crossplane.io/_/health.lua` and its upbound
twin, where a `_` path segment is how that tree spells the wildcard a
directory name cannot carry. A ConfigMap key cannot express that
wildcard at all, which is why the entries that remain sit in one
`resource.customizations` block rather than in dotted keys.

**What step 2 does not read.** Managed resources. upjet gives every one
of them a status, so none can be status-less, and their CRDs are large
enough that fetching them all costs hundreds of megabytes — so the
`upbound.io` half fetches the config-shaped kinds and leaves the rest.
That is a heuristic rather than a proof: a provider shipping a
status-less kind whose name is neither a provider config nor a store
config would not be read. The `crossplane.io` half has no such filter
and reads every group.

**How a composite is checked.** Two passes over `status.conditions`,
`Synced` before `Ready`. One loop answers whichever condition the array
happened to hold first, and a composite that went out of sync after it
was once ready then reports the stale success.

**Why the `Application` case costs something.** Argo removed the health
assessment for the kind in 1.8 and documents restoring it for exactly
this case — an app-of-apps ordering its children by sync wave — so the
entry in the chart is a documented restoration rather than something
prised out of the source.

Registering it is not turning waves on — they were always ordering, on
a signal that was always success. Giving the kind a health status makes
the signal real, and a real signal can say no: a child that hangs now
hangs its parent, and a hung sync replays a stale revision. So the fix
wants the Applications it gates to be ones that can fail without taking
a plane's own manifests with them.

## References

- [argocd-apps](argocd-apps.md) — Applications, waves, and reading a
  sync that is not applying.
- [crossplane-debug](crossplane-debug.md) — the composites
  whose health this is about.
