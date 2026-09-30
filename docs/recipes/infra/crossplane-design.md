# Designing a Crossplane kind

<!-- tessl-plugin: deployment -->

## Status

**Verified**, 2026-08-27, in what it claims rather than in being
followed: the three commands run, and every statement about this
installation checked against the compositions — the split of `Delete`,
the two observed Releases, and `function-auto-ready` ending all seven.
No kind has yet been designed by working from these steps.

## Problem

You need to use Crossplane to manage a set of cloud resources.

## Solution

Nine decisions, in the order you answer them.

### 1. How much one kind covers

One kind for the application, and it is the unit you deploy. An
instance is created, taken down and destroyed as one thing, so anything
left outside its kind fractures that act, for example:

- **`XQueenswoodInstance`** — an instance: its project, identities,
  buckets and secrets, and the four kinds below.
- **`XManagementPlane`** — a plane, not yet decomposed.

Inside it, decompose into kinds that group related concepts into a
whole. Each is several managed resources that only make sense together
and that are created and destroyed as one, and leaving them loose puts
detail in the application's Composition that does not differentiate
it, for example:

- **`XNetwork`** — a network and the subnetworks carved out of it.
- **`XCluster`** — a cluster, its node pool, and the identity the nodes
  run as.
- **`XPublicEndpoint`** — an address, a certificate, the DNS
  authorization that proves the domain, and the records that answer it.
- **`XPostgres`** — a server and a user, the private connection they
  are reached over, the identities and grants that reach them, and a
  database for each name the caller asks for.

What each composes today, and how many:

```bash
just crossplane-kinds
```

### 2. What the caller must supply

The invariants are the reason the kind exists: fix them in each
composed resource's `base` — the literal manifest, before any patch.
What is left for the caller is whatever does not change that guarantee:
how big, how many, how long something is kept. Constants belong in
`base` too rather than in a patch, because a `Format` transform with no
verb for its input appends `%!(EXTRA string=…)` to the value.

Every patch reading a field the caller must supply carries
`policy: {fromFieldPath: Required}`. Without it a missing source is
skipped and the field is quietly left unset.

Two sources into one field is one `CombineFromComposite`; two patches
to one field is the second overwriting the first.

### 3. What the caller may omit

A patch with no `policy` block is optional, which is the default. A
missing source is skipped, and the resource composes without that
field — the skip is the point rather than a hazard.

A Required patch behaves differently: a missing source drops its whole
composed resource rather than the one field, so one field can switch a
group of resources on and off. Such a field cannot then carry a
default, which would make it always present.

Where the _number_ of composed resources varies with the caller,
`patch-and-transform` cannot express it: it composes a fixed set and
has no way to say "no resource at all". That needs
`function-go-templating`, ranging over the field.

### 4. What the XRD supplies instead

`default:` in the XRD's schema, for a field the caller may not want to
decide right now — a region, a disk type, a release channel. The choice
stays theirs; the default only means they need not make it today.

Mark every patch reading a defaulted field `Required` as well. The
default is in the XRD and the patch is in the Composition, and there is
a moment after both are applied when the default is not yet in force —
`Required` makes the resource wait for it rather than compose without
the field.

That is for a field the kind has always had. A field added to a kind
with live XRs is the opposite case: a default is applied when an object
is written and never on the way out, so every XR stored before the
field existed carries nothing there until something writes it. Put the
value such an XR means in `base` and let an unrequired patch override
it — absent then means an XR older than the field, which is a meaning
rather than a mistake.

### 5. What each part is called

The kind first. Name the thing itself, at the most specific level that
stays true, borrowing the vocabulary of whatever domain it belongs to:
`XPostgres` is the engine, where `XCloudSQL` would be the category.

Then the slots. The `- name:` in the resources list is the identity, not
`metadata.name`. Crossplane records it as
`crossplane.io/composition-resource-name` and matches on it, so
changing a `metadata.name` patch renames nothing, and changing the
`- name:` is what makes a slot a new resource.

Read the slot names already in use before choosing one:

```bash
just crossplane-slots
```

### 6. What cannot change after create

Some of what a resource holds is its identity, and changing one of
those fields is a delete and a create rather than an update. Terraform
marks them ForceNew and performs the replacement itself; upjet refuses,
because destroying a managed resource is governed by its
`managementPolicies` rather than by a field edit. So the change is
declined and the resource stays as it was.

Nothing in the CRD says which fields these are. A `Certificate`'s
`managed.domains` describes itself as "the domains for which a managed
SSL certificate will be generated", and gives no hint that a second
domain is refused rather than added — so the name it was meant to cover
goes on resolving to a certificate that does not. The flag lives in the
Terraform provider's source, and neither `kubectl explain` nor
`terraform providers schema` carries it.

Which is why this is a decision rather than a lookup:

- **Fix identity in `base`.** A field the caller may set, that cannot
  then change, is a promise the kind cannot keep — they edit the
  manifest, nothing happens, and the composite goes on reading
  `Synced`.
