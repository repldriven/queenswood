# Recovery procedures

## Status

**Superseded** in its environment model, and accurate about its
mechanics.
It assumes `QUEENSWOOD_ENV`, `pass` and the `gcp-up` / `gcp-down` cycle
of the previous generation, none of which the current installation
has. How FoundationDB and Keycloak restore has not changed; where their
credentials and restore points live has.

The backup path is also being redesigned: an S3-to-GCS proxy replaces
the HMAC key this describes, because the org-policy exemption it needs
is not available in an organisation we do not own. See
[the plan](../../plan/cloud-just-migration.md).

The FoundationDB half is superseded by
[fdb-recovery](fdb-recovery.md), which covers the same ground for the
current installation: the recovery scenarios, their RPO, and what to
set for each. What remains authoritative here is Keycloak.

This document carries no plugin label, so nothing here becomes a rule.

## What to run

What to run when data has to come back. Both stores restore the same
way — a target recorded at teardown, acted on before anything writes —
so the routine cycle needs no commands at all.

Everything here assumes `QUEENSWOOD_ENV` is set to the environment you
mean, since every recipe keys `pass` and the GCP project off it.

## The routine cycle: nothing to run

A teardown and rebuild restores itself.

```bash
just gcp-down    # records what to restore, while it can still be known
just gcp-up      # the rebuilt environment restores to it
```

`gcp-down` records two things: FDB's restore version, and the prefix of
the Keycloak realm export it took. `gcp-up` re-renders the root
Application from both, and each store's import acts before its own
writer runs. The pair is what matters — FDB references the Keycloak
subject, so restoring one without the other leaves a bank whose users
no longer resolve.

Both exports run **before** the workload namespace is drained, because
both read from a live cluster: FDB through the backup agents that live
in that namespace, Keycloak through a Job running `kc.sh export` on its
own image. `gcp-fdb-export` waits for the backup to be restorable,
stops it cleanly so the last mutations land, and writes the version to
`<env>/backup/fdb-restore-version` in `pass`. `gcp-keycloak-export`
takes a final export on top of the hourly scheduled one and writes its
prefix to `<env>/backup/keycloak-restore-realms`. Either refuses to
continue if what it produced is not restorable — draining past that
point is what makes the data unrecoverable.

`gcp-up` re-renders the root Application with that version, and the
chart's restore Job acts on it before the migrator writes anything.

## Restoring FDB to a chosen point

When the newest data is the problem, or no teardown ran.

1. List what exists:

   ```bash
   just gcp-fdb-restore-points
   ```

   Any version between `oldest` and `newest` works, not only the
   snapshots it lists — the mutation log fills the gaps.

2. Record the version you want:

   ```bash
   pass insert -e -f "queenswood/gcp/$QUEENSWOOD_ENV/backup/fdb-restore-version"
   ```

3. Rebuild. The restore only runs against an empty cluster, so the
   data has to go first:

   ```bash
   just gcp-down
   just gcp-up
   ```

   `gcp-down` overwrites the version you just set with its own. To keep
   yours, run `gcp-down`, set the version again, then `gcp-up`.

## Applying a version to a plane that is already up

Only needed when changing the version without a rebuild — after a
`gcp-up` that predates the value, for instance.

```bash
just kind-xp-install-root
```

This re-renders the root Application from `pass` and Argo propagates
it down to the chart. Safe on a running cluster: the restore Job finds
the cluster non-empty and exits without doing anything.

## No recorded version and no backup agents

If the cluster is gone, `gcp-fdb-restore-points` has nothing to exec
into. Bring the environment up first, empty, then use the steps above:
the restore Job no-ops on the way up, agents start, and you can list
points and rebuild once more.

## Keycloak

Same shape as FDB, and stricter for a reason FDB does not have. The
operator's realm import creates realms and never overwrites them, so
whichever definition reaches a realm first wins permanently. There is
no second chance to correct it.

That is why restore is not a separate step. One Job imports the realms
in every environment, and it chooses its source — the named export, or
the chart's committed definitions — before it creates anything. A
restore layered on top of a declarative import would always arrive too
late.

What it protects is identity. FDB references the Keycloak subject, so a
realm rebuilt from the committed JSON mints new user ids and the bank
silently duplicates itself around them: a restored bank sits in FDB,
unreachable, while a second one is built alongside it on first login. A
missing realm is obvious; an orphaned bank is not.

Once per project, create the identity the import reads exports with:

```bash
just gcp-keycloak-restore-sa-create
```

To restore a chosen export rather than the one `gcp-down` recorded:

```bash
just gcp-keycloak-restore-points        # last 5; pass a count for more
pass insert -e -f \
  "queenswood/gcp/$QUEENSWOOD_ENV/backup/keycloak-restore-realms"
```

