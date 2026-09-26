# Recovering FoundationDB

<!-- tessl-plugin: deployment -->

## Problem

The instance backs itself up continuously and nothing has ever been
restored from it.

The scenarios are the ordinary ones — initial provisioning, stop and
start, planned cluster rebuild, unplanned failover, logical
corruption — and
they behave the way they do on any cloud database. What is particular
here is which of them this installation can actually serve today, and
that `fdb.restore` records where a cluster's data should come from
rather than triggering a restore, so what happens depends on the state
the cluster is already in.

This recipe is the scenarios, their RPO, and what to set.

## Solution

### The backup is a full copy plus a transaction log

The standard shape. `fdbbackup` writes periodic full snapshots — every
`snapshotPeriodSeconds`, an hour by default — and ships the mutation
log continuously between them. That is FoundationDB's equivalent of
WAL, binlog or redo-log shipping, and it gives the same two recovery
modes:

- **Restore to latest** — the end of the log. Used when the moment does
  not matter, or when nothing recorded it.
- **Point-in-time recovery (PITR)** — any version inside the restorable
  window, not only a snapshot boundary, because the log fills the gaps.

The restorable window is what `just sop-fdb-list-backups <env> <label>`
prints. Its end is the practical RPO floor for every unplanned
scenario, and it is a real measured value rather than a derived one —
read it rather than assuming `snapshotPeriodSeconds`, which bounds the
full copies only.

### Scale-to-zero is not a recovery scenario

`state: down` is the node pool at zero. The GKE cluster, the project,
the network, the identities and every PersistentVolume stand, and the
`FoundationDBCluster` and its StatefulSet are untouched — so bringing
the environment back schedules the same pods onto the same disks with
the same data. In time `down` also takes Cloud SQL to
`activationPolicy: NEVER`, stopping Keycloak's database rather than
deleting it.

This is worth stating because the previous generation's teardown *was*
its stop, so a rebuild was how you restored. That coupling is gone.
Nothing about `down` needs a restore point, and reaching for one costs
data that was never lost.

### What decides which cell you are in

**The recovery mode requested**, in the instance's chart values:

- **none** — `fdb.restore.backupName` and `fdb.restore.version` both
  empty. No Job renders.
- **latest** — `backupName` names a generation, `version` empty.
- **specific** — both set. PITR to that version.

A `version` with no `backupName` is refused at render: a version names
a point inside one container, and each cluster generation has its own.

**The state of the destination**, which the restore Job reads for
itself — one key, `getrangekeys "" \xff 1`:

- **none (new)** — empty. A greenfield cluster, or one rebuilt after
  loss.
- **existing** — populated, and correct.
- **corrupted** — populated, and wrong.

FoundationDB refuses a restore into a non-empty destination, and the
Job checks emptiness before submitting one. That check separates the
first from the other two and cannot separate the second from the third:
emptiness is mechanical, corruption is a judgement about content.

Note this is *logical* corruption — bad data written by a migration, a
batch or a bug, structurally valid and semantically wrong. Media
corruption on a disk is handled by the cluster's own replication and is
not a restore scenario.

### The matrix

| restore \ FDB | none (new)     | existing     | corrupted    |
| ------------- | -------------- | ------------ | ------------ |
| none          | provisioning   | normal ops   | the incident |
| latest        | DR failover    | no effect    | no effect    |
| specific      | PITR rebuild   | no effect    | no effect    |

Only the empty column acts. Everything in the other two completes
successfully and changes nothing.

### Initial provisioning — none × new

Greenfield. No restore Job renders, the migrator runs unblocked,
bootstrap seeds a bank that has never held anything.

**RPO:** not applicable. **RTO:** a normal deployment.

### Normal operation, including stop and start — none × existing

The common case, and the section above. Leave `fdb.restore` alone.

It is safe to leave a version set here from an earlier recovery: the
emptiness check means an ordinary reconcile against a populated cluster
does nothing, and the Job's name is a hash of what was asked for, so
re-applying the same values resolves to a Job that already completed.

**RPO:** zero, no data is lost. **RTO:** the time to schedule pods.

### Planned cluster rebuild — specific × new

Any change to a field that identifies the composed `Cluster` rebuilds
it: `zone`, `region` — the subnet with it — `datapathProvider`, and
anything else the provider treats as ForceNew. The trigger varies and
the event does not, so read this section by what is being rebuilt
rather than by what prompted it.

