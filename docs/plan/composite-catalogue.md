# Plan: a catalogue of composites, and what is left of it

## Context

[ADR-0025](../adr/0025-building-blocks-and-what-cannot-be-one.md)
recorded a direction and a boundary for a catalogue of building blocks
and said nothing was built. Five kinds now are. Four came out of
`XQueenswoodInstance`, which went from 2,985 lines to about 1,520:

- `XPostgres` — a Cloud SQL server, the databases named on it, and the
  one proxy identity every database is reached through.
- `XNetwork` — a VPC, the subnet a cluster sits in, and the proxy-only
  subnet a regional load balancer wants.
- `XPublicEndpoint` — the address an environment answers on, the
  certificates it terminates, and the records pointing its names at
  that address.
- `XCluster` — a GKE cluster, the pool that runs on it, and the
  identity that pool runs as.

The fifth came out of the plane:

- `XPublicZone` — a public zone one installation is authoritative for,
  and whatever records it holds in that zone directly.

All five are in `platform`, beside `XManagementPlane` and
`XManagedUnit`. `platform` means not this product rather than not this
cloud: every one of them composes GCP resources and nothing else.

The mechanics — what a transfer does, what it costs, and the three ways
it went wrong — are in
[crossplane-live](../recipes/infra/crossplane-live.md). This plan is
what is left to do, not how to do it.

## Hollowing out the plane

The plane composes 45 managed resources and two of the composites
above in 2,303 lines. It was left alone deliberately while the instance
was the proving ground; the instance was the proof.

- **`XNetwork` and `XCluster`, 250 lines.** Both are composed by the
  plane now, with `env: c` and `label: mgmt`. The network is an adopt
  with no rename — the names those two fields render are the ones the
  plane built by hand — plus the proxy subnet it has never had, which
  is what the `network` block's `proxyCidr` is for. The cluster is a
  rename of four live resources — the cluster, the pool, the node
  identity and the binding naming it — so the merge composes them under
  their new names beside the live ones and the plane swaps onto the
  cluster it built.
  `XCluster` gained a `retain` field for the one thing that differs: an
  instance's cluster carries `Delete` and the plane's withholds it. See
  [plane-cluster-naming](plane-cluster-naming.md), which carries the
  ordering and the one binding the plane must go on granting itself.
- **The `ProjectService` loop, 367 lines across ten.** Two of the ten
  do not follow their own names — `cloudresourcemanager` and
  `cloudbilling` — so the plane's list is names beside APIs where the
  instance's is bare names. Both are genuinely needed here: the plane
  is the caller whose project the enablement is counted against, which
  is why the instance needed neither.

What is left is that loop, and it needs no new kind.

Composing another composite is a prerequisite of bootstrapping rather
than a change to the composition alone: `boot-cluster-up` applies the
XRD and Composition of every kind the manifest reaches, directly or
through another composite, because `boot-mgmt-apply` otherwise fails
with `no matches for kind` — which stops the whole pipeline rather than
one resource, and only on the next bootstrap, which is rare enough that
it will not be the person who made the change. See
[plane-install](../recipes/infra/plane-install.md).

What the plane must keep is what only it has: the folder and its
bindings, the management project, Crossplane and Argo as `Release`s,
and the four identities.

## `XPublicZone`

Built, and reshaped by
[ADR-0028](../adr/0028-the-apex-belongs-to-no-installation.md) before
it was ever used. The kind is not what this plan first sketched: it
holds a zone one installation is authoritative for, one per name an
environment answers on, rather than the installation's apex zone.

The argument that split it survives with a different reason under it.
An endpoint rebuilds from its own declaration; a zone does not, because
its nameservers change when it is rebuilt and something outside the
installation names them. So the two still belong to different kinds —
not because a zone must never be replaced, which a delegated one may
be, but because replacing one costs an edit the installation cannot
make itself.

The endpoint goes on naming the zone rather than owning it: two
composites cannot read each other's status, and a zone that enumerated
its tenants would make adding an environment a change to the plane.

What the plane keeps is nothing. The apex belongs to no installation
and is declared outside every control plane, so `XManagementPlane`'s
`dns` step and its XRD field go, and the estate's one zone moves out of
the installation holding it. That move is
[apex-dns-migration](apex-dns-migration.md), which is where the
ordering lives.

## `XEgress`

Nothing composes a Cloud NAT today, so this is a capability rather than
a refactor. It sits beside `XPublicEndpoint` rather than beside the
zone — instance-scoped, disposable, and about how traffic leaves one
environment rather than about what domain an installation owns.
`cloud-naming` already reserves `nat-<code>-<env>-<region>`.

It is what a payment provider that allowlists a source IP needs, which
nothing can be given while traffic leaves via whichever node runs the
adapter.

## `XArgoDestination`

