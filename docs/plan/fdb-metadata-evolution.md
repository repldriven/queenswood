# Plan: evolve FDB record metadata instead of rebuilding it

## Context

The test instance came up from zero nodes and every service pod stayed
in `Init:1/2`. The second init container waits for the bootstrap Job,
the bootstrap Job waits for the migrator Job, and the migrator fails
on every run. The console loads, and every call to the API host
answers 502 or 503 because the API service has no ready endpoint.

The migrator's job is to save the record metadata the code carries
into FoundationDB. The Record Layer validates every save against the
metadata already stored, and two merged changes had made the two
incompatible:

- The `CashAccount` record lost a field. PR #626 reserved tag 20 in
  place of `gl_control_account_id`. The validator refuses a descriptor
  that drops a field an existing store was written with. Fixed in
  PR #635: the field is back, deprecated, and the record conversion
  drops it unconditionally.
- Seven indexes changed their key expression under the same name. Six
  idempotency-key indexes gained a leading `bank_id` in PR #609, and
  `LedgerAccount_by_bank_gl_account_code` gained a trailing `currency`
  in PR #634. The validator refuses a changed expression outright.
  `Party_by_idempotency_key` was added at the same time, which is
  allowed on its own.

Neither failure can be caught by the test suite as it stands. A test
cluster is empty, so there is no stored metadata to evolve from, and
every save is a first save.

The root of it is that the project has never evolved a schema against
a store holding data. The factory changed record shapes and indexes
freely, and nothing asked what a migration would have to do. The test
instance is the first environment whose data outlived a schema change,
and it will hit one of the rules below on every change from now on.

## What the Record Layer enforces

`FDBMetaDataStore.saveRecordMetaData` runs `MetaDataEvolutionValidator`
with its defaults. The rules that matter here:

- The new metadata's version must exceed the stored one. The fdb brick
  already treats the "must increase" rejection as "already current"
  and skips the save.
- A record type may gain fields and never lose one. Deprecate; never
  remove or reserve a tag that was ever written.
- An index keeps its key expression. With `allowIndexRebuilds` the
  expression may change, provided the index's `lastModifiedVersion` is
  higher than the stored one, which is what tells a store to rebuild
  it.
- An index that disappears must be listed as a former index carrying
  its subspace key, the version it was added at, the version it was
  removed at, and its name. Otherwise the save fails as "index missing
  in new meta-data".
- Without `allowIndexRebuilds`, every surviving index must keep exactly
  the `lastModifiedVersion` it was stored with.

A store that opens against metadata whose index versions moved marks
the affected indexes for rebuild. A small store rebuilds them inline on
open; a large one leaves them disabled until an `OnlineIndexer` runs.

## What the builder does today

`build-meta-data` in the fdb brick's `system/components.clj` rebuilds
the metadata from `system/fdb-record-types.yml` on every start. It sets
records from the file descriptor, primary keys, and indexes, and
nothing else:

- `RecordMetaDataBuilder.addIndex` assigns each index
  `lastModifiedVersion = ++version` in iteration order, so the first
  index is version 1, the next 2, and so on. The metadata version is
  the count of indexes.
- No index carries an explicit version, no former index is ever
  declared, and the validator runs with defaults.

So a changed expression fails, a renamed index fails as missing, and an
index added ahead of another shifts every later version and fails as a
changed `lastModifiedVersion`. The scheme only ever works against an
empty store.

## Design

Give the YAML an evolution model, and make the builder honour it.

- **Metadata version.** A top-level `version` in the YAML, bumped by
  hand on every change to records, primary keys or indexes, set on the
  builder with `setVersion`. The "already current" no-op stays as it
  is, and a save that should have happened but did not becomes
  visible: the version was not bumped.
- **Index versions.** Every index carries `added` and `modified`
  versions. `added` is the metadata version the index first appeared
  in; `modified` is the version its expression last changed in, and
  equals `added` until it does. The builder sets both on the `Index`
  before `addIndex`, so iteration order stops mattering.
- **Former indexes.** A `former-indexes` list per record type, each
  with `name`, `added` and `removed`, passed to `addFormerIndex`. A
  rename is a former entry plus a new index; a removal is a former
  entry alone.
- **Validator.** The migrator's save uses a validator built with
  `allowIndexRebuilds`. Nothing else is relaxed: a removed field, a
  missing former index or an unbumped version still fails, loudly, in
  the migrator and nowhere later.
