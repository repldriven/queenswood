# A site at the apex

<!-- tessl-plugin: deployment -->

## Status

**Untested.** Derived from
[ADR-0044](../../adr/0044-the-apex-serves-a-static-site-from-a-project-of-its-own.md)
and the Firebase Hosting REST API; the recipes have not yet been run
against an organisation.

## Problem

You want your domain's apex, and `www` below it, to answer with a static
page over HTTPS, with no cluster running and nothing charged for
serving it.

## Solution

### Prerequisites

- Step 1 — the organisation's capabilities, `webAdmin` among them —
  [organisation-foundation](organisation-foundation.md).
- Step 4 — the apex zone, declared in `apex.yml` and delegated —
  [apex-install](apex-install.md).
- Step 6 — a directory holding the page, with an `index.html` at its
  root.
- The capability each step names. Ours is a Google group; yours may
  differ.
- Steps 2 and 4 — write access to the manifests repository, and a
  merge.

```bash
# the domain the apex zone is for
export DOMAIN=example.com
# the directory holding the page
export SITE_DIR=~/site
# the private manifests repository, wherever it is checked out
export QW_INSTALLATIONS_REPO=../installations
```

Start at step 1 for a domain with no site. Start at step 6 to change
what an existing site serves.

### 1. Create the project

**As an org project admin.** Ours is `grp-gcp-project-admin@` — join
for this step, then leave.

```bash
just web-project-create
export WEB_PROJECT=$(gcloud projects list \
  --filter=labels.queenswood-tier=web --format='value(projectId)')
```

It prints the project, `prj-c-web-<suffix>`, Firebase added to it, and
the `webAdmin` roles granted on it. No billing account is linked.

### 2. Render the manifest

**As yourself.**

```bash
just web-manifest "$DOMAIN" "$WEB_PROJECT"
```

It writes `web.yml` at the root of the manifests repository, beside
`apex.yml`, naming `www` as a name that redirects to the apex. Read it,
commit it and merge it.

### 3. Connect the domain

**As the web admin.** Ours is `grp-gcp-web-admin@` — join for steps 3
to 6, then leave.

```bash
just web-apply
```

It creates the site and each domain `web.yml` names, then prints each
domain's state and the records `apex.yml` lacks.

### 4. Publish the records

**As the web admin, then as the DNS admin.** Ours is
`grp-gcp-dns-admin@` — join for the apply, then leave.

```bash
just web-status
```

It prints each record set `apex.yml` lacks, whole: where `apex.yml`
already holds a record of that name and type, the values it holds are
in the list. Replace or add each in `apex.yml`, commit it and merge it.
Then:

```bash
just dns-apex-diff
just dns-apex-apply
```

### 5. Wait for the certificate

**As the web admin.**

```bash
just web-status
```

Each domain should read `HOST_ACTIVE, OWNERSHIP_ACTIVE, CERT_ACTIVE`,
and the last line should say `apex.yml` carries every record Hosting
asks for. A certificate can take up to a day.

### 6. Publish the page

**As the web admin.**

```bash
just web-publish "$SITE_DIR"
curl -sI "https://$DOMAIN" | head -1
curl -sI "https://www.$DOMAIN" | head -1
```

The release names the file count, the first `curl` answers `200` and
the second `301`.

### 7. Put the standing grant away

**As yourself, for the last time.** Creating the project in step 1 made
you its owner, and nothing after it needed that.

```bash
gcloud projects remove-iam-policy-binding "$WEB_PROJECT" \
  --member="user:$(gcloud config get-value account)" --role=roles/owner
```

## Failures

**A browser warns that the certificate is for another name.** Until the
domain's own is issued, Hosting answers with one that does not name it.
Read `just web-status`: anything short of `CERT_ACTIVE` is still
issuing.

**The site stops serving part-way through a day.** The project is
unbilled and the day's no-cost transfer is spent. It serves again the
next day, or once a billing account is linked.

**`just web-status` goes on listing a record `apex.yml` holds.** The
file was merged and not applied. `just dns-apex-diff` says so.

## Rules

**MUST:**

- Create the web project outside every folder with `just
  web-project-create`, which links no billing account and binds
  `webAdmin` on that project alone.
- Declare the site in `web.yml` at the root of the manifests
  repository, and change it by merging that file and running `just
  web-apply`.
- Publish the records Hosting asks for through `apex.yml` and `just
  dns-apex-apply`, reading them from `just web-status`.
- Release the site with `just web-publish`.

**MUST NOT:**

- Connect a domain or release a site from the Firebase console. Nothing
  records it, and `web.yml` no longer says what serves.
- Remove Hosting's `hosting-site=` TXT value from `apex.yml` while the
  site serves. Hosting checks it continually.

**MAY:**

- Link a billing account to the web project to keep the site serving
  past the no-cost quota, which charges for what it serves beyond it.
- Redirect no names to the apex, rendering with `names=""`.
- Point the apex at a load balancer instead, once one exists. It is the
  same A record.

## Discussion

We serve the page from Firebase Hosting in a project of its own at the
organisation, beside the apex zone's, and declare the site in
`web.yml`. The `web-*` recipes create what that file names, read back
the records Hosting asks for, and release a directory. They never write
the apex zone: `apex.yml` stays the one declaration of what it holds.

**Why no billing account.** An unbilled project is on Hosting's no-cost
plan, where exhausting the quota stops the site rather than charging
for it. A page that becomes popular enough to need more is a decision
to pay, made by linking an account.

**Why the records are shown whole.** `apex.yml` holds one entry per name
and type, every value for that pair in its `rrdatas`, and the apex
already carries TXT values Hosting's ownership token has to join — the
verification tokens and SPF among them. A record set shown alone would
replace them.

**Why `www` redirects.** Each name Hosting serves is a domain of its
own, with its own certificate. Redirecting it means the page has one
address, and a name below the apex costs nothing to keep answering.

**Why the API rather than the Firebase CLI.** The recipes call Hosting
with `gcloud`'s token, so every act is the same identity as every other
recipe here, and nothing else needs installing or signing in to.

## References

- [ADR-0044](../../adr/0044-the-apex-serves-a-static-site-from-a-project-of-its-own.md)
  — why the apex serves from Hosting, and the options set aside
- [ADR-0028](../../adr/0028-the-apex-belongs-to-no-installation.md) —
  the apex, and the front door this does not foreclose
- [apex-install](apex-install.md) — `apex.yml`, and applying it
- [organisation-foundation](organisation-foundation.md) — the
  capabilities each step takes
- [cloud-naming](../practices/cloud-naming.md) — why the project
  carries no code
- [Connect a custom domain](https://firebase.google.com/docs/hosting/custom-domain)
  — the records Hosting asks for, and the certificate's timing
- [Firebase Hosting REST API](https://firebase.google.com/docs/reference/hosting/rest)
  — sites, custom domains, versions and releases
