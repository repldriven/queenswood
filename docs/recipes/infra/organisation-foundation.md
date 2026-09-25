# An organisation's secure foundation

<!-- tessl-plugin: deployment -->

## Status

**Untested as written.** The organisation this platform runs in was set
up this way, and nobody has worked down this page to create a second
one. Expect the first run to find an ordering stated wrongly rather than
a step omitted.

## Problem

You have no Google Cloud, and you want an organisation, a billing
account, and no person holding a standing right in either.

## Solution

Everything below is one act done once, however many installations
follow. What it produces is not a set of groups but a set of
capabilities, each held by nobody until somebody joins for a task.

> [!WARNING]
> In an established Google Cloud organisation, this recipe is not yours.
> Those capabilities are already held some other way, by people who are
> not you, and your directory is not yours to add groups to. Go to
> [contract-install](contract-install.md), which
> is where an installation's own capabilities are named, and answer each
> with whatever your organisation gives you.

### Prerequisites

- A domain, with access to edit its records at the registrar.
- A recovery email and phone for the admin account. It gets no mailbox,
  so it cannot receive its own password reset.
- Steps 1 to 5 — a private browser window, with no Google account signed
  in to it.
- Step 6 — a terminal, signed in as the admin account.
- Step 7 — an ordinary browser window, as a user on your own domain.
- No Google Cloud access and no group membership. This recipe creates
  every identity it uses.

```bash
# the domain the organisation is created against, e.g.
export QW_DOMAIN=example.com
```

### 1. Sign up for Cloud Identity Free

In the private window, at
`workspace.google.com/gcpidentity/signup?sku=identitybasic`. Google
moves that page; where it steers you to a paid Workspace plan, find the
free Cloud Identity edition instead.

Use `admin@` on your domain for the admin. It is a new Google account,
unrelated to your existing one.

### 2. Verify the domain

Google may hand off to your registrar and add the TXT record itself.
Success reads "You're all set to use Google Workspace apps".

### 3. Check the edition

`admin.google.com`, **Billing** then **Subscriptions**. It should read
Cloud Identity Free, 50 licences.

Turn on 2-step verification here, and store the password and the backup
codes. This account is the root of trust for everything below.

### 4. Create the organisation

Sign in to `console.cloud.google.com` as the admin and accept the terms.
The organisation appears on that first sign-in — no project is needed —
and the console grants the admin Organization Administrator in the same
action.

### 5. Create the access groups

In `admin.google.com` under **Directory, then Groups**. Each carries one
capability, and the descriptions below are worth pasting in — a group
whose purpose is not written down acquires members. A description says
what holding the capability lets you do, and never which roles implement
it: those change in a pull request, and nothing goes back to correct a
field in the directory. The display name is the address, so one string
appears on every screen and the console sorts by scope.

- **`grp-gcp-org-admin@`** — Empty. *"Grants and revokes IAM across the
  organisation. Break-glass: join for the task, then leave."*
- **`grp-gcp-folder-admin@`** — Empty. *"Creates, moves and deletes
  folders. Break-glass: join for the task, then leave."*
- **`grp-gcp-billing-admin@`** — Empty. *"Administers the billing account
  — linking projects, budgets and payment. Break-glass: join for the
  task, then leave. One person also holds this directly, because a
  billing account has no recovery path outside its own policy."*
- **`grp-gcp-project-admin@`** — Empty. *"Creates projects directly at
  the organisation, outside every folder. Break-glass: join for the
  task, then leave. Billing them is separate."*
- **`grp-gcp-dns-admin@`** — Empty. *"Administers the organisation's own
  DNS — the ownership tokens, the mail policy, and the delegations
  naming which installation answers on which name. Break-glass: join for
  the task, then leave."*
- **`grp-gcp-security-reviewer@`** — Populated. *"Reads IAM policy across
  the organisation and changes nothing. Populated: auditing who holds
  what must never require the power to change it."*

For each, in this order: **Access type: Restricted**, *then* **Who can
join: Only invited users**. Reversing it discards the join rule, and the
type then reads Custom, which is correct. Leave the access matrix alone.