The value is the prefix it prints, not an object —
`keycloak/realms/2026/08/05/161016Z`. A `*` marks the one `gcp-down`
last recorded.

Three things fail the Job rather than being worked around, each because
the quiet version of it is the expensive one:

- **A named export that cannot be fetched.** It never falls back to the
  committed definitions, because falling back is precisely the silent
  duplication.
- **A restore arriving at a realm that already exists.** The operator
  cannot import over it, and a skip here leaves a realm whose user ids
  no longer match FDB. A completed restore is recorded on the realm
  itself, so re-running against an already-restored realm stays quiet.
- **User ids or federated identity links missing after the import.**
  Both are checked over the admin REST API against the export the Job
  applied. The Google link is what makes a returning user resolve to
  the restored account rather than minting a new one.

Order matters for a reason that is easy to miss. A rebuilt environment
mints a fresh admin signing key and bootstrap registers its public half
on the `queenswood-admin` client. Importing a realm after that reverts
it to the key the export was taken with, while the pods hold the new
private half, and `private_key_jwt` stops verifying. Bootstrap is
therefore gated behind the realm import, and re-registers the current
key onto whichever realm was imported.

Google sign-in needs one more thing that no chart carries: the realm's
committed JSON holds a placeholder client id and secret, so `gcp-up`
runs `just gcp-keycloak-idp google` to push the real pair in. Without
it Google answers `401 invalid_client`, which on a restored environment
is indistinguishable from the restore itself having failed.

## When the deployment appears to hang

Either restore blocks the chain by design, so a stuck one looks like a
stalled deploy. Services wait on bootstrap, which waits on both the
migrator and the Keycloak import, and the migrator waits on the FDB
restore.

```bash
kubectl -n "$QUEENSWOOD_ENV" get jobs
kubectl -n "$QUEENSWOOD_ENV" logs \
  -l app.kubernetes.io/component=fdb-restore
kubectl -n "$QUEENSWOOD_ENV" logs \
  -l app.kubernetes.io/component=keycloak-realm-import
```

Neither gate has a timeout escape, on purpose. Both writers are
destructive to the thing being restored: saving record metadata makes
FDB non-empty and it refuses to restore into that, and registering the
admin signing key is what a late Keycloak import would undo. A gate
that gave up and ran anyway would not be carrying on safely.

To abandon the restore and boot empty, clear the version and re-render:

```bash
pass rm "queenswood/gcp/$QUEENSWOOD_ENV/backup/fdb-restore-version"
just kind-xp-install-root
kubectl -n "$QUEENSWOOD_ENV" delete job \
  -l app.kubernetes.io/component=fdb-restore
```

## Verifying a restore

`fdbrestore status` is the source of truth. The operator's
`FoundationDBRestore` resource reports `queued` for a restore that has
already finished, so do not gate on it.

```bash
POD=$(kubectl -n "$QUEENSWOOD_ENV" get pods -o name \
  | grep fdb-backup-agents | head -1 | cut -d/ -f2)
kubectl -n "$QUEENSWOOD_ENV" exec "$POD" -c foundationdb -- \
  fdbrestore status --dest-cluster-file /var/dynamic-conf/fdb.cluster
```

`State: completed` with `LastError: None` is success. Empty output
means no restore was ever submitted. To check the data rather than the
status, compare key counts against what you expect:

```bash
kubectl -n "$QUEENSWOOD_ENV" exec "$POD" -c foundationdb -- \
  fdbcli -C /var/dynamic-conf/fdb.cluster \
  --exec 'getrangekeys "" \xff 100000' | grep -c '^`'
```

For Keycloak, the Job says what it did — which source each realm came
from, and for a restore the count of user ids it confirmed intact:

```bash
kubectl -n "$QUEENSWOOD_ENV" logs \
  -l app.kubernetes.io/component=keycloak-realm-import
```

## Helm refuses to upgrade after manual intervention

Applying chart resources by hand — during an incident, say — stops the
next Helm upgrade with `invalid ownership metadata`. Helm will not
import a resource it did not create.

```bash
kubectl -n "$QUEENSWOOD_ENV" annotate <resource> \
  meta.helm.sh/release-name="$QUEENSWOOD_ENV" \
  meta.helm.sh/release-namespace="$QUEENSWOOD_ENV" --overwrite
```

Annotate rather than delete: deleting a `FoundationDBBackup` stops the
backup and tears down its agents.

## Related

- [cloud-deployment](cloud-deployment.md) — the tiers, the teardown
  order, and the credential taxonomy the backup keys live in
- [ADR-0016](../../adr/0016-crossplane-over-terraform.md) — why the
  management plane applies any of this at all