- **Where a caller must vary one, compose a second resource.** Two
  `Certificate`s sharing one `DNSAuthorization`, rather than one
  certificate whose domains are a list somebody appends to.
- **Write down what you find**, in
  [crossplane-providers](crossplane-providers.md), because nothing
  rediscovers it: identity covers more than a name — a node pool's
  `serviceAccount`, an IAM binding's `member`, a list-shaped field
  that reads as extensible.

### 7. What may be deleted

> [!WARNING]
> Withholding `Delete` is the prudent answer. Crossplane goes on creating
> and updating as before and never issues the delete, so removing the
> managed resource leaves the cloud resource standing:
>
> ```yaml
> managementPolicies:
>   - Observe
>   - Create
>   - Update
>   - LateInitialize
> ```

Withhold `Delete` where a rebuild would not return what was there:

- **Data, or a secret.** A database, a bucket, a Secret Manager entry.
- **Folders and projects.** They contain everything else, so deleting
  one takes what is inside it — and a new project carries a new random
  suffix, which every name and binding was written against.
- **Service accounts.** A new one is a different principal: the
  bindings that granted the old one its rights do not follow it, and a
  Workload Identity annotation names an address that no longer exists.
- **An agreement held outside GCP.** A public zone's nameservers sit at
  the registrar, and a recreated zone draws new ones.

What Crossplane may do to each composed resource today:

```bash
just crossplane-policies
```

### 8. What may be observed

`Observe` alone is the other end of the same field: Crossplane reads
the resource and never acts on it — no create, no update, no delete.
The composed object exists so the XR can see the resource and patch
from its status, while something else owns its lifecycle.

There should be almost none, and the `OBSERVED` column of
`just crossplane-policies` says how many. Two today, both Helm releases
a boot plane installed: Crossplane's own and Argo's. A plane that
upgraded either would be upgrading the thing performing the upgrade, so
they are observed and changed by hand instead — see
[argocd-upgrades](argocd-upgrades.md) and
[crossplane-upgrades](crossplane-upgrades.md).

### 9. When it is ready

A managed resource reports `Ready` from its own conditions, which may
not reflect the cloud: a GCP folder deleted through the console sits in
`DELETE_REQUESTED` for thirty days and reads as existing throughout.
Add a `readinessCheck` against the field carrying the real state.

End every Composition with `function-auto-ready`, the function that
derives readiness from each composed resource's own conditions.
`function-patch-and-transform` sets readiness for what it composes and
`function-go-templating` sets it for nothing, so a pipeline mixing the
two needs it — and so does one with no template in it yet. The
`AUTO-READY` column of `just crossplane-kinds` should read `yes`
throughout.

## Failures

**A patch that never fires, and no error anywhere.** A patch whose
source field is absent is skipped silently. That is what makes an
optional field work, and what turns an unapplied XRD into an empty
value nothing complains about.

**A manifest edit that changed nothing, on a resource still reading
`Synced`.** A field that is the resource's identity: upjet declines the
replacement rather than performing it, and says so in the managed
resource's `LastAsyncOperation` while the composite above it goes on
reporting `Synced`, because a composite's `Synced` is whether the
composition rendered rather than whether every resource took its
change. Changing it at all is
[crossplane-live](crossplane-live.md)'s.

**Every live XR of a kind stops rendering the moment a field is added
to it.** `spec.<field> is absent`, from a `Required` patch or from a
`fail` in a templated step, on composites that were reconciling a
minute earlier — and on a plane mid-sync it arrives with the merge that
added the field. The XRD's default is not retrospective, so the absence
has to be given a meaning rather than treated as an error: the value in
`base`, the patch unrequired, and the template reading the field with a
fallback.

**A kind named after what reads it.** One that registers a cluster with
Argo, called `XArgoCluster`, parses as a cluster belonging to Argo —
and the cluster already has a kind. A name borrowed from a reader also
inherits that reader's lifetime, so the kind looks retirable the moment
the reader is.

**Two XRs claiming one object, while nothing looks broken.** A slot
name reused from a live managed resource: the parent matches the live
object to the new slot rather than releasing it, both XRs then claim
it, and Kubernetes refuses two owner references with `controller: true`.
Every apply the new kind attempts fails, the old owner never lets go,
and the resource keeps running under its original owner while the
transfer never completes.

**A first reconcile that fails on a value the resource cannot be
created with.** A Composition has no way to withhold a field: a patch
always writes something, a map transform must name every key it may
see, and `patch-and-transform` cannot express "not this field, this
time". So a value that is a _transition_ rather than a _state_ — Cloud
SQL's `activationPolicy: NEVER`, which stops a database and cannot
create one stopped — fails rather than being skipped. Reading observed
state in a templating step does not rescue it, because the resource
would still have to be created legal and moved afterwards. Say so in
the XRD, where whoever writes the manifest is looking.

**A resource composed with a hole in it.** An XRD default is absent for
a window: Argo applies the XRD and the Composition in one sync, and the
new Composition can be selected before the API server serves the
regenerated CRD, so a patch reading that default is skipped. What it
costs depends on the target — a field the provider's CRD requires fails
the apply, which retries and heals, while an optional one composes a
resource the provider may then fail to observe, a state no later
reconcile recovers because the resource exists and is wrong.