It is a rebuild in place rather than a migration, and it rebuilds the
cluster rather than the instance. The instance is not replaced: same
project, same name, same address, same DNS records, same database. Only
the cluster and the volumes under it go. There is no second instance at
any point, so there is nothing to cut over to and no second name to
invent.

Two larger rebuilds exist and are not this. Rebuilding the *instance*
destroys its project, so the database goes with the cluster and
Keycloak has to be recovered alongside FoundationDB, to points chosen
together. Rebuilding the *installation* takes the recovery project too,
and with it the backups being restored from. Neither is written down.

That is also why there is no way back. This is the in-place shape, and
it commits at the moment the volumes go.

Because it is planned, the restore point is chosen rather than
inherited. Quiesce writes, let the mutation log catch up, take the
version with `just sop-fdb-version-at <env> <label> <time>`, then
rebuild and restore to it.

One trap belongs to the trigger rather than the recovery: a ForceNew
change is refused rather than performed, so editing the field alone
leaves the composite reporting `Synced` while the cluster carries on
unchanged, and the refusal appears only in `LastAsyncOperation`. The
managed resource has to be deleted for the rebuild to happen at all.

**RPO:** zero if writes stop before the version is taken; otherwise
everything after it. **RTO:** a full rebuild plus the restore, which is
hours rather than minutes and has never been measured here.

Restoring *beside* the instance instead would need a second label, and
every per-instance name follows it — including the backups bucket and
the backup key, which is one per instance and granted on that entry
alone. A twin would hold a key that cannot read the original's backups,
and nothing composes the grant that would fix it. That is a larger part
of why the side-by-side shape is unbuilt than the cutover is.

### Unplanned failover — latest × new

Disaster recovery. The zone or the cluster is gone with no orderly
teardown, so nothing recorded where to stop and the end of the log is
the best-defined point available. This is the one scenario where
restore-to-latest is the right mode.

It is well defined only because a container now holds one cluster
generation. While generations shared a container, "latest" could be
another cluster's.

**RPO:** the gap between the end of the restorable window and the
moment of the outage — minutes, but read it off the span rather than
assuming. **RTO:** rebuild plus restore, and this installation has no
standby, so recovery starts from provisioning. Backup-and-restore is
the DR tier in use; there is no pilot-light or warm-standby tier
underneath it.

### Logical corruption or accidental deletion — the incident

PITR to a point before the damage is the textbook answer, and it is the
cell that does not work. The cluster is populated, so setting a version
does nothing: the Job finds keys, prints `destination already holds
data; leaving it alone`, exits 0, the migrator's gate is satisfied by a
completed Job, and the deployment reports success over an unchanged
bank.

Two shapes exist in principle, and
[ADR-0026](../../adr/0026-recovering-data-and-the-states-that-do-it.md)
prefers the second here specifically:

- *Restore in place* — empty the destination and let the restore fill
  it. What the chart supports, and appropriate for a test environment.
  It commits at the moment the volumes go, and the corrupted data was
  the only record of what happened.
- *Restore to a new instance, then cut over* — the side-by-side shape.
  Reversible until the cutover, and it allows comparing the two before
  committing. It needs a cutover story this model does not have: DNS,
  the realm the console signs into, and the Keycloak user ids FDB
  records reference.

Neither is built. Both need the destination empty and nothing takes it
there — `down` preserves data by design, and the restore Job leaves a
populated cluster alone. Until that gap is filled, emptying means
acting on the cluster by hand, which is break-glass and needs
`clusterAdmin`.

Whatever fills it must not share a word with `down`, which is
reversible and preserves what it stops where this is neither. It must
also be self-limiting the way the restore Job already is, or a field
meaning "destroy and rebuild" destroys on every reconcile.

**RPO:** everything between the chosen point and the present, including
good writes made after the damage — which is the standard cost of PITR
and the reason the side-by-side shape matters. **RTO:** unbounded
today, because the procedure does not exist.

### The cells with no effect

`latest` or `specific` against a populated destination. The Job runs,
takes its do-nothing branch, and completes. A green restore Job is
evidence the Job ran, never that a restore ran.

The emptiness check is the safety property that makes leaving
`fdb.restore` set safe across every reconcile, so this is not a bug.
What is missing is a supported way to say "and I mean it".

### After any restore, write to a new generation

`fdb.restore.backupName` is the source container and
`fdb.backup.backupName` is the destination, and after a recovery they
must differ. Nothing enforces it and both default to `fdb/continuous`.

