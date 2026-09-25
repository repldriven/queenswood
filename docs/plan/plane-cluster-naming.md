# Plan: name the plane's cluster the way an instance's is named

## Context

[cloud-naming](../recipes/practices/cloud-naming.md) says a cluster is
`<code>-<env>-<label>` with no kind prefix, "since GKE prefixes `gke-`
itself". An instance follows it. The management plane does not: it
composes `gke-<code>-c-mgmt`, from before the rule and before
`XCluster` and `XNetwork` existed.

The cost is visible on the nodes, which GKE names from the cluster:

```
plane     gke-gke-qw01-c-mgmt-np-qw01-c-mgmt-xxxxxxxx-xxxx
instance  gke-qw01-n-test-np-qw01-n-test-primar-xxxxxxxx-xxxx
```

The plane doubles the prefix, and spends four characters of the
node-name budget on it. The instance's own pool name is already
truncated — `np-qw01-n-test-primar` — so the budget is not notional.

The second cost is structural. `XManagementPlane` composes its
`Network`, `Subnetwork`, `Cluster`, `NodePool` and node identity
directly, where an instance composes `XNetwork` and `XCluster`. So the
plane rebuilds a subnet's name in its own `Cluster` patch:

```
CombineFromComposite over spec.code and spec.regionCode
  -> "sb-%s-c-mgmt-%s" -> spec.forProvider.subnetworkRef.name
```

A combine cannot read the EnvironmentConfig, so this is the one reason
`XManagementPlane.spec.regionCode` still has to be set, and the one
reason `installation.yml` still states a region field after the region
moved into the installation's environment.

## Where this starts

The estate this is written against, so the reader needs nothing else:

- One installation, one instance, and the instance is `state: down`.
  Nothing serves anybody, which is what makes now the cheap moment.
- The plane runs `gke-<code>-c-mgmt`, a zonal cluster in the management
  project, reached as the `<code>-mgmt` kubectl context.
- Its `Network`, `Subnetwork`, `Cluster`, `NodePool` and node identity
  are composed directly by `XManagementPlane`, not through `XNetwork`
  and `XCluster` as an instance's are.
- Every DNS name now resolves through the apex, which the plane does
  not own and this rebuild does not touch.

Nothing outside the plane refers to the cluster by name except
`MGMT_CTX` and the `plane-ctx` recipe that builds it, both of which
spell `gke-<code>-c-mgmt` by hand.

## Why this needs a rebuild, and what a rebuild is

Every part of it is a rename of a live resource, and a rename is a
delete and a create. The two halves differ in what that costs:

- Moving `Network` and `Subnetwork` into a composed `XNetwork` renames
  nothing. Both leave their slots and are composed again under the same
  names inside the new kind, and neither has ever carried `Delete`, so
  the GCP objects are adopted rather than replaced.
- `gke-qw01-c-mgmt` is the cluster Crossplane and Argo run on, and a new
  name means a new cluster. The plane cannot delete and recompose the
  one it is standing on, so what happens instead is that the merge has
  the plane build its successor, the successor adopts the estate, and
  the cluster it replaced is deleted afterwards. That is
  [plane-rebuild-cluster](../recipes/infra/plane-rebuild-cluster.md),
  and it needs no boot plane and no seed identity: nothing here is
  outside the folder the platform identity already holds rights in.

Nothing is down for any of it. The plane is not serving anybody, the one
instance keeps running whether or not anything is reconciling it, and
until the last step there is a way back.

## Does `XCluster` fit the plane

Checked field by field. It does, with one thing the plane keeps and
one field the kind gains.

**The `Cluster` is the same or better.** Both compositions patch the
same six fields — `location`, `networkRef.name`, `project`,
`releaseChannel.channel`, `subnetworkRef.name`,
`workloadIdentityConfig.workloadPool` — and `XCluster`'s `base` is a
superset of the plane's. It fixes four invariants the plane has never
set:

```
addonsConfig               httpLoadBalancing enabled
gatewayApiConfig           CHANNEL_STANDARD
datapathProvider           ADVANCED_DATAPATH
inTransitEncryptionConfig  IN_TRANSIT_ENCRYPTION_INTER_NODE_TRANSPARENT
```

