# The identity that builds installations

<!-- tessl-plugin: deployment -->

## Status

**Verified in part.** The seed project and its grants have been created
this way; closing and reopening the identity has not been exercised
across two installations.

## Problem

You want an identity that can create folders and projects on an
organisation's behalf, so that everything after this is a file rather
than a person clicking.

## Solution

### Prerequisites

- An organisation and a billing account, from
  [organisation-foundation](organisation-foundation.md).
- Membership of the organisation's billing-admin and org-admin
  capabilities, joined for a step and left afterwards.

Done once for an organisation rather than once per installation. Where
one already exists, skip to step 3 and reopen it.

### 1. Check what you can reach

**As any account in the organisation.**

```bash
just seed-preflight
```

The organisation, the billing account, your own direct roles, and the
parents you may create under, ending in `nothing blocking` or a
`BLOCKED:` list and a non-zero exit.

Roles reported as `none bound directly` or `not readable by this
account` block nothing.

### 2. Create the seed project and the seed identity

**As an org project admin and billing admin.** Ours are
`grp-gcp-project-admin@` and `grp-gcp-billing-admin@` — join both for
this step, then leave. The seed project is created at the organisation
and linked to an account, which are two capabilities.

```bash
just seed-create
```

The seed project, the service account in it, `billing.user` on the
billing account, and the platform-admin group allowed to impersonate
it. It reuses a project labelled `queenswood-tier=seed` where one
exists.

### 3. Grant it its organisation roles

**As an org admin.** Ours is `grp-gcp-org-admin@` — join for this
step, then leave.

```bash
just seed-grant-org-roles
```

Where an organisation hands you a folder, these are held on that folder
and granted by whoever owns it.

### 4. Close it when the bootstrap is done

**As an org admin.**

```bash
just seed-close
```

It removes the platform-admin group's `serviceAccountTokenCreator` and
revokes `folderCreator`, `folderIamAdmin` and `projectCreator`.
`orgpolicy.policyAdmin`, `cloudasset.viewer` and `browser` stay.

To bootstrap again, reopen with `just seed-open` and `just
seed-grant-org-roles`.

## Failures

**`seed-preflight` exits immediately with no output.** `set -e`
aborting before the recipe's first `echo`, not a check that passed.

**A second seed project appears.** The label was missing from the
first, so nothing recognised it. Seed projects keep their
random-suffixed ids and are retained rather than deleted, so the
duplicate is permanent.

## Rules

**MUST:**

- Read what you can reach with `just seed-preflight` before choosing
  between creating a folder and adopting one.
- Create the identity with `just seed-create`, which reuses the seed
  project where one exists rather than minting a second.
- Grant it `folderCreator` and `folderIamAdmin` on the parent with
  `just seed-grant-org-roles`, never `folderAdmin`, so it cannot delete
  a folder.
- Close the identity with `just seed-close` once a bootstrap is done
  and reopen it for the next with `just seed-open`. Its organisation
  grants otherwise stand for ever, and nothing after the bootstrap
  needs them.
- Impersonate it with `just seed-impersonate` rather than holding a
  credential.

**MUST NOT:**

- Create a key for the seed identity, or for any identity an
  installation composes.
- Grant a person `serviceAccountTokenCreator` on it outside a
  bootstrap.
- Delete the seed project. Its id is consumed and it is reused.

**MAY:**

- Skip this entirely where an organisation hands you a folder and an
  identity able to create projects in it. This recipe is how we produce
  one, not what an installation requires.

## Discussion

The seed exists because creating a folder is checked on the parent, and
a parent is the organisation or a folder above. Nothing inside an
installation can grant itself that, so something outside has to, once,
and then stop.

It belongs to the organisation rather than to an installation, which is
why it is a step of its own and why its project carries no installation
code. One seed builds as many installations as an organisation wants,
opened for each and closed after.

Closing it is the point rather than tidiness. Between bootstraps the
rights it holds are the rights to create folders and projects anywhere
in the organisation, held by an identity a group can impersonate. The
plane that succeeds it needs none of them.

## References

- [organisation-foundation](organisation-foundation.md) — the organisation
  and the billing account this grants against.
- [plane-install](plane-install.md) — what impersonates
  it, and the plane it builds.
- [gcp-iam](gcp-iam.md) — why a granted identity is not an inherited
  one.
