# Postmark as an instance's submission provider

<!-- tessl-plugin: deployment -->

## Status

**Untested.** One instance's sending domain was verified this way: the
account, the server, the DKIM and Return-Path records, and deleting the
signup domain. No email has been sent through it yet.

## Problem

You want an instance to send its invitation emails through Postmark,
and you need the Postmark half of
[outbound-email-install](outbound-email-install.md): which credential,
which records, and what the account needs before it sends to anyone.

## Solution

### Prerequisites

- An address on a domain you own, which Postmark's signup requires. The
  apex's own mail is one, and has nothing to do with Postmark.
- The instance's zone, delegated — see
  [instance-deploy](instance-deploy.md).

Each step below is the Postmark act for the
[outbound-email-install](outbound-email-install.md) step it names.

### 1. Create a server

One server per instance, named `<code>-<env>-<label>`, delivery type
**Live**. Invitations use its **Default Transactional Stream**.

### 2. Add the sending domain

**outbound-email-install step 1.** Under Sender Signatures, add the
instance's domain as a domain, not an address. Postmark lists two
records, both Inactive:

- **DKIM:** TXT at `<timestamp>pm._domainkey.<label>`.
- **Return-Path:** CNAME at `pm-bounces.<label>` to `pm.mtasv.net`.

Delete the domain Postmark created from the signup address, and the
address signature under it.

### 3. Generate an SMTP token

**outbound-email-install step 2.** In the transactional stream's
settings, generate an SMTP token. The access key is the username and
the secret key is the password.

### 4. Publish the records

**outbound-email-install step 3.** In `<code>/<label>.zone.yml`, with
the `.<label>` suffix Postmark shows dropped from each name:

```yaml
spec:
  records:
    - name: "<timestamp>pm._domainkey"
      type: TXT
      rrdatas:
        - '"k=rsa;p=<key>"'
    - name: "pm-bounces"
      type: CNAME
      rrdatas:
        - "pm.mtasv.net."
    - name: "_dmarc"
      type: TXT
      rrdatas:
        - '"v=DMARC1; p=none"'
```

Merge, then click **Verify** beside each record in Postmark. Both read
Verified.

### 5. Name the server

**outbound-email-install step 5**, with:

```yaml
mail:
  smtp:
    host: smtp.postmarkapp.com
    port: 587
    security: starttls
    username: <access key>
```

### 6. Request approval

From the account page. Until Postmark approves the account it delivers
only to addresses on its verified domains, so check
outbound-email-install step 6 by inviting one of those.

## Failures

**A banner asking for DKIM and Return-Path records that already
resolve.** Postmark checks only when Verify is clicked. Where it still
reads Inactive, it cached the name's absence from before the merge:
wait five minutes and click again.

**A banner saying DMARC is not set up, with `_dmarc` resolving.** It
advertises DMARC Digests rather than reporting on the domain.

**An invitation to an outside address that never arrives, while one to
the verified domain does.** The account is not yet approved.

## Rules

**MUST:**

- Create one server per instance, named `<code>-<env>-<label>`, and send
  through its transactional stream.
- Add the instance's domain as a domain, and publish its DKIM and
  Return-Path records in the instance's zone.
- Use an SMTP token's access key and secret key as the username and the
  password, written with `just queenswood-instance-smtp-secret`.
- Delete the domain Postmark created from the signup address.

**MUST NOT:**

- Use the Server API Token as the SMTP password. It carries the whole
  API.
- Add an SPF include for Postmark. The Return-Path CNAME is what SPF is
  checked against.
- Send through a Broadcasts stream.

**MAY:**

- Add DMARC Digests' `rua=` address to the instance's `_dmarc` record,
  for the reports that say when `p=quarantine` is safe.

## Discussion

Postmark holds an instance as a server with a transactional stream, and
authenticates its mail with a DKIM key and a bounce domain under the
instance's own name.

**Why no SPF record.** SPF is checked against the envelope sender, and
Postmark's is `pm-bounces.<domain>`, which the CNAME points at
Postmark's own SPF. The instance's domain itself sends nothing, so its
alignment comes from the Return-Path and DKIM together.

**Why delete the signup domain.** Mail sent as it would be signed by
Postmark rather than by the domain, and an apex publishing a strict
DMARC policy has receivers reject it.

**Why a server per instance.** Its token, its stream and its sending
statistics are the instance's alone, so rotating one credential or
reading one instance's bounces touches no other.

## References

- [outbound-email-install](outbound-email-install.md) — the steps this
  fills in.
- [ADR-0020](../../adr/0020-providers-are-deployment-facts.md) —
  External providers are deployment facts, not request parameters.