**Every templated resource unready for ever, naming one that is
`Available`.** A pipeline mixing `function-patch-and-transform` and
`function-go-templating` without `function-auto-ready` at the end.

**A whole pipeline failing on one composed resource.** A cluster-scoped
kind composed from a namespaced XR fails everything with `cannot apply
cluster scoped composed resource … for a namespaced composite resource`,
and may survive one reconcile before it does, because create and apply
are different paths.

## Rules

**MUST:**

- Give the application one kind, and decompose inside it into kinds
  that group managed resources created and destroyed as one.
  `just crossplane-kinds` is what each composes today.
- Fix the invariants in `base`, and leave the caller only what does not
  change what the kind guarantees.
- Read the slot names already in use — `just crossplane-slots` — before
  naming a composed resource.
- Change a resource's `- name:` to rebuild it under a new
  `metadata.name`. Deleting the object alone rebuilds the old one.
- Set `policy.fromFieldPath: Required` where a missing source is a
  mistake rather than a meaning, and on every patch reading a field the
  XRD defaults — except in the change that adds the field to a kind with
  live XRs, where absent means an XR older than the field: put its value
  in `base`, leave the patch unrequired, and read it in a template with
  a fallback.
- Put constants in `base`.
- Use `function-go-templating` where the number of composed resources
  varies with the caller.
- Fix a field that cannot change after create in `base`, never in a
  caller-supplied patch, and compose a second resource where a caller
  must vary one. Nothing in the CRD says which fields those are.
- Carry `Delete` in `managementPolicies` only where a rebuild returns
  what was there — `just crossplane-policies` is what Crossplane may do
  to each composed resource today. Withholding it is the prudent
  default.
- End every Composition with `function-auto-ready`. The `AUTO-READY`
  column of `just crossplane-kinds` should read `yes` throughout.
- Add a `readinessCheck` against the field carrying the real state,
  where a managed resource's own conditions do not reflect the cloud.
- Make every Composition edit safe for an XR that already exists: add
  fields rather than repurpose them, default what a manifest does not
  yet set, and never make a field required in the change that
  introduces it.

**MUST NOT:**

- Add a version to an XRD. Where a change is that large it is a
  different kind, named for what it is and adopted deliberately.
- Set `compositionUpdatePolicy: Manual` on an XR. Pinning divides an
  estate into the XRs that took an edit and the ones that did not,
  which is the problem versioning an XRD would have caused.
- Give a default to a field whose `Required` patch switches a group of
  composed resources on and off. A default makes it always present.
- Expect a Composition to withhold a field.
- Compose a cluster-scoped kind from a namespaced XR.
- Compose resources with different deletion criteria into one kind.
  Deleting a kind deletes what it composed, so something that must
  never be deleted does not belong with something rebuilt routinely —
  a public zone with a public endpoint, a network with a cluster.

## Discussion

We design a kind by deciding what it covers, what its caller supplies,
omits or is given, what its parts are called, what may be destroyed or
merely watched, and when it is finished — and we make each of those
decisions once, because nothing here revisits them.

**The four things, in Crossplane's words.** An **XRD** defines the
schema and creates the API endpoint. A **Composition** configures how
an XR creates other resources. A **composite resource (XR)** is the
result — "a set of Kubernetes resources as a single Kubernetes object".
The **composed resources** are what the Composition creates, and the
**managed resources (MRs)** among them map one-to-one onto a remote
API.

**Why every edit is immediate.** An XR's `compositionUpdatePolicy`
defaults to `Automatic`, so it uses the latest `CompositionRevision`
and takes an edit on its next reconcile. It could pin — `Manual` with a
`compositionRevisionRef` — and nothing here does, deliberately. A
pinned estate divides into the XRs that took the change and the ones
that did not, so a policy tightened centrally becomes a policy that has
to be chased, which is exactly what a shared module in a tool with
version pinning costs.

**Which is why the kind stays at `v1alpha1`.** A version on an XRD is
not a compatibility knob, because nothing pins one. A second version
promises an upgrade path that does not exist: existing XRs would have
to be migrated either way, and the version number is the only thing
suggesting otherwise. Where a change is large enough to want a version,
what it wants is a different kind with a name of its own, that an
installation moves to when it chooses.

**So every edit is designed for an XR that already exists.** The
manifests live in another repository and reach a plane on their own
schedule, so a field made required in the change that introduces it
breaks every XR whose manifest has not caught up. Add rather than
repurpose, default what is not yet set, and let the requirement follow
once the manifests do.

## References

- [crossplane-debug](crossplane-debug.md) — what in an installation
  is not ready, or not what you declared.
- [crossplane-live](crossplane-live.md) — whether
  a change applies, is refused, or destroys.
- [crossplane-providers](crossplane-providers.md) — what
  Terraform-backed providers add to this.
- [ADR-0025](../../adr/0025-building-blocks-and-what-cannot-be-one.md) —
  what cannot be a kind at all.
