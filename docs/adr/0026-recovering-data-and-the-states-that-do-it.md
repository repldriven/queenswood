# 26. Recovering data, and the states that do it

<!-- tessl-plugin: deployment -->

## Status

Proposed. Extends
[ADR-0022](0022-cloud-foundation-and-environment-lifecycle.md), which
made off a declared state and an instance's project durable, and
[ADR-0024](0024-instances-are-their-own-composites.md), which made an
instance its own composite. Nothing here is built.

It records a gap first and a direction second. The gap is real now:
FoundationDB is backed up continuously and there is no defined way to
use a backup.

## Context

The previous generation recovered data by destroying it. Its recipe
says so plainly — *the restore only runs against an empty cluster, so
the data has to go first* — and the two commands that did it,
`gcp-down` and `gcp-up`, tore an environment down and built it back.
The restore Job was then the thing that filled the empty cluster, which
is why it is a build-time gate: the migrator waits for it, bootstrap
waits for the migrator, and every service waits for bootstrap.

ADR-0022 removed the first half of that cycle on purpose. An instance's
project is durable, and `down` is *the node pool at zero* — the
cluster, the project, its data, the network and the identities all
stand. What stops is what costs money.

So the destructive act that the restore depended on no longer exists,
and nothing replaced it. Setting `fdb.restore.version` on a running
instance does nothing at all: the Job checks emptiness itself and
leaves a cluster that holds data alone. That check is right — it is
what stops a reconcile destroying live data — but it means the field
that names a restore point cannot cause a restore.

The result is an installation that takes backups faithfully and has no
procedure for reading one back. Backups nobody has restored are a
belief rather than a control, and the belief is cheapest to correct
before there is anything in the bank worth losing.

## Decision

**Down and rebuild are different kinds of state, and must not share a word.**
`down` is reversible and preserves what it stops. Whatever replaces the old
destructive cycle is irreversible and destroys what it replaces. Both are
legitimately "a declared state" in the sense ADR-0022 means, and putting them in
one enum is how someone eventually recovers an environment by selecting the
wrong value from a list.

The decision has further parts:

- **Recovery has two shapes and they are for different situations.**

  *In place.* Empty the data and let the restore fill it. It is what the chart
  already supports and it needs `clusterAdmin`, which is right — recovering from
  corruption is break-glass. It is correct when the current data is worthless: a
  test environment, or a rebuild after total loss.

  *Beside it, then cut over.* Build a second instance, restore it to a point
  before the damage, verify what it holds, move traffic. It is heavier and needs
  a cutover story this model does not have.

- **For corruption, restoring beside it is the one to build toward**, and not
  for convenience. The corrupted data is evidence — the first act of the
  in-place path destroys the only record of what happened and when. And it is
  reversible until the cutover, where in place commits at the moment the volumes
  go: a restore that comes back wrong, or to the wrong point, has nothing behind
  it.

- **A destructive state must be self-limiting.** The restore Job embeds its
  version in its own name, so re-applying the same value resolves to a Job that
  has already completed and the restore does not run twice. Anything that
  empties data needs that property or something as strong. A field meaning
  "destroy this and rebuild it" that stays true is a field that destroys on
  every reconcile, which is the hazard ADR-0022 names from the other end — *a
  live plane watching its resources vanish through a prune and doing what it was
  told.*

- **Retention is one number, expressed in days, and everything else is derived
  from it.** An installation says how far back it can recover — thirty days —
  and nothing else is stated.

  Days because that is the unit the tool consuming it uses:
  `--delete-before-days` and `--min-restorable-days` both take days, so any
  other unit would be converted on the way in, which is the arithmetic this
  decision exists to remove. It is also the unit the question is asked in —
  nobody wants to recover to 2,592,000 seconds ago. `snapshotPeriodSeconds`
  beside it is in seconds for the same reason and not from inconsistency: a
  snapshot period is a thing you tune in minutes and FDB takes it in seconds.
  The unit follows what reads the value, not a house style.

  `fdbbackup expire` takes a cutoff and a floor as separate flags, and two flags
  that must agree is how a configuration ends up cutting at thirty and
  guaranteeing seven, which nobody notices until the day it matters. Both come
  from the one value.

  That the two are the same number is what makes the floor useful rather than
  decorative: it stops being a second decision and becomes a check on the first.
  Expire deletes what is not needed to restore across the window, and the floor
  asserts the window survived. Where FDB's two approximations of *approximately
  NUM_DAYS worth of versions* disagree at the boundary, expire refuses and that
  run deletes nothing — which is the direction a destructive operation should
  fail in, and it corrects itself on the next one.

- **States belong to parts, not only to instances.** `state: up | down` is
  instance-wide, and the parts of an instance have independent lifecycles: the
  data tier, the database behind Keycloak, the services. Recovering data should
  not require declaring the whole environment off, and the parts that can be
  stopped independently are the ones that can be recovered independently.