Two of those cannot be turned on afterwards, which is another reason
this belongs to a build rather than a migration.

**The `NodePool` is a superset too.** Same `networkConfig.podRange`,
`oauthScopes` and `GKE_METADATA`; plus machine type, disk, the node
service account, upgrade settings, and a node count driven by
`spec.state`. The plane has no state and never will —
[ADR-0024](../adr/0024-instances-are-their-own-composites.md) says
state is an instance's property — so it takes the XRD's default of
`up` and never sets it.

**The one thing `XCluster` deliberately does not do.** It composes the
node identity and its `defaultNodeServiceAccount` binding, but no
`serviceAccountUser` on that identity, and says why: for an instance
the platform identity created the project, so it is owner and attaching
a service account to a node pool is a right it already holds. The
plane's project was created by the seed, not by the platform identity,
so the plane grants that binding explicitly and must go on doing so.

Keep it in `XManagementPlane` rather than adding a flag to `XCluster`.
The binding is a fact about who created the project, not about
clusters.

**The one thing that does need a field.** `XCluster` carries `Delete`
on the cluster and the pool, which is what ADR-0022 leaves the
disposable tier and what makes retiring an instance destroy its
cluster. The plane's withholds it: the cluster it names is the one the
Crossplane performing the deletion runs on, so orphaning it is what
makes composite deletion survivable, and what lets each composite adopt
what the last one built.

That is a property of the resource's own lifecycle rather than of who
created a project, so it is a field — `retain`, false by default, which
composes exactly what an instance composes today, and true on the
plane.

**Names that move**, beyond the cluster itself:

```
                today               after
node identity   sa-<code>-c-nodes   sa-<code>-c-mgmt-nodes
node pool       np-<code>-c-mgmt    np-<code>-c-mgmt-primary
node binding    <code>-nodes-…      <code>-c-mgmt-nodes-…
```

The binding is the `defaultNodeServiceAccount` grant, which follows the
identity it names. It is a new binding rather than a moved one, since
the member is a different account.

Check both against `XCluster` rather than trusting this table: they are
what a rebuild gets right for free and a migration would have to
delete.

## What to change

**`XManagementPlane` composes `XNetwork`** rather than a `Network` and
a `Subnetwork`. With `env: c` and `label: mgmt`, `XNetwork` already
produces `vpc-<code>-c-mgmt` and `sb-<code>-c-mgmt-<regionCode>` —
the names the plane composes today, so this part is a no-op even
against a live plane.

It also composes a proxy subnet, which the plane has never had. Give
the plane's `network` block a `proxyCidr` beside its existing three,
defaulted in the same range.

**`XManagementPlane` composes `XCluster`** rather than a `Cluster`, a
`NodePool` and a node identity. This is what renames things:

```
                        today                     after
cluster                 gke-<code>-c-mgmt         <code>-c-mgmt
kubeconfig secret       gke-<code>-c-mgmt-kubeconfig  <code>-c-mgmt-kubeconfig
node identity           see the composition       sa-<code>-c-mgmt-nodes
```

Check the node pool's name against `XCluster`'s before assuming it
matches: the plane's is `np-<code>-c-mgmt` and an instance's is
`np-<code>-<env>-<label>-primary`.

It composes the XR with `retain: true`, and with no `state`: the plane
has none, so the pool takes the kind's default of `up`.

**`MGMT_CTX`** is `<code>-mgmt` and the context is renamed from
whatever `plane-ctx` fetches, which builds `gke-<code>-c-mgmt` by hand.
That string moves with the cluster.

**`regionCode` stops being the plane's.** `XNetwork` takes it as a
plain patch, so `XManagementPlane` patches it from the environment the
way it patches `region` and `zone`, and `XCluster` takes it the same
way. Then:

- the plane's own `spec.regionCode` is unused, and can be removed from
  the XRD once no manifest sets it;
- `installation.yml` states no region field at all, which is what
  [contract-install](../recipes/infra/contract-install.md) already says
  the environment is for.

