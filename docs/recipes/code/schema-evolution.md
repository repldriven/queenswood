# Schema evolution

<!-- tessl-plugin: design -->

## Status

**Untested** against a store holding data. The rules are exercised by
the fdb brick's meta-data tests against a testcontainer and by the
migrator's guard, and the test instance is the first to be evolved
rather than rebuilt.

## Problem

You are changing what FoundationDB stores — a proto field, a record
type, a primary key, an index — and an installation already holds
data written under the current shape. The Record Layer validates every
save of record meta-data against what is stored and refuses a change it
cannot carry forward: a removed field, an index whose key changed under
the same name, an index that disappeared. A test cluster is empty, so
the suite never meets that validator. The migrator does, on the
instance.

## Solution

The declaration in
[fdb-record-types.yml](/components/resources/resources/system/fdb-record-types.yml)
carries the meta-data `version`, and each index the version it was
`added` at and the version its key last changed at, `modified`. A store
lists the indexes removed from it under `former-indexes`. The migrator
saves with a validator that allows index rebuilds and refuses
everything else, and the guard runs the same validator between the last
`stable-*` tag's meta-data and the working tree's.

### Prerequisites

- The change to the protos under `components/schema/resources`, or to
  the declaration, made.
- `protoc` on the path, and the `stable-*` tags fetched.

1. Bump `version` at the top of the declaration by one.

2. On each index whose key, type or uniqueness changed, set `modified`
   to the new version, leaving its name and its `added` alone.

3. On each index added, set `added` and `modified` to the new version.

4. For each index removed or renamed, add an entry under its store's
   `former-indexes` carrying its `name`, its `added`, and `removed` at
   the new version. A rename is a former entry and a new index.

5. Keep a proto field that is no longer wanted, with its tag, marked
   `[deprecated = true]`, and drop it in the record conversion.

6. Run the guard:

   ```bash
   just test-schema-evolution
   ```

   It builds the meta-data from the last `stable-*` tag's declaration
   and protos, then from the working tree's, and validates the second
   as an evolution of the first. The output ends in `0 failures`.

7. Run the tests of every brick whose store changed.

## Failures

- **`index key expression changed`, naming an index** — its key changed
  and `modified` did not. Set `modified` to the new version.
- **`index missing in new meta-data`** — an index was removed or
  renamed with no former entry. Add one under its store.
- **`new meta-data does not have newer version than old meta-data`** —
  something changed and `version` did not. Bump it.
- **`new index has version that is not newer than the old meta-data
  version`** — a new index carries a version at or below the stored
  one. Set its `added` and `modified` to the new version.
- **`new index added version does not match old index added version`**
  — `added` moved on an index the store already holds. Put it back.
- **`field removed from message descriptor`** — a proto field was
  removed or its tag reserved. Restore it and deprecate it.
- **`meta-data changed without a version bump`, from the migrator** —
  the declaration differs from what is stored at the same version.
  Bump `version` and mark what changed.
- **`stored meta-data is newer than this code's`, from the migrator** —
  an older image is deploying over a store that has moved on. Deploy
  the image that moved it, or a newer one.
- **The guard passes and the migrator refuses the same change** — the
  instance's stored meta-data predates the last `stable-*` tag. The
  migrator logs the stored version and every index's versions before it
  saves. Reconcile the declaration against that log.

## Rules

**MUST:**

- Bump `version` in the declaration on every change to a record type,
  primary key or index, and run `just test-schema-evolution` before
  pushing.
- Set `modified` to the new version on an index whose key, type or
  uniqueness changed, keeping its name and its `added`.
- Give a new index `added` and `modified` equal to the new version.
- List a removed or renamed index under its store's `former-indexes`
  with its `name`, its `added` and the version it was removed at.
- Deprecate a proto field that is no longer wanted, keeping its tag,
  and drop it in the record conversion.

**MUST NOT:**

- Remove a proto field, or reserve its tag, once a record has been
  written with it.
- Change an index's `added`, or give a new index a former index's
  name.
- Rename an index in place of bumping `modified`.
- Clear a store's meta-data to make a refused save land.

## Discussion

The declaration is the register of every version the store has been
through. Before it carried versions, the builder numbered indexes in
the order it met them, the meta-data version was the count of indexes,
and the migrator read the Record Layer's "version must increase" as
"already current" and skipped the save. An index added ahead of another
renumbered every later one, a changed key failed under its own name,
and a change that kept the count was never saved at all. No test could
see any of it, because a test store is empty and every save into it is
the first.

The Record Layer holds a save to five rules, and the declaration gives
each one something to check. The version must rise. A record type may
gain fields and never lose one, since a stored record may carry any
field ever written. An index keeps the version it was added at, and its
key may change only with `modified` raised, which is what a store reads
to know the index needs rebuilding. An index that disappears must be
listed as former, with its versions, so a store can delete what it
built. A former index's name is never reused, because its subspace is
the name.

The migrator refuses what the old builder skipped. At the stored
version, the declaration must be identical to what is stored, so a
change without a bump fails the Job. Below the stored version, an older
image is being deployed over a store that has moved on, and its
services would fail to open the stores anyway.

**Known limitations.** A store opening against meta-data whose index
versions moved rebuilds a small index inline and leaves a large one
disabled until an `OnlineIndexer` runs. Nothing in the migrator runs
one, so an index rebuilt on a store past a few hundred records stays
disabled.

The guard's baseline is the last `stable-*` tag rather than an
instance, because it is the one thing every checkout can reach. The
chain composes: the version only rises, `added` never moves and a
former entry never leaves, so a working tree that evolves from the tag
before it evolves from every tag before that. An instance whose stored
meta-data fell behind the tags is what the migrator's log line is for.

## References

- [ADR-0002](../../adr/0002-foundationdb-record-layer.md) — the Record
  Layer, whose meta-data versions the migrator manages
- [lifecycle-transitions](lifecycle-transitions.md) — the checklist a
  new state or transition follows, whose proto step this extends
- [code-generation](code-generation.md) — regenerating the descriptor
  after a proto change, with `:force true`
- [deployment](../infra/deployment.md) — the migrator Job that saves
  the meta-data, and the init chain waiting on it