Two resources in the instance composite are not this product's either:
a `ProjectIAMMember` granting the deployer identity `container.admin`
on the project, and an `Object` writing the Secret that registers the
cluster as a destination. Any cluster Argo deploys to needs exactly
that pair, and nothing about either is Queenswood.

They are a pair in the sense
[ADR-0025](../adr/0025-building-blocks-and-what-cannot-be-one.md)
means: half of it is a silent failure. A registration with no binding
syncs and then fails on every apply; a binding with no registration is
a cluster Argo cannot see.

Named in Argo's own vocabulary. An Application has a `destination`,
and what this composes is the thing a destination resolves to, so the
word is borrowed rather than invented -- the same reason
`XPublicEndpoint` reads as it does.

Not `XArgoCluster`, which parses as a cluster belonging to Argo: Argo
is not a cluster and the cluster already has a kind. Not a
tool-agnostic name either. `XPostgres` is the concrete engine where
`XCloudSQL` would be the generic wrapper, so naming the specific thing
is what this repository already does -- and every resource here is
Argo-shaped, so a name pretending otherwise would claim a portability
the composition does not have.

It should take the endpoint and the CA as spec fields rather than
composing the cluster itself. A cluster kind has no business knowing
how anything deploys to it — that is the line
[ADR-0024](../adr/0024-instances-are-their-own-composites.md) draws
between what a composite builds and what Argo installs. That leaves the
values arriving through the composing instance's status, which is the
coupling that broke this cluster's registration once already; naming it
in a spec does not remove the round trip, but it makes it a declared
dependency rather than an incidental one.

One call site today, so the shape would be read off a single example —
the caveat `XPostgres` carried, and the reason its first version
hardcoded a database name.

The custom role beside them is unrelated: it is a viewer capability
granted through the access step rather than anything to do with
deployment.

## Retiring an environment

Nothing covers finishing one. `instance-deploy` builds an environment
and [fdb-recovery](../recipes/infra/fdb-recovery.md) stops one, and
between them the estate can raise an instance and hold it at zero — but
there is no recipe for ending one, and the pieces are exactly those that
resist being reconciled away.

Deleting the `XQueenswoodInstance` deletes what it composes, subject to
each resource's `managementPolicies` — so the disposable tier goes and
the protected tier is orphaned rather than removed. What is left needs
doing by hand, in an order nothing states:

- the NS record in `apex.yml`, which is above the installation and
  removed by a person;
- the environment's zone, which withholds `Delete` for the reason
  [ADR-0028](../adr/0028-the-apex-belongs-to-no-installation.md) gives
  and so survives its composite;
- the instance's project, retired by lifting its lien, which
  [ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md)
  says is deliberate and never a reconcile;
- whatever the recovery project holds for it, which outlives the
  instance on purpose and is the one thing a retirement must not take
  with it.

The order matters in one place. The delegation goes before the zone, or
the name resolves to nameservers answering nothing — which is worse than
not resolving, because a client gets `SERVFAIL` rather than `NXDOMAIN`
and the cause reads as an outage rather than an absence.

That the pieces resist automation is the design working rather than a
gap in it. The gap is that a person retiring an environment has to
derive all of this from four documents, at the moment they are least
likely to be careful.

## Smaller things

- **Rename `instance-gke` back to `instance-cluster`**, for consistency
  with every other slot. Not free: renaming the slot deletes the
  composite in it, which deletes the pool, which carries `Delete`. It
  wants the two-step from
  [crossplane-live](../recipes/infra/crossplane-live.md) applied one
  level down — withhold `Delete` from the pool inside `XCluster` first,
  rename, then restore. Worth doing for the name alone, but not
  casually.
- **Stop enabling `serviceusage` on an instance project.** The same
  argument that removed `cloudresourcemanager`: enabling any API on a
  project is a Service Usage call against a project that has nothing
  enabled yet, so the call cannot require what it is about to turn on.
  Left alone when the other went, because one change should remove one
  thing.
- **The `allowedServices` constraint**, which bounds what may be
  enabled at all rather than reconciling what was. Recorded in
  [cloud-just-migration](cloud-just-migration.md) under what is
  outstanding.

## Ordering

Retiring an environment is not on this list's critical path and does
not wait for any of it — it is a recipe rather than a kind, and the
sooner it exists the less likely it is to be written during a
retirement.

The plane before the new kinds. Hollowing it out uses kinds that exist
and are proven on a live instance; `XPublicZone` and `XEgress` both add
kinds, and one of them touches DNS, where a mistake is measured in
registrar propagation rather than in reconciles.

The estate's zone leaves the plane before any of this, for its own
reasons rather than the plane's — see
[apex-dns-migration](apex-dns-migration.md). Once it has, a plane
rebuild has no zone to preserve, which is one fewer thing
[plane-cluster-naming](plane-cluster-naming.md) has to sequence around.

Within the plane, the `ProjectService` loop first: it is the only one
of the three that transfers no ownership at all, because a loop can
reproduce the composed names it replaces exactly.