## Consequences

**A restore point is a version, and nobody thinks in versions.** This
was going to be the consequence that decided whether any of it could be
used under pressure, and it turns out to be answerable from the bucket
alone. A continuous backup ships its mutation log as objects named for
the version range they carry, and GCS records when each was written —
so the object written just after a moment names a version just before
it, and `sop-fdb-version-at` reads it off the listing.

No arithmetic and no assumed rate, which is what makes it trustworthy.
The rate is real — measured against 127 log objects over 42 minutes,
FDB advances 998,940 versions a second with r² of 0.999954, against a
documented million — but a procedure that computed from it would be a
model that could be wrong about a cluster nobody was watching. Reading
the answer off the objects cannot be.

What remains is that the answer is only as old as the log. Ask for a
moment before the backup began and there is nothing to name, which the
recipe says rather than extrapolating into a version that never
existed.

**A restored cluster writes to a new generation, or it poisons the one
it read from.** FoundationDB numbers versions from near zero on a
rebuild, so a restored cluster's first versions are *lower* than the
ones already in the container — and `fdbbackup describe` picks the
highest restorable version, which is the dead cluster's. The chart
records this from the last time it happened: a restore point belonging
to a cluster two rebuilds back. So `fdb.restore.backupName` names where
to read and `fdb.backup.backupName` names where to write, and after a
recovery they must differ. Nothing enforces that today, and both
default to `fdb/continuous`.

That makes a generation the unit of the whole procedure rather than a
path segment: recovering means reading from one and starting another,
and the bucket accumulates them. It also settles what the naming means
— `<service>/<backup-type>/<generation>` has a slot for it precisely
because a container holds one cluster's life.

**Cutting over is unbuilt, and larger than it looks.** DNS, the realm
the console signs into, the Keycloak user ids that FDB records
reference. ADR-0024 already defers `draining` for a related reason —
an unattended Keycloak restore is its precondition and is not met.

**Nothing expires anything, and the bucket grows without bound.**
Measured on the test instance: 195 objects an hour, of which 144 are
mutation-log files carrying a twenty-million-version range and, with
nobody writing to the bank, zero bytes. That is around 4,700 a day and
1.7 million a year. The bytes are nil; the count is not, and every
listing walks it.

The lifecycle rules deliberately do not touch FDB's prefixes, and must
not. A continuous backup's older objects are not stale copies but the
log a restore reads through, and an object deleted by GCS behind FDB's
back leaves metadata describing something that is no longer there. So
`fdbbackup expire` is the only thing that may delete, and running it
needs the backup image, the credentials and the proxy — which is to
say, a scheduled job in the instance, not a bucket rule.

**Retention is a recovery decision wearing cleanup's clothes.**
Expiring everything older than thirty days is not tidying: it is
declaring that this installation cannot restore to thirty-one days ago,
which is a statement about what the bank can recover from and belongs
beside the others here rather than in a values file nobody reads twice.
It deletes rather than marks — `fdbbackup expire --help` says so of
both cutoffs, *deletes data files containing no data at or after* a
version — so on a blobstore container it is one DELETE per object and
there is nothing to sweep up afterwards.

Whatever runs it inherits the rule two sections up, and FDB already
supplies the mechanism: `--min-restorable-days`, and the
`--restorable-after` pair, set a floor below which expire refuses. That
is the self-limiting property, built in rather than invented, and a
schedule that omits it is a schedule that can be told to delete
everything. `--delete-before-days` is the cutoff it pairs with, and it
is anchored to the latest log version in the backup rather than to wall
clock, so it cannot misfire on a clock nobody checked. Neither is a
value an installation sets: both come from the retention period, per
the decision above.

How often it runs is a smaller question than it looks, and answerable
later with evidence rather than now by guess. Each run deletes what has
aged out since the last, so the interval sets the batch size — a day is
around 4,700 objects, an hour around 195 — while the cost of deciding
what to delete is a pass over the container either way, which favours
running less often. Nothing here is urgent: a backup started today has
nothing thirty days old, so the first run that removes anything is a
month away, and the cadence can be chosen from what the first one
costs.

**Keycloak and FoundationDB recover to different points.** Restoring
one and not the other leaves a user who exists in the realm with no
party in the bank, and onboarding gives them a second one.

**The in-place path stays available and stays undocumented until
someone writes it down.** It is what a test environment should use, and
testing it is how the first evidence arrives that a backup can be read
at all.

## References

- [ADR-0022](0022-cloud-foundation-and-environment-lifecycle.md) — off
  as a declared state, and why an instance's project is durable
- [ADR-0024](0024-instances-are-their-own-composites.md) — the instance
  as its own composite, and what `down` leaves standing
- [the plan](../plan/cloud-just-migration.md) — where the backup path
  was rebuilt