## The zone was the blocker, and it is cleared

A plane could not be rebuilt while it composed the zone the registrar
pointed at. That is no longer true.
[ADR-0028](../adr/0028-the-apex-belongs-to-no-installation.md) put the
apex in a project at the organisation, declared in git and composed by
nothing, and
[apex-dns-migration](apex-dns-migration.md) moved this estate onto it:
the registrar delegates to the new apex, the apex delegates each
environment's name to a zone the installation composes, and the one
instance answers from its own.

What is left of the old arrangement is inert but still declared. The
plane goes on composing the zone the registrar no longer points at,
because `installation.yml` still carries a `dns` block. Removing it is
the first thing below, and after it the plane composes no DNS at all —
which is the state this rebuild wants to start from.

## What not to do

A `subnetworkSelector` with `matchLabels` will resolve the subnet
without naming it, and is tempting because it retires `regionCode`
without the rename. It is not worth it. To be unique the label has to
carry `<code>-<env>-<label>`, so the derivation moves from a name to a
label rather than disappearing — and a Kubernetes name is unique
because the API server enforces it, where a label is unique because
somebody was careful. For the field that decides which subnet a cluster
attaches to, that is a guarantee traded for a convention.

The plane's `regionCode` is not a wart in the composition. It is the
shadow of the cluster not having been migrated, and it goes when that
does.

## Ordering

**Finish the DNS tail first.** It is three merges and one deliberate
delete, and none of it touches the cluster.

1. Remove the `dns` block from `<code>/installation.yml`. The plane
   drops the zone and its two record objects; all three survive in GCP,
   because neither carries `Delete`.
2. Remove the `dns` step and the `dns` field from `XManagementPlane` —
   its composition and its XRD — now that no manifest sets it. A field
   cannot leave the schema while a manifest still carries it, or Argo
   diffs for ever.
3. Delete the old apex zone, deliberately and on its own. Until then it
   is the way back from the delegation move, so there is no hurry.

**Then the rebuild.**

4. Make the changes under *What to change* and merge them. The plane
   reads this repository from git, so the merge is what starts the
   rebuild: `XNetwork` adopts the VPC and the subnet, the successor
   cluster is composed under its new name, and the cluster the plane is
   running on leaves its slot and keeps running with nothing
   reconciling it.

   `boot-cluster-up` also has to apply the `XNetwork` and `XCluster`
   XRDs and compositions — see
   [composite-catalogue](composite-catalogue.md), and note that the
   apex work hit exactly this by merging a manifest before its kind.
   Nothing in this ordering runs it, but the next installation does.
5. Swap onto the cluster the merge built, with
   [plane-rebuild-cluster](../recipes/infra/plane-rebuild-cluster.md).
   The names are right from the first create, and the estate is adopted
   rather than rebuilt.
6. Render an instance and confirm nothing states a region field.
7. Remove `regionCode` from `XManagementPlane`'s XRD, once no manifest
   sets it.

Where a plane is being installed from nothing instead, none of this is a
rebuild: the names are chosen once and there is no successor and no
swap.

## References

- [cloud-naming](../recipes/practices/cloud-naming.md) — no kind prefix
  on a cluster, and why.
- [crossplane-live](../recipes/infra/crossplane-live.md) — moving a
  resource between composites, and what a rename does.
- [crossplane-design](../recipes/infra/crossplane-design.md) — a
  composed resource is identified by its composition name.
- [plane-install](../recipes/infra/plane-install.md)
  — building the plane, and the pivot a rename would repeat.
- [ADR-0024](../adr/0024-instances-are-their-own-composites.md) — what
  the plane composes, and what Argo installs.
- [composite-catalogue](composite-catalogue.md) — the rest of what
  hollowing out the plane leaves.
- [apex-dns-migration](apex-dns-migration.md) — moving the apex out
  of the installation, which happens before any of this.
- [plane-rebuild-cluster](../recipes/infra/plane-rebuild-cluster.md) —
  the swap step 5 is, written for any plane rather than this one.