- **This change's own migration.** The seven changed indexes keep
  their names and get `modified` set to the new metadata version, so
  the stored ones are rebuilt rather than replaced. `Party_by_...` and
  any other index that exists today gets `added` equal to the version
  it appeared in; for everything that predates this plan that is the
  version stored on the test instance, which the first step reads.

## Steps

1. Read the stored metadata version and per-index versions off the
   test instance, so the YAML's first explicit versions match what is
   stored. `FDBMetaDataStore.getRecordMetaData` from a REPL against
   the cluster file, or a one-off log line in the migrator.
2. Extend the YAML schema and `build-meta-data`: `version` at the
   top, `added` and `modified` per index, `former-indexes` per record
   type. Reject a YAML that omits any of them, so a new index cannot
   land without a version.
3. Configure the validator on the meta-store's save.
4. Write the evolution test in the fdb brick: save metadata built from
   an old YAML fixture, then from a new one, and assert that a bumped
   `modified` saves, a changed expression without a bump fails, a
   removed index without a former entry fails, and a removed proto
   field fails.
5. Fill in the versions for the current YAML, with the seven changed
   indexes marked modified at the new version.
6. Add a guard that runs in CI against the last `stable-*` tag: build
   metadata from the tag's YAML and protos, then from the working
   tree's, and run the validator between them. This is the check that
   would have failed PR #609, #626 and #634 before they merged.
7. Write the rule down: a recipe under `docs/recipes/code/` for a
   schema change, and a Rules bullet the idioms plugin distils. Proto
   fields are deprecated and never removed or reserved once written;
   an index expression change bumps `modified`; a removed index gets a
   former entry; every change bumps the metadata version.

## Applying it to the test instance

The migrator and bootstrap Jobs are named for a hash of their pod spec,
and the image tag is `latest`, so a new image does not change their
names and Argo sees them as synced. After the fix ships:

1. Delete the two Jobs on the instance:

   ```
   kubectl -n queenswood delete job \
     -l 'app.kubernetes.io/component in (migrator,bootstrap)'
   ```

2. Trigger a sync from the management plane. The application has
   automated sync with `selfHeal: false`, so a deleted Job is not
   recreated until a manifest changes or an operation is posted:

   ```
   kubectl -n argocd patch application <instance>-queenswood \
     --type merge \
     -p '{"operation":{"sync":{"prune":false,"syncOptions":["CreateNamespace=true","ServerSideApply=true"]}}}'
   ```

3. Follow the migrator's log until it completes, then the bootstrap
   Job, then the service rollouts. The API host answers something other
   than 502 or 503 when the API service has an endpoint again.

Both steps were run this way during the incident and work.

## Alternatives

- **Clear the instance's FoundationDB and save fresh metadata.**
  Restores service in minutes, discards the test data, and leaves the
  same wall in front of the next schema change. Not taken.
- **Rename every changed index instead of bumping its version.**
  Works under the default validator once former indexes exist, at the
  cost of a new subspace per change and an index name that no longer
  says what it indexes. Bumping is the Record Layer's intended path
  for a rebuild.

## Follow-ups found on the way, not in scope here

- `wait-for-fdb-cluster` proves the cluster-file ConfigMap has
  content, not that FDB answers. A one-shot Job with six retries can
  burn them all during a cold start.
- Jobs created while an instance is `down` sit pending and run the
  moment it comes `up`, ahead of what they depend on. An `up` should
  recreate them.
- No workload logs reach Cloud Logging in the test project, though
  logging is enabled and the agents run. The failed Jobs' logs were
  gone, and the failure had to be reproduced live to be read.
- The Keycloak ingress emits a translation warning on every sync
  because its Service is `ClusterIP`. Noise today, and it hides a real
  warning when one comes.

## References

- [ADR-0002](../adr/0002-foundationdb-record-layer.md) — the Record
  Layer decision, which says evolution flows through metadata versions
  the migrator manages
- [ADR-0026](../adr/0026-recovering-data-and-the-states-that-do-it.md)
  — why a destructive state is never the reflex
- [deployment](../recipes/infra/deployment.md) — the migrator and
  bootstrap Jobs and the init chain behind every service pod
- [lifecycle-transitions](../recipes/code/lifecycle-transitions.md) —
  the checklist a schema change already follows, which step 7 extends
- [fdb-record-types.yml](/components/resources/resources/system/fdb-record-types.yml)
  — the YAML the builder reads
