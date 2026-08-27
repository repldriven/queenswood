# Argo CD

<!-- tessl-plugin: deployment -->

## Problem

You want what you merge to reach the cluster.

## Solution

Lay Applications out so that a fix can always land, and read a sync
that is not applying for whether it is retrying, waiting, or finished.

### Prerequisites

- A management plane running in the installation's folder.
- `platformViewer`, e.g. `grp-gcp-<code>-platform-viewer@`.

```bash
# the installation code, e.g. qw01
export CODE=qw01
```

### Laying out Applications

- A parent holds Applications, and resources of kinds that always
  exist. Anything of a kind one of its children installs belongs in a
  child of its own.
- `ServerSideApply=true` on a chart with large CRDs.
- `argocd.argoproj.io/sync-options: SkipDryRunOnMissingResource=true`
  on a resource whose CRD an earlier wave installs.
- Retry budgets that outlast an operator install, image pull included.
- `prune: false` where pruning would delete something a missing file
  should not delete.

### Applying a change

Merge it. Argo reads the revision an Application names, never a working
tree, so a change is not testable until it is merged — unless the
revision is a field the manifest can set.

### Checking it landed

```bash
kubectl --context "$CODE-mgmt" -n argocd get applications
```

Every `SYNC STATUS` is `Synced`. `HEALTH STATUS` reads `Progressing` on
an instance's Applications while its workloads converge, and that is
not a finding.

Then the revision one of them applied, against the commit you merged:

```bash
kubectl --context "$CODE-mgmt" -n argocd get application <app> \
  -o jsonpath='{.status.operationState.syncResult.revision}{"\n"}'
```

## Failures

**A child that never applies, holding the kind the parent needed.** The
parent fails building a sync task for a kind the API server does not
serve, and stops before applying the child that would install it. The
fix cannot reach the cluster on its own: breaking the cycle takes a
hand-applied patch, and hand-applying takes cluster write access an
operator should not hold. Move the resource into a child of its own.

**A workload crash-looping because its own kinds do not exist.** Not a
failed CRD — a CRD over 256KB, whose whole body client-side apply
writes into `last-applied-configuration`, which the API server rejects
as `Too long`. The Application reports the chart applied. Set
`ServerSideApply=true`.

**A merged fix that never arrives, on an Application that is
retrying.** An operation pins the revision it started with and each
retry replays that revision's manifests, so a fix merged mid-loop is
never applied however many attempts remain — an hour of it, with a
budget backing off to ten minutes. The status reads as though the fix
landed: `status.sync.revisions` shows the revision Argo *would* sync
and updates the moment the merge is polled, where
`.operation.sync.revisions` shows the one being retried. Compare the
two, then merge the fix and remove `.operation`, which takes a JSON
patch because a merge patch cannot remove a field:

```
kubectl -n argocd patch app <app> --type json \
  -p '[{"op":"remove","path":"/operation"}]'
```

**An operation `Running` with `retryCount` unset.** It is not failing
at all. Only `Healthy` and `Degraded` end a sync task — `Progressing`,
`Suspended`, `Missing` and `Unknown` all leave it running — so a wave
holding a resource that can never go Healthy waits for good, with no
retry, no backoff and no timeout. Removing `.operation` does nothing to
one already in flight: the field disappears and
`operationState.phase` stays `Running` at its original `startedAt`.
Terminating is what reaches it, and is what `argocd app terminate-op`
does:

```
kubectl -n argocd patch app <app> --type merge \
  -p '{"status":{"operationState":{"phase":"Terminating"}}}'
```

**An Application that stays failed after the drift was corrected.**
`selfHeal` corrects drift and does not retry a failed sync. One that
has exhausted its retry budget stops until the revision changes or
somebody syncs it, so a fix merged after the budget ran out needs a
nudge — and a nudge is cluster write access.

**Every resource `OutOfSync`, and the message naming one of them.** A
sync is one operation over every resource, so a single object the API
server rejects leaves every well-formed one beside it unapplied. The
cluster looks mostly right while the Application keeps failing.

**`field not declared in schema`, on a template that renders.**
Server-side apply refuses an undeclared field rather than dropping it,
and only the API server holds the schema. `helm template` renders it
happily. A template that renders is not a template that applies.

**A resource name containing `%!s(bool=false)`.** A `valuesObject` is
YAML, so a bare `n`, `y`, `no` or `yes` in one is a boolean rather than
a short string, and a chart building a name with `printf "%s"` renders
that. What fails is the API server refusing a name containing a `%`,
which reads as a templating fault rather than as a missing pair of
quotes two files away. Quote every short value, and have the chart fail
on a non-string rather than coerce one — `false` is a name that applies
cleanly and is wrong.