Create each **without an owner**. An owner is always a member, so owning
the admins group means holding Organization Administrator permanently.
**No managers either** — administering a privilege-granting group is
privileged, and a super admin can do it without being in the group.

### 6. Bind them

**As the admin account**, and the only step in this recipe that is not
a directory act. It holds Organization Administrator directly from step
4, which is what makes this binding possible and step 8 what removes
the need for it.

```bash
gcloud auth login "admin@$QW_DOMAIN"
gcloud config unset project
just gcp-groups-bind-org
```

Each group against the roles that implement its capability, which are
declared in [organisation-roles.json](/infra/access/organisation-roles.json)
and printed readably by `just gcp-roles org`. Two report as bound below
the organisation rather than here: `grp-gcp-billing-admin@`, which step
7 binds on the billing account, and `grp-gcp-dns-admin@`, which
[apex-install](apex-install.md) binds on the apex project.

### 7. Create the billing account

**As a user on your own domain**, not the super admin — one made under
**Directory**, then **Users**, if you have none — so that user
administers the account. In the ordinary browser window.

Take the free trial if offered; some payment methods are asked for a
small refundable prepayment first.

> [!WARNING]
> Whether the payments profile is for an individual or an organisation
> cannot be changed afterwards. Pick organisation only if a registered
> entity exists to name, matching the payment instrument.

Read the account id off, and export it:

```bash
gcloud billing accounts list --filter='OPEN=True'
```

```bash
# the account just created, from the ACCOUNT_ID column, e.g.
export QW_BILLING=xxxxxx-xxxxxx-xxxxxx
```

Then bind the group, and keep your own direct binding:

```bash
gcloud billing accounts add-iam-policy-binding "$QW_BILLING" \
  --member="group:grp-gcp-billing-admin@$QW_DOMAIN" \
  --role=roles/billing.admin
```

### 8. Put the standing grants away

`grp-gcp-org-admin@` now carries Organization Administrator, so remove
the admin account's direct organisation binding in the console, then
revoke it locally:

```bash
gcloud auth revoke "admin@$QW_DOMAIN"
```

An organisation, an open billing account, capabilities nobody holds by
default, and a super admin nobody signs in as.

## Failures

**"You're all set to use Google Workspace apps."** Said on a successful
Cloud Identity sign-up and on a successful Workspace trial alike — the
sign-up flow is shared and the wording is Workspace's. Nothing in it
reports which edition you ended on, and the trial expires and takes the
organisation with it, which is why step 3 goes and reads the
subscription rather than trusting the confirmation.

**A card the sign-up flow will not verify.** The card is fine. Card
verification runs through 3-D Secure, which redirects to the issuer and
back, and a private window — the one step 1 asked for — breaks the
return leg. The failure is reported against the payment method, so the
next thing tried is another card.

**A group that plainly exists, reported as "There is no such a group".**
`gcloud identity groups describe` was asked with no active project.
Every Cloud Identity call attributes quota to one, and a read with none
answers as though the group were absent rather than saying so.

## Rules

**MUST:**

- Set recovery email and phone on the super admin, and 2-step
  verification. It has no mailbox and no one above it.
- Read the subscription in the Admin console rather than trusting the
  sign-up confirmation. Cloud Identity Free and a Workspace trial
  confirm identically, and the trial expires and takes the organisation
  with it.
- Create every group without an owner or a manager. Both are members.
- Set **Restricted** before **Only invited users**, or the join rule is
  discarded.
- Bind the organisation's groups with `just gcp-groups-bind-org`, from
  no active project. An installation's are
  `just gcp-groups-bind-installation`, and each fails before its own
  groups exist.
- Read what a capability grants, and why, with `just gcp-roles` before
  granting or questioning one. Organisation-scoped roles are declared in
  [organisation-roles.json](/infra/access/organisation-roles.json);
  everything folder or project scoped is in the compositions under
  `infra/platform/crossplane-xrds/`.
- Bind groups where humans hold access, and principals directly where
  automation does.
- Create the billing account as a user on your own domain rather than as
  the super admin, so that user administers it.
- Keep one direct human administrator on the billing account. A billing
  account has no recovery path outside its own IAM policy.
