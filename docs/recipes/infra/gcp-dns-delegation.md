# Moving a domain's delegation

<!-- tessl-plugin: deployment -->

## Status

**Verified.** Used to move a domain off the registrar's own DNS, and
to move one from an existing zone to another.

## Problem

You have a zone that answers, and the domain is still delegated
somewhere else — the registrar's own DNS, or an older zone of yours.
The act is the same either way: four nameservers, replaced at the
registrar.

What differs is what has to be in the new zone first, and step 1 is
where that is proved rather than assumed.

## Solution

### Prerequisites

- The apex zone created and answering —
  [apex-install](apex-install.md). This is the registrar's delegation;
  a name below the apex is delegated from the apex zone instead, which
  is [instance-deploy](instance-deploy.md) step 1.
- The domain prepared by [gcp-dns](gcp-dns.md): verified, its
  automation identity an owner, inventoried, and unsigned if it was
  signed.
- An account at the registrar that can change nameservers.

### 1. Prove the new zone answers everything the old one does

Both sets of nameservers, current and new:

```bash
dig +short NS <domain>
just dns-apex-nameservers
```

Then the same sweep the domain was inventoried with, asked of one from
each — never of a public resolver, which answers from whichever is
delegated and can tell you nothing about the other:

```bash
diff <(just dns-records <domain> <one-of-the-new>) \
     <(just dns-records <domain> <one-of-the-current>)
```

Only the SOA and the NS records themselves may differ, each naming its
own authority. Anything else that differs is something the new zone
does not yet carry.

**The sweep covers the apex and the policy names, and nothing below
them.** Where names exist under the domain — an environment answering
on a subdomain — check each directly against both authorities, and move
or replicate them before delegating.
[apex-dns-migration](../../plan/apex-dns-migration.md) is that case
written out.

### 2. Replace all four at the registrar

Choose custom nameservers and replace **all four**, without trailing
dots. Leave whatever was authoritative before it intact — the
registrar's own records, or the old zone — since that is what makes
reverting a matter of switching back.

Do not re-enable DNSSEC there. If signing is wanted again it goes on
the new zone, with a fresh DS published at the registrar afterwards.

### 3. Confirm the delegation moved

The registrar saving is not the delegation moving: it submits to the
registry, which is its own hop. Ask the registry, for its authority
section:

```bash
dig NS <domain> @<a registry nameserver> +noall +authority
```

`dig +short NS <tld>.` lists the registry's nameservers to aim that at.
It answers with the new four when the registry has the change, whatever
public resolvers are still caching.

### 4. Confirm ownership survived it

```bash
dig +short TXT <domain> @<one-of-the-new>
```

The `google-site-verification=` token has to answer from the new
authority. Everything rests on it — Cloud DNS refuses zone operations
to an identity that does not own the name — and it is the one whose
absence shows up as nothing at all rather than as a broken page.

### 5. Authorise a CA

An absent CAA authorises every CA rather than none. One record at the
apex covers every name below it, delegated child zones included, since
a CAA lookup walks up the tree.

For a zone whose certificates come from Certificate Manager, add to
`apex.yml`:

```yaml
  - type: CAA
    rrdatas: ['0 issue "pki.goog"']
```

then `just dns-apex-apply`. Name every CA that must be able to issue:
one that is left out cannot, and the failure appears at issuance rather
than here.

## Failures

**A registry query that answers nothing.** `+short` was in it. A
referral carries the NS records in the authority section and has no
answer section, and `+short` prints only an answer — so the query looks
empty and reads as a delegation that has not moved.

**A name below the apex that stops resolving after the move.** It
existed only in the old zone, and step 1's sweep does not reach below
the apex to find it. The certificate validation record is the one that
hurts most: the certificate stays valid and fails to renew, months
later.

**Resolvers disagreeing about who is authoritative.** Expected, and
bounded by two TTLs — the registry's delegation, and the old zone's own
NS records, which is usually the longer of the two by hours. Both
authorities answering the same is what makes the window harmless, which
is the whole purpose of step 1.

**A registrar warning that this disconnects the site.** Accurate and
intended where it was serving the domain: its DNS stops being
authoritative and everything in its panel stops applying.

## Rules

**MUST:**

- Diff the sweep from each authority before delegating, with
  `just dns-records <domain> <nameserver>` aimed at each.
- Check every name below the apex separately. The sweep does not reach
  them, so one that exists only in the old zone passes step 1 and stops
  resolving after the move.
- Query the registry's authority section to check the delegation
  itself, since a referral carries the NS records there rather than in
  the answer.
- Confirm the verification TXT resolves from the new authority
  afterwards, with `dig +short TXT <domain> @<one-of-the-new>`.

**MUST NOT:**

- Change the delegation before the new zone answers, or while a DS
  record still names the old nameservers' keys.
- Replace only some of the registrar's nameservers.
- Delete the old records at the registrar as part of the move. They are
  the way back.
- Re-enable DNSSEC at the registrar after moving.
- Diff against a public resolver. It answers from whichever authority
  is delegated and says nothing about the other.

**MAY:**

- Let the SOA and the NS records differ between the two sweeps, each
  naming its own authority. Anything else that differs is something the
  new zone does not yet carry.

**SHOULD:**

- Set a CAA record naming every CA that must issue, at the apex, where
  it covers delegated child zones too.

## Discussion

The delegation moves last because a delegation to a zone that does not
answer is an outage, and because the token proving ownership has to be
answering from the new zone before the move — or the move takes
ownership away with it, which is the failure that takes the zone too.

Done in the order above the propagation window is a no-op: both
authorities answer the same, so there is no interval in which resolvers
holding either one are wrong.

## References

- [gcp-dns](gcp-dns.md) — preparing the domain, which comes first.
- [apex-dns-migration](../../plan/apex-dns-migration.md) — delegating a
  domain that already has names answering below it.
- [apex-install](apex-install.md) — creating the apex zone this
  delegates to, and where its nameservers are read from.
- [instance-deploy](instance-deploy.md) — delegating a name below the
  apex, which touches no registrar.
