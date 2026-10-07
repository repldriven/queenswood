# 44. The apex serves a static site from a project of its own

<!-- tessl-plugin: deployment -->

## Status

**Proposed**

## Context

The property wanted is that the apex answers with a page — a landing
page, the same for every installation — without any cluster running,
without a fixed charge for serving it, and declared in git and applied
by a person like the apex zone itself.

Without it the apex answers nothing.
[ADR-0028](0028-the-apex-belongs-to-no-installation.md) leaves the apex
record to point at prod's address or at a front door, and both need an
installation standing. A page that has to be there while every instance
is shelved, or before the first one exists, cannot depend on either.

The apex cannot be a CNAME, and Cloud DNS has no ALIAS, so whatever
serves the page has to answer on an address the apex's A record names.

The shortlist:

- **A bucket's website endpoint.** Rejected: it is reached by CNAME
  only, so never from the apex, and it serves no certificate for a
  custom domain.
- **A global external Application Load Balancer with a backend
  bucket.** Rejected for now: it is the front door ADR-0028 defers, and
  its forwarding rule is billed by the hour whether or not anything is
  served, a fixed charge for one page. Choosing Hosting does not
  foreclose it — it is the same A record.
- **A regional external Application Load Balancer.** Rejected: its
  forwarding rule is billed the same as a global one, it needs a VPC
  and a proxy-only subnet besides, and it serves only a public bucket,
  with no cache.
- **A page served from an instance.** Rejected: the apex would go dark
  whenever that instance did, which ADR-0028 rules out.
- **Firebase Hosting, in a project at the organisation.** Chosen: it
  answers on addresses the apex's A record can name, issues and renews
  the certificate itself, charges nothing within its no-cost quota, and
  connects a domain and releases a site through an API a recipe can
  call.

## Decision

The apex's A record names a Firebase Hosting site in
`prj-c-web-<suffix>`, a project at the organisation beside
`prj-c-dns-<suffix>`, declared in `web.yml` at the root of the
manifests repository and applied by a person through the `web-*`
recipes.

The parts:

- Create the project outside every folder, with no billing account
  linked, so serving past the no-cost quota stops the site rather than
  charging for it.
- Bind `webAdmin` on that project and nowhere else, break-glass like
  `dnsAdmin`.
- Declare the domain, the project, the site and the names below the
  apex that redirect to it in `web.yml`, and change them by merging
  that file and running `just web-apply`, which creates what is absent
  and deletes nothing.
- Publish the records Hosting asks for through `apex.yml` and `just
  dns-apex-apply`, as `just web-status` reports them: the web recipes
  never write the apex zone.
- Give the apex's A record the 60-second TTL ADR-0028 asks for.
- Release what the site serves with `just web-publish`, from a
  directory holding an `index.html`.

## Consequences

**Easier.** The apex answers with a page whatever state every
installation is in, and nothing is running to make it so. It costs
nothing until it is popular enough to exhaust the quota, and moving it
to a load balancer later is one A record in `apex.yml`. The project
belongs to no installation for the reason the apex zone does, and no
installation's capability reaches it.

**Harder.** Two projects sit above every folder where there was one,
each with a break-glass group of its own. An unbilled project stops
serving once the day's quota is spent, and only linking a billing
account lifts it — a success that reads as an outage.

Nothing reconciles the site or the domains connected to it, and Hosting
may change the records it asks for. `just web-status` is the audit
that makes either visible, against `apex.yml`, and only when it is run.
