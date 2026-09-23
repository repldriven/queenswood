# Writing about an installation

<!-- tessl-plugin: deployment -->

## Problem

Everything written about this system is written somewhere permanent and
mostly somewhere public: a commit message, a pull request description,
an issue, a comment, a recipe. Working on cloud infrastructure means
having real identifiers in front of you constantly — they are in the
terminal output you are reading when you write the sentence — and the
easiest sentence to write is the one naming what you just saw.

These are system identifiers: what PII is to a person, they are to an
installation — the account, folder, project and billing ids, the
addresses and the suffixes that pick out this one among every other.
CWE-497, the exposure of sensitive system information, is the class of
weakness writing one down belongs to.

None of them is a credential. That is exactly why they get written
down: nothing feels risky about pasting a project id. What an
organisation, folder or billing account id is good for is sounding like
somebody who already has access, on a support call or in an email, and
a public repository is where that person looks first.

There is a guard, and it is detection. It has caught real identifiers
and it has also been extended, by somebody who had read it that
morning, in a pull request whose own description carried a folder id.
Detection tells you afterwards, and afterwards is a value that has been
public for as long as it took somebody to notice.

So the aim is not to write one and be caught. It is to have nothing to
catch, which is easier than it sounds: a description almost never needs
a literal number at all.

## Solution

### Name the shape, never the instance

A name shape says how things are named, which is what a reader needs. A
realised one says which project, which is what nobody needs and what
somebody else wants.

```
prj-qw01-c-mgmt-xxxxxx        the shape, and what to write
folders/<folder-id>           likewise
bkt-qw01-n-test-backups-xxxxxx
```

`xxxxxx` for a suffix, `<folder-id>` and `<org-id>` and
`<project-number>` for the things that are only a number. They read as
placeholders, they cannot be mistaken for real, and `x` is not a hex
digit so the guard cannot match one either.

### Mask every number and every hex run

Not a list of dangerous shapes to remember. A description explains what
changed and why, and almost never needs a literal number to do it — so
the default is that numbers and hex runs do not go in, and the few that
must are named below rather than argued for one at a time.

That is deliberately stricter than the check. A check has to tolerate a
version and a git sha or it cries wolf, and 4.1% of this repository's
history already trips it; guidance has no such constraint. Being told
which shapes are safe is what produces a description with a folder id
in it, because the writer was busy deciding whether their number was
one of them.

The two exceptions, which are exceptions because they name nothing:

- **A public resolver or nameserver.** `8.8.8.8`, and the
  `ns-cloud-*.googledomains.com` set a zone is delegated to. Checking a
  delegation is a thing this repository documents and the addresses are
  the same for everybody.
- **Loopback and the unspecified address.** `127.0.0.1`, `0.0.0.0` as
  a bind address, and `localhost`.

Everything else goes in as a placeholder. A version, a git sha, a Job's
name-hash and a private range will all pass the check, and none of them
needs to be real to make a point: `<version>` and `<sha>` say what the
sentence is about, where the literal only says which afternoon.

### The habit that actually prevents it

**Mask on the way in, not on the way out.** The moment to replace a
suffix with `xxxxxx` is while writing the sentence, with the real value
still on screen — not after a check has failed, and not after a review.
Every identifier this project has had to scrub was written by somebody
who would have masked it if they had thought of it then.

Two places that is easy to forget, because the text is not yours:

- **Pasted terminal output.** An error message names the project it
  came from. Quoting it is the most natural thing in a description and
  it carries the id verbatim.
- **An example.** Writing about the guard, illustrate it with a
  placeholder. A real identifier used as a demonstration is still a
  real identifier, and it is how the last one got out.

### Where it applies

Everywhere the text can be read: the tree, a commit message, a pull
request's title and body, an issue, a comment, a review. The private
`installations` repository is where real ids belong, because that is
what it is for — but a description *of* a change to it is written in
the public one.

### When a real one belongs

Rarely, and then say so with `system-id-ok` on the line. It is an
assertion by the author that this value is meant, which is the point:
it makes the exception visible and attributable rather than silent.

## Rules

**MUST:**

- Mask every number and every hex run — a name's suffix, an id, an
  address, a version, a sha. Write `xxxxxx`, `<folder-id>`, `<org-id>`,
  `<project-number>`, `<version>`, `<sha>`.
- Mask while writing, with the real value still in front of you.
- Mask a pasted error message and a worked example the same way. Both
  are text somebody else produced, and both carry ids verbatim.

**MUST NOT:**

- Rely on the check to catch it. It is deliberately more permissive
  than this, so that it does not cry wolf, and it reports afterwards.
- Use a real identifier as an example of a masked one.

**MAY:**

- Name a public resolver or nameserver, and loopback. They identify
  nobody and a delegation cannot be documented without them.
- Mark a line `system-id-ok` where a real value genuinely belongs, which
  makes it deliberate rather than missed.

## References

- [cloud-naming](cloud-naming.md) — what things are called, and why a
  suffix exists at all
- [ADR-0023](../../adr/0023-installation-naming-and-access.md) — the
  naming and access decision underneath both
- `scripts/hooks/check-system-ids.sh` — the detection half, and the one
  definition of what an identifier is
- [CWE-497](https://cwe.mitre.org/data/definitions/497.html) — Exposure
  of Sensitive System Information to an Unauthorized Control Sphere