- Revoke the super admin's local credentials, and its direct
  organisation binding, once the group carries the role. Both: either
  one left standing still reaches super admin.

**MUST NOT:**

- Sign up in a browser already signed in to a Google account.
- Create the billing account in a private window. 3-D Secure needs the
  ordinary one.
- Write a role into the recipe that binds it. A role is declared in
  [organisation-roles.json](/infra/access/organisation-roles.json),
  where adding one is a reviewable diff and a reader can see what it is
  for.
- Script the creation of a group. Every Cloud Identity write attributes
  quota to a project, and at foundation time none exists.
- Leave anybody standing in `grp-gcp-org-admin@`,
  `grp-gcp-folder-admin@`, `grp-gcp-billing-admin@`,
  `grp-gcp-project-admin@` or `grp-gcp-dns-admin@`.

**MAY:**

- Skip this recipe entirely in an established organisation, and answer each
  capability with whatever it gives you.
- Reuse an existing billing account instead of creating one.
- Keep a second super admin, unused, so one lost device is not the end
  of the organisation.

## Discussion

We set the organisation up so that the account that created it is not
the account anybody works in, and so that no capability in it is held by
a person at rest. The super admin exists to have brought the
organisation into being and to administer the directory; a user on the
domain is what a person signs in as from then on; and everything between
them is carried by a group somebody joins for a task and leaves. The
last step is taking the first identity's power away again.

**What this produces is capabilities, not groups.** Every recipe
downstream asks for a capability and gives a group as an example, never
as a requirement: `platformViewer`, e.g.
`grp-gcp-<code>-platform-viewer@`. The groups here are how we answer
them for ourselves, and are worth the trouble mainly because
somebody has to be able to see who holds what. An organisation you
joined rather than created answers them differently and is no less
correct.

**Why the super admin is not the account you work in.** It can grant
itself anything, it administers the directory that decides who is in
which group, and it is the account a compromise wants. Making it the one
somebody signs in with daily rests the whole organisation on that
session. So it creates the organisation, hands Organization
Administrator to a group, and is put away; what a person signs in as
afterwards holds `roles/browser` and joins a break-glass group for
anything more, per
[ADR-0023](../../adr/0023-installation-naming-and-access.md).

**Why both halves of step 8 matter.** Removing the direct organisation
binding leaves an account that can be signed in as and can grant itself
the role back. Revoking the local credential leaves a binding that
whoever holds the password inherits. Left half-done in the second way,
`gcloud config set account` reaches super admin with no password, no
prompt and nothing that reads as an escalation in the audit log.

**Why billing keeps a direct human binding.** Everywhere else in this
platform a person is bound through a group, and this is the one rule
with an exception. A billing account's IAM policy is not inside the
organisation's hierarchy, and nothing above it can repair the policy: if
the only administrator is a group and the directory holding that group
becomes unreachable, there is no path back to the account that pays for
everything. So one named person is bound directly, on purpose, and the
group is bound beside them.

**Why the roles are a file and the groups are not.** A role is the
answer to "what does holding this let me do", which is the question
somebody asks before joining a break-glass group and the one an auditor
asks afterwards — so it is worth being able to read without running
anything, and worth a diff when it changes. Group *names* stay in the
justfile because they are derived from the installation code and
[cloud-naming](../practices/cloud-naming.md) governs them. Each role
carries its own
reason in the file rather than in a comment beside it, so that whatever
prints a role prints the reason with it: a grant nobody has justified is
a grant nobody has decided.

**Why creating a group cannot be scripted.** Every Cloud Identity write
attributes quota to a project, and at this point the organisation has
none — which is also why `gcloud identity groups describe` answers that
a group plainly present does not exist. Binding needs no quota project,
which is why one half is a recipe and the other is a browser.

## References

- [contract-install](contract-install.md) — an
  installation's own capabilities, which come next.
- [cloud-naming](../practices/cloud-naming.md) — the code every
  installation name is
  built from.
- [plane-install](plane-install.md) — the management plane
  this leaves you ready to build.
- [up-and-running](up-and-running.md) — every
  recipe from here to a bank serving traffic, in order.
- [ADR-0023](../../adr/0023-installation-naming-and-access.md) —
  read-only or break-glass, and no third category.