FoundationDB numbers versions from near zero on a rebuild, so a
restored cluster's first versions are *lower* than those already in the
container — and `fdbbackup describe` picks the highest restorable
version, which is the dead cluster's. It has already answered with a
restore point belonging to a cluster two rebuilds back.

Every acting scenario starts from a rebuilt cluster, so open a new
generation on every recovery. That is what
`<service>/<backup-type>/<generation>` has the slot for.

### After a FoundationDB upgrade, start the backup again

FoundationDB 7.3.79 reads the encryption block size from a backup's
own configuration rather than a knob, a change made in
[apple/foundationdb#13126](https://github.com/apple/foundationdb/pull/13126).
A backup started under an earlier version stored none, so every upload
fails `ASSERT(encryptionBlockSize > 0)` in the agents' trace logs,
while `fdbbackup status` goes on saying `restorable` about a log that
has stopped. `fdbbackup modify` carries the stored size forward, so a
new generation alone does not help.

Abort the running backup, then merge a new `fdb.backup.backupName`:

```bash
kubectl --context <code>-<env>-<label> -n queenswood exec \
  deploy/queenswood-fdb-backup-agents -- \
  fdbbackup abort -t default -C /var/dynamic-conf/fdb.cluster
```

The operator finds no backup running and starts one into the new,
empty container, storing the size. Abort only once: a second abort
stops the backup it just started.

### Restore testing, and what each check proves

A backup is not verified until it has been restored. The three
available checks prove strictly increasing amounts, and only the last
is about the data:

1. `just sop-fdb-list-backups <env> <label>` — the objects exist and
   the layout is right. Proves writing and nothing else: a wrong
   encryption key lists in a bucket exactly like a right one.
2. `fdbbackup describe`, which `just sop-fdb-describe <env> <label>`
   prints rather than runs, because it needs the backup image, the
   cluster file, the blobstore credentials and the key, so it runs in a
   pod and `exec` is break-glass. The only check reading the metadata
   *through* the key.
3. A restore into an empty destination. The only thing proving the
   backup carries what it claimed at the moment it claimed it, and what
   nobody has run.

A DR drill needs a witness rather than an empty bank: write a known
batch, let a snapshot pass, take that moment's version, write a second
batch, then empty and restore to the recorded version. Pass condition
is the first batch present and the second absent, which is what
separates "the backup can be read" from "the backup carries the right
point".

### Verifying a restore ran

`fdbrestore status` is the source of truth. The operator's
`FoundationDBRestore` resource reports `queued` for a restore that has
finished, which is why the chart's Job passes `-w` and blocks rather
than using the CR.

```bash
kubectl --context <code>-<env>-<label> -n queenswood exec -it \
  deploy/queenswood-fdb-backup-agents -- \
  fdbrestore status --dest-cluster-file /etc/fdb/fdb.cluster
```

`State: completed` with `LastError: None` is success; empty output
means no restore was ever submitted. To check the data rather than the
status, count keys against what you expect:

```bash
kubectl --context <code>-<env>-<label> -n queenswood exec -it \
  deploy/queenswood-fdb-backup-agents -- \
  fdbcli -C /etc/fdb/fdb.cluster \
  --exec 'getrangekeys "" \xff 100000' | grep -c '^`'
```

### Keycloak recovers with it

FoundationDB records reference Keycloak subjects, so recovering one
without the other leaves a user who exists in the realm with no party
in the bank, and onboarding gives them a second one. Every scenario
above that touches FDB touches Keycloak, the zone move included.

The realm export runs hourly to
`keycloak/realms/<YYYY>/<MM>/<DD>/<HHMMSSZ>/`, with `LATEST` written
last and only once both realms and the manifest are there. The two
stores will not recover to the same instant and cannot be made to; what
matters is that the Keycloak restore preserves user ids, and that both
points are chosen together rather than one taken as given.

There is no separate restore step, because the operator's import
creates a realm and never overwrites it: whichever definition arrives
first wins permanently, so one Job chooses its source — the named
export, or the chart's committed definitions — before it creates
anything. Three things fail that Job rather than being worked around,
each because the quiet version is the expensive one. A named export it
cannot fetch never falls back to the committed definitions, since
falling back is precisely the silent duplication. A restore arriving at
a realm that already exists stops, because the operator cannot import
over it and a skip leaves user ids that no longer match FDB. And the
user ids and federated identity links are checked over the admin REST
API against the export just applied — the Google link is what makes a
returning user resolve to the restored account rather than minting a
new one.

Bootstrap is gated behind that import, for a reason easy to miss. A
rebuilt environment mints a fresh admin signing key and bootstrap
registers its public half on the `queenswood-admin` client; a realm
imported after that reverts the client to the key the export was taken
with, while the pods hold the new private half, and `private_key_jwt`
stops verifying.

### Requesting a restore

`fdb.restore` is chart values only. It is not on the instance XRD, so
it cannot be expressed in the installation manifest — a restore is an
edit to the instance's values, merged, and applied by Argo from merged
state. That is what makes leaving a version set safe: the request is
reviewable, and re-applying it changes nothing.

The Job carries no `ttlSecondsAfterFinished`, unlike the migrator,
because the completed Job *is* the record that this cluster has been
restored. Let it age out and a later reconcile would recreate it.

One latent disagreement worth knowing: the restore Job and the
migrator's `wait-for-restore` gate render on `version` *or*
`backupName`, while `rbac-bootstrap-wait.yaml` renders on
`bootstrap.enabled` or `version` alone. `bootstrap.enabled` defaults
true so the Role is always present in practice, but a restore-to-latest
with bootstrap disabled would leave the gate unable to read the Job it
waits on.

## Rules

**MUST:**

- Establish that data is actually lost or wrong before restoring.
  Scale-to-zero preserves the volumes, so `down` and `up` is not a
  recovery scenario and needs no restore point.
- Name a generation with every version, and open a new generation after
  recovering. `fdb.restore.backupName` is the source,
  `fdb.backup.backupName` is the destination, and after a recovery they
  differ.
- Quiesce writes before taking a restore point for a planned cluster
  rebuild,
  and take it with `sop-fdb-version-at` rather than modelling one.
- Read RPO off the restorable window rather than off
  `snapshotPeriodSeconds`. The mutation log fills between full copies,
  so the window is the real answer.
- Recover Keycloak alongside FoundationDB, preserving user ids.
- Annotate a hand-applied chart resource back into its release rather
  than deleting it. Deleting a `FoundationDBBackup` stops the backup
  and tears down its agents.
- Prove a restore with `fdbrestore status` and a key count, never with
  the Job's exit status and never with the `FoundationDBRestore`
  resource.
- Start the backup again in a new generation after any change to
  `fdb.version`, aborting the running one first, and read `Last
  complete log` rather than the first line of `fdbbackup status` to
  judge whether it is current.
- Test the recovery procedure on a schedule and record what it proved.
  A backup is not verified until it has been restored.
- Restore onto systems segregated from the source anywhere other than a
  test environment. The damaged data is the only record of what
  happened, and restoring over it commits before anything is verified.

**MUST NOT:**

- Read a completed restore Job as evidence a restore happened. Against
  a populated destination the successful branch is the one that does
  nothing.
- Read a bucket listing as evidence a backup is readable. A wrong
  encryption key lists exactly like a right one.
- Use restore-to-latest where the moment is known. It is for an
  unplanned failover with nothing recording where to stop.
- Set a `version` with no `backupName`. The render refuses it, and the
  refusal is the feature.
- Give whatever empties a destination a name sharing a word with
  `down`, or let it stay true across reconciles.
- Let a lifecycle rule delete inside FDB's prefixes. `fdbbackup expire`
  is the only thing that may delete; an object removed behind FDB's
  back leaves metadata describing something that is gone.

**MAY:**

- Leave `fdb.restore` set indefinitely after a recovery. It is a target
  rather than a mode, and the emptiness check plus the
  content-addressed Job name are what make that safe.
- Restore in place in a test environment. It is what the chart supports
  today, and running it is how the first evidence arrives that a backup
  can be read at all.

## References

- [ADR-0026](../../adr/0026-recovering-data-and-the-states-that-do-it.md)
  — why `down` no longer empties anything, the two shapes of recovery,
  and retention as one number in days
- [ADR-0022](../../adr/0022-cloud-foundation-and-environment-lifecycle.md)
  — off as a declared state, and why an instance's project is durable
- [external-secrets](external-secrets.md) — where the backup
  encryption key lives, and why it is never rotated
- [data-recovery](../../compliance/data-recovery.md) — the obligations
  this procedure exists to satisfy, and the ones it does not yet meet
- `justfiles/sop.just` — the read-side recipes, and what each proves