**A resource `OutOfSync` in an Application that no longer manages it.**
Under annotation tracking, Argo records the owning Application on the
resource itself as `argocd.argoproj.io/tracking-id`, and nothing else
removes it. A
resource that moves between Applications arrives at its new owner still
carrying the old one's name, and the former owner goes on listing it —
holding it `OutOfSync` for good, and taking the worst of a resource it
no longer manages up through every parent above. Where that owner
prunes, it deletes it instead. Strip the annotation by hand, as the
last act of the handover:

```
kubectl -n argocd annotate <kind> <name> argocd.argoproj.io/tracking-id-
```

That annotation is the marker of annotation tracking. Which method a
plane uses:

```bash
kubectl --context "$CODE-mgmt" -n argocd get configmap argocd-cm \
  -o json | jq -r '.data["application.resourceTrackingMethod"] // "unset"'
```

Unset means the installed Argo's default decides. Changing it re-tracks
every resource, so read it before setting it.

**A Secret whose contents change on every sync.** A chart that mints a
value and preserves it with `lookup` mints a fresh one each render —
see [external-secrets](external-secrets.md).

## Rules

**MUST:**

- Keep concrete resources out of a parent Application. Put anything
  whose kind a child installs into a child of its own.
- Set `ServerSideApply=true` for charts with large CRDs.
- Set retry budgets that outlast an operator install.
- Read `.operation.sync.revisions` rather than `status.sync.revisions`
  when a sync is failing. The first is what is being retried; the
  second is only what would be synced next.
- Read `retryCount` before calling a stuck sync a retry loop. Unset
  means the operation is not failing at all.
- Merge the fix before cancelling anything, or the fresh sync hangs the
  same way.
- Remove `.operation` to cancel a queued sync, with a JSON patch, and
  terminate one already in flight by setting
  `status.operationState.phase` to `Terminating`.
- Strip `argocd.argoproj.io/tracking-id` from a resource handed from
  one Application to another.
- Set `prune: false` where pruning would delete something a missing
  file should not delete.
- Merge a change before expecting Argo to apply it. It reads the
  revision an Application names, never a working tree.

**MUST NOT:**

- Expect sync waves to resolve a missing kind.
- Expect `SkipDryRunOnMissingResource` to make an apply succeed. It
  skips the dry run and nothing else.
- Expect a merged fix to reach an Application whose retries have
  already been exhausted.
- Rely on `lookup` to preserve anything.

## Discussion

We keep a parent Application free of concrete resources, give every
sync a budget that outlasts an install, and read a failing one from
`.operation` rather than from `status`, because Argo reports what it
would do next far more legibly than what it is doing now.

**Why a parent cannot hold a kind its child installs.** A sync is
planned before it is applied: Argo builds a task per resource, and a
kind the API server does not serve has no task to build. The plan
fails, so nothing in that Application applies — including the child
whose chart registers the kind. Nothing about that is recoverable
through git, which is the part worth internalising: the repository can
be correct and the cluster still unable to reach it.

**What an operation pins.** A sync is an operation with a revision
attached, and retries belong to the operation rather than to the
Application. So the revision is fixed at the moment the first attempt
started, and every subsequent attempt applies those manifests — a merge
during the loop changes what Argo *would* sync without changing what it
is syncing. That is why the two revision fields disagree, and why the
one everybody reads first is the one that does not matter.

**Why cancelling takes two different acts.** `.operation` is the field
that asks for a sync — setting it starts one without the CLI, and
removing it un-queues something that has not started, or is between
retries. An operation parked in a health wait has started and is not
waiting to be re-queued: it is waiting for a resource, so the
controller goes on holding it after the field is gone. Terminating sets
a phase the controller reads. A plain patch reaches `status` because
the Application CRD declares no status subresource; check that before
relying on it.

**The plane's own tree.** `management-plane` is the parent, planted by
the boot chart and holding nothing but Applications: providers in wave
1, the plane's own configuration in 2, the XRDs in 3, and
`installation` — the composite describing this installation, read from
the private repository — in 4, beside `external-secrets`. An instance's
Applications appear next to them rather than nested under them, named
for the instance.

**Waves and the kinds they cannot conjure.** A wave orders applies. It
does not make a kind exist, and an operator install is asynchronous:
Argo reports a chart applied long before its CRDs are registered, so
the next wave can begin against an API server that does not yet serve
what it needs. `SkipDryRunOnMissingResource` skips validation, not the
apply. The only thing that reliably absorbs the gap is a retry budget
long enough to cover an image pull.

## References

- [argocd-health](argocd-health.md) — what `Healthy` means, and what a
  parent's waves do not gate without it.
- [argocd-github](argocd-github.md) — reading a private repository.
- [external-secrets](external-secrets.md) — where a value belongs when
  a chart must not hold it.
- [crossplane](crossplane.md) — what Argo is usually delivering here.
