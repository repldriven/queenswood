# 29. A shelved instance keeps everything but its cluster

<!-- tessl-plugin: deployment -->

## Status

Proposed, and built as far as the repository goes: `spec.cluster.shelved`
on `XQueenswoodInstance` and `XCluster`, the record an instance leaves in
its backups bucket, and the
[instance-shelve](../recipes/infra/instance-shelve.md) and
[instance-unshelve](../recipes/infra/instance-unshelve.md) recipes. A
test instance has been shelved and unshelved, restoring FoundationDB to
the recorded version with every record intact.

Extends [ADR-0022](0022-cloud-foundation-and-environment-lifecycle.md),
which made off a declared state, and
[ADR-0026](0026-recovering-data-and-the-states-that-do-it.md), which
separated a reversible state from a destructive one.

## Context

`down` takes an instance's node pool to zero and stops its database,
and leaves the cluster standing, because GKE offers no stopped cluster.
A standing cluster is billed a management fee whether it has nodes or
not, and the free tier covers one zonal cluster per billing account.
The management plane runs on one, so every instance's cluster is billed
in full while it does nothing: frozen, the plane costs its cluster
alone; `down`, an instance still costs a cluster.

What an instance holds of lasting value is almost all outside its
cluster already. The project holds the network, the identities, the
Cloud SQL database behind Keycloak, the address and certificates the
instance answers on, and the Secret Manager entries its workloads read
through Workload Identity. The recovery project holds the backups
bucket and the key those backups are encrypted under. The one thing
inside the cluster is FoundationDB's volumes, and FoundationDB is
backed up continuously into that bucket.

The options:

- **Delete the instance's composite, and apply it again later.** The
  protected tier is orphaned rather than deleted and the next composite
  adopts it, which re-adopting the plane has shown works. Rejected: it
  deletes a composite to save money, which ADR-0024 forbids for tidying
  up, and it leaves the running database orphaned and billed until
  something adopts it.
- **Rebuild from nothing — a new project, database and secrets.**
  Rejected: a project id cannot be reused, and a new one needs a new
  OAuth client created by hand, every secret written again, and
  Keycloak restored alongside FoundationDB to a matching point.
- **A value of `state` meaning no cluster.** Rejected: ADR-0026 keeps a
  destructive state out of the enum a reversible one lives in, so that
  nobody recovers an environment by picking the wrong word from a list.
- **A field on the cluster, `shelved`, that composes neither the
  cluster nor its pool.** Everything else stands, and FoundationDB comes
  back from its backup.

## Decision

An instance may be shelved: `spec.cluster.shelved: true` stops composing
its cluster and node pool, Crossplane deletes both, and every other
resource the instance composes stands. Unshelving sets it false, the
cluster is composed again under the same name, and FoundationDB
restores from the point recorded when it was shelved.

The decision has these parts:

- Shelve only an instance that is `down`, and never a cluster with
  `retain`: the XRD refuses both, since a shelved instance with a
  running database is still billed for it, and a retained cluster is the
  one the plane's Crossplane runs on.
- Take the workloads off the cluster before shelving it, by withdrawing
  the unit's Applications and deleting them, while it has nodes: a
  persistent disk and a load balancer outlive a cluster deleted from
  under them, and an operator's finalizer needs its pod to run.
- Record the restore point — generation and version — in the instance's
  backups bucket under `records/` before the workloads come off, and
  carry it into `fdb.restore` in the merge that shelves: the bucket and
  the manifests repository hold everything an unshelve reads, and no
  machine does.
- Point `fdb.backup.backupName` at a new generation in the merge that
  unshelves, never earlier: a live cluster writing to it would put
  higher versions there than the rebuilt one starts from.
- Compose the cluster and its pool afresh on unshelve rather than
  retaining their managed resources, so no late-initialised field from
  the old cluster is sent as a create parameter to the new one.
- Keep the address through a shelve, so the certificates and records
  stay valid and the instance comes back on the name it had.

## Consequences

Easier:

- An installation with one instance costs, shelved and with the plane
  frozen, storage and one address: no cluster outside the free tier, no
  nodes, no disks, no load balancer.
- Every unshelve restores FoundationDB from its backup, so the backups
  are read regularly rather than believed in.
- Nothing is written by hand on unshelve. The secrets reach the new
  cluster from Secret Manager through the same bindings, since the
  Workload Identity pool belongs to the project rather than the cluster,
  and Keycloak's bootstrap admin is the one the database was created
  with.
- Keycloak and FoundationDB come back consistent with each other: the
  database is never restored, and nothing writes to either while the
  instance is shelved.

Harder:

- A shelve destroys FoundationDB's volumes, so an unshelve is only as
  good as the backup and the key. Until one round trip has been proven
  with a key count, an instance whose data matters is not shelved.
- Shelving takes two merges and a deletion on the plane, and
  unshelving two merges, where `down` takes one. The deletion is
  break-glass by design: it is the step that destroys data.
- The address is billed while nothing uses it.
- Argo's registration for the instance's cluster stands with no address
  while it is shelved, and reports the cluster unreachable.
- The recorded restore point and the one in `fdb.restore` can drift if
  either is edited by hand; `just queenswood-instance-records` lists
  what the bucket holds, which is what the merge should say.
