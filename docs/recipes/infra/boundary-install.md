# An installation's boundary

<!-- tessl-plugin: deployment -->

## Status

**Untested.** `qw01`'s manifest was written by hand, and its folder
created by an earlier bootstrap and adopted afterwards.

## Problem

You want to declare an installation's boundary.

## Solution

### Prerequisites

- The installation's code, chosen and never changed, from
  [cloud-naming](../practices/cloud-naming.md).
- Its contract committed — [contract-install](contract-install.md).
- In that file, either a `folder.parent` and `folder.displayName`, or a
  `folder.folderId`.
- Write access to the manifests repository, and a merge.

```bash
# the installation code, e.g.
export QW_CODE=qw01
# the private manifests repository, wherever it is checked out
export QW_INSTALLATIONS_REPO=../installations
```

### 1. Read the folder block from the contract

```bash
grep -A3 '^  folder:' "$QW_INSTALLATIONS_REPO/$QW_CODE/environment.yml"
```

Either a `folderId`, or a `parent` and a `displayName`. Neither means
the contract is unfinished: go back to
[contract-install](contract-install.md).

### 2. Render the manifest

```bash
just queenswood-subsidiary-manifest \
  > "$QW_INSTALLATIONS_REPO/$QW_CODE/subsidiary.yml"
```

One key under `spec`, the code, at the top of the installation's
directory rather than inside it.

### 3. Read it, then commit it

```bash
cat "$QW_INSTALLATIONS_REPO/$QW_CODE/subsidiary.yml"
```

## Failures

**`Unsynced resources: folder`, while the folder reports `Synced`.**
The apply is being rejected rather than the folder being wrong. Read
the composite's events for the reason — a `Folder` carrying `Create` or
`Update` in `managementPolicies` and no `displayName` is invalid, and
the provider says so on every apply while the composite reports only
that something is unsynced.

**Two folders under the parent.** `folderId` was absent when the
contract still named a `parent` and a `displayName`, so the composite
composed rather than adopted. GCP permits two folders with one display
name under a parent, so nothing refuses it. Count them before trusting
a composite that reports healthy.

## Rules

**MUST:**

- Commit the contract before this. The boundary reads `access` and
  `folder` from it and composes neither the bindings nor the folder
  without them.
- Render the manifest with `just queenswood-subsidiary-manifest`, at
  the top of the installation's directory and never inside a
  subdirectory of it.
- Prove an adoption by counting folders under the parent rather than by
  reading the composite, which reports healthy either way.

**MUST NOT:**

- Name a principal in the contract's `access` that does not exist. IAM
  rejects the binding, not the file.
- State `parent` and `displayName` beside a `folderId` and expect them
  to apply. They are ignored, and removing the `folderId` line later
  leaves them to compose a second folder.

**MAY:**

- Re-render this manifest at any time. It carries nothing generated.
- Declare a boundary with an empty `access` mapping, which reconciles
  correctly and which nobody can reach.

## Discussion

We declared the folder as its own kind, so composing one and adopting
one differ by a field rather than by a procedure. The folder is what an
installation is, so that field is the whole handover: the same object
is left behind whether we made it or somebody handed it over.

The manifest carries the code alone. It is the XR: applying it
instantiates the kind, and everything the kind needs is stated once in
the contract, where the plane and every instance read it too. Nothing
in it is generated, so it may be re-rendered freely — which the
contract beside it may not, since that one carries the folder id.

It sits at the top of the installation's directory because the
Application that syncs it does not recurse: a manifest filed in a
subdirectory is never applied at all, and nothing reports that.

The two modes are exclusive by precedence rather than by refusal. Where
`folderId` is set the other two are ignored, so stating all three is
inert rather than an error, which is the better failure: the contract
is read by three composites, and rejecting it would stop the plane and
every instance rather than this alone.

An adopted folder is observed and never written. `folderId` withholds
`Create` and `Update` from the composed `Folder`, so the provider
cannot rename it or move it whatever the spec says. An empty
`displayName` renames a folder and an empty `parent` moves it. See
[ADR-0027](../../adr/0027-the-folder-is-a-subsidiary.md).

## References

- [contract-install](contract-install.md) — the contract, and the
  principals it names.
- [plane-install](plane-install.md) — the plane
  that applies this, built inside the boundary afterwards.
- [up-and-running](up-and-running.md) — where this sits in the order.
- [ADR-0027](../../adr/0027-the-folder-is-a-subsidiary.md) — the folder
  as its own kind, and the handover in either direction.
- [ADR-0023](../../adr/0023-installation-naming-and-access.md) — the
  capabilities bound here, and who holds them.
