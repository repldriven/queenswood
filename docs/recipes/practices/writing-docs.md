# Writing docs

<!-- tessl-plugin: docs -->

## Problem

You're writing or editing a document under `docs/` — an ADR, a
TDD, a PRD, a recipe, a plan — and you want it to fit the
project's conventions for formatting, tone, link hygiene, and
mermaid diagrams.

## Solution

Treat `docs/` as a single readable surface. The project has a
few hard rules driven by how the docs are read (Neovim with a
column ruler at 80, GitHub markdown, mermaid rendering), plus a
tone register that's deliberately understated and a PRD-vs-TDD
split that determines which vocabulary is allowed where.

### What a recipe is made of

Six sections, in this order, each answering one question and only
one:

- **`## Status`** — has this been run? A procedure only; see below.
- **`## Problem`** — *what* you want, in a sentence or two. Not why it
  is hard, not what it costs, not what makes it awkward.
- **`## Solution`** — *how*. The steps, or the convention, and nothing
  else.
- **`## Rules`** — MUST, MUST NOT, MAY. The normative summary, and what
  the tessl plugins distil into agent rules.
- **`## Discussion`** — *why*. Everything the sections above left out.
  Optional, and often the longest.
- **`## References`** — the related recipes and ADRs.

The test is a reader who does not care why: they should be able to stop
at the end of the Rules with everything they need. Where they cannot,
the how is carrying a why, and the why belongs one section further
down where somebody has chosen to read it.

### Steps are followed, not read

A Solution made of steps is copied into a terminal by somebody who is
partway through something and not reading around it. So:

- Set the variables the steps share once, at the top of the Solution,
  with an example value. A command carrying `<code>` has to be edited
  before it runs, which is how one gets run against the wrong thing.
- Name a file in full in every step that touches it. "The same file as
  above" assumes a reader who arrived from above.
- Let each step stand alone. A step that calls a helper defined in
  another step fails for anybody who came back to it in a new shell.
- Say what the output means, especially when nothing appears to have
  happened. A step whose success looks like failure gets repeated.

### Hard-wrap at 80 columns

All Markdown under `docs/` hard-wraps at 80 columns. Wrap prose
paragraphs and indent bullet continuation lines by 2 spaces.

```
- A bullet whose prose runs long enough to wrap should
  continue with a 2-space indent on subsequent lines.
```

Verify with awk:

```
awk '{ if (length($0) > 80) print FILENAME":"NR": "length($0) }' <file>
```

The 80-column rule exists because the docs are read in Neovim
with a colorcolumn ruler at 80; long lines render with the
ruler overlapping content. Workspace Clojure code is already
80-wrapped via zprint; docs match.

### The tessl-plugin label

A recipe or ADR distilled into a plugin rule carries
`<!-- tessl-plugin: <name> -->` in its front matter — after the title,
before the first section. Anywhere in that window is found; the exact
line is not load-bearing.

That is deliberate rather than lax. A markdown formatter puts a blank
line after a heading, so a label pinned to line 2 moves to line 3 the
first time somebody saves the file, and a parser reading a fixed line
then reports the doc as unlabelled — indistinguishable from one nobody
has labelled, and silent. Documents should be free to be formatted, so
the tooling tolerates the shapes formatting produces.

Which means: do not move a label to satisfy a script. If discovery
cannot find a labelled doc, the script is wrong.

### Markdown link hygiene

Three patterns break the project's markdown viewers and should
be avoided.

**1. Don't put `)` immediately after a link's closing paren.**

```
;; Bad — adjacent `))` confuses the viewer
The pattern (see [foo.md](foo.md)) exists.

;; OK — em-dashes or comma split avoids the adjacency
The pattern — described in [foo.md](foo.md) — exists.
```

A `.` or other punctuation after a link is fine. Only an
adjacent `)` is the problem.

**2. Don't wrap link text across lines.**

```
;; Bad — link text spans two lines
- [ADR-0001 — Reuse mono as
  upstream](../../adr/0001-reuse-mono-as-upstream.md)
```

Use the canonical reference-list pattern: link first, em-dash,
title as plain prose. The link stays intact; the trailing
prose can wrap.

```
;; OK — link intact, title fits inline
- [ADR-0001](../../adr/0001-reuse-mono-as-upstream.md) — Reuse mono as upstream

;; OK — link intact, title wraps because the path is long
- [ADR-0011](../../adr/0011-one-component-per-third-party-library.md) —
  One component per third-party library
```

**3. Don't use inline code as the entire link text.**

```
;; Bad — backticked path as link text trips some parsers
[`slides/foo/bar.md`](../slides/foo/bar.md)

;; OK — plain link text
[slides/foo/bar.md](../slides/foo/bar.md)
```

**4. Climb at most two levels (`../../`), and never three.**
Recipes sit one level deeper than everything else under `docs/`
— `docs/recipes/<chapter>/` — so a recipe reaching an ADR is
`../../adr/...` and there is no shallower spelling of it. A
recipe reaching another chapter is `../<chapter>/...`, and
anything outside `docs/` reaching in is `../recipes/<chapter>/...`.
Three or more means the link is leaving `docs/` for a tree that
should have been under it.

### A procedure says whether it has been run

A recipe describing steps carries a `## Status` as its first section,
opening with one of three words. A recipe describing a convention
carries none: there is nothing to have run.

- **Verified** — the steps have been followed as written, and corrected
  from doing so. Say when, and on what.
- **Untested** — nobody has followed them. Say what they were derived
  from, since that is what a reader is trusting instead.
- **Superseded** — they describe a generation no longer in use. Say
  what still holds, since mechanics often outlive the model around
  them.

Being run and being followed are different things: a procedure written
up after the act is `Untested` until somebody works from the document.
That is the distinction the word is carrying, and it is worth the
pedantry — the defects a walkthrough finds are in the writing rather
than in the acts, so a document nobody has followed has not been tested
at all.

### Mermaid diagrams

**Don't use `;` inside mermaid labels, notes, or arrow text.**
Mermaid treats `;` as a statement separator; using it inside
human-readable strings splits the line at the semicolon and the
parser then sees a malformed continuation. GitHub fails to
render the block.

```
;; Bad
  Note over A,B: first thing; second thing

;; OK — comma, em-dash, period, or <br/> instead
  Note over A,B: first thing, second thing
  Note over A,B: first thing — second thing
  Note over A,B: first thing.<br/>second thing.
```

Don't try to escape the semicolon (`\;`, HTML entity); mermaid
doesn't honour either. The same pitfall applies across mermaid
grammars (sequence, flowchart, state).

**Mermaid lines can exceed 80 chars when needed.** The 80-col
wrap is for prose. A mermaid sequence label, node label, or
arrow text that reads more clearly as a single line at, say,
90 chars should stay one line — don't insert `<br/>` purely to
hit 80. Use `<br/>` only when the label *should* render on two
lines in the diagram.

### Tone

Two rules keep the docs honest and durable.

**No maturity overclaim.** Don't describe `mono`, Queenswood,
or any of their components as "battle-tested",
"production-proven", "years of hardening", or similar
maturity-framing. Both projects are new; they have tests and
they work, but they don't have production miles. Stick to
literal facts: "exists", "has tests", "is the only place this
code lives", "designed to be reusable from the start". Reuse
arguments rest on avoiding duplication and architectural
cleanness, not on imaginary track record.

**No competitor names.** Don't name specific banks or fintechs
(Revolut, Griffin, Kroo, Monzo, Starling, and so on) in TDDs,
ADRs, recipes, or PRDs. Use generic phrasing instead.

```
;; Not OK
Daily-compounded interest. This is what Revolut does today;
Griffin is moving in this direction.

;; OK
Daily-compounded interest, which an increasing number of
digital banks offer to compete on rate visibility.
```

If a real reference is genuinely useful, cite a public spec,
RFC, or standard (ISO 20022, FPS scheme rules) rather than a
vendor.

### No specific counts, dates, or "recent" framings

Don't pin a doc to a snapshot of repo state — the snapshot
ages worse than the prose around it.

```
;; Not OK
- `docs/adr/` — fourteen records covering ...
- `docs/recipes/` — thirteen recipes covering ...
- The thirteen service projects under projects/ ...

;; Also not OK
This was added a fortnight ago.
As of May 2026, the project has 47 bricks.

;; OK
- `docs/adr/` — architecture decisions, one per load-bearing
  choice.
- `docs/recipes/` — task-oriented recipes; read the relevant
  one before doing any non-trivial task.
- Each service project under projects/ ...
```

The rule applies to:

- **Counts of artefacts** — "fourteen ADRs", "thirteen
  recipes", "twenty-one bank bricks". Use plurals or
  "each" / "the relevant" / "every" instead.
- **Datestamps** — "as of May 2026", "added in Q2".
  References to commits, PRs, or release tags age the
  same way; describe the *state* the doc captures, not
  when it was captured.
- **"Recently" / "recently added" / "lately"** — relative
  time anchors that no longer mean what they meant when
  written.

If a count or date is genuinely load-bearing (e.g. a regulator
threshold, a fixed-cardinality enum), it can stay — that's not
project state, it's external. Otherwise, drop it.

### Aspiration over enforcement

When a doc captures a code-quality rule (one-component-per-
library, no-bang naming, helper-convergence in `utility`, and
so on), frame it as a **principle and discipline** rather than a
mechanical CI-enforced gate. Acknowledge that drift happens
during ordinary development and the practice is to catch it
during code review or periodic audit.

Soften absolute language ("exactly", "must", "violation") in
favour of "the principle is X / reality is messier" framing.
Mention any audit mechanism (`clj -M:poly libs`, etc.) as
*visibility*, not enforcement. Acknowledge drift in the Harder
consequences section explicitly. Don't pitch CI gates unless
the rule is genuinely mechanical and the user has asked for one.

This sits in tension with the MUST / MUST NOT / SHOULD list at
the foot of every recipe — that list is shorthand, not a
contract, and the body text gives the framing.

### PRDs specifically

PRDs are read by product-shaped readers — product managers,
designers, compliance, executives — not engineers. Two rules
follow.

**Use non-technical product language.** Engineering vocabulary
doesn't belong in a PRD even when it's accurate.

- Avoid: synchronous, asynchronous, reactive, primitive,
  watcher, relay, handler, idempotent, deterministic,
  transaction (in the engineering sense), event, dispatch,
  subscribe, poll (as a verb of art), choreography, saga,
  orchestrator.
- Prefer: "in the background", "automatically", "without the
  tenant having to do anything", "once X completes", "the
  platform offers a way to", "a means of comparing".
- "Atomic" is borderline — OK if framed as "all-or-nothing"
  with a quick gloss; better to say "either the tenant comes
  up complete or doesn't come up at all".
- Internal-mechanism words (changelog, FDB, message bus, brick)
  never belong in a PRD.
- Sequence diagrams in PRDs describe user-visible beats, not
  internal hops between components.

```
;; Not OK — engineering register
The IDV flow is asynchronous and reactive. A name-matching
primitive lets callers compare names. A relay publishes an
event when the changelog updates.

;; OK — product register
Identity verification runs in the background; the tenant
doesn't wait for it. The platform offers a way to compare two
names softly. Activation happens automatically once
verification completes.
```

**Describe what users do, not the operation name.** PRDs say
"uses the banking API to X" — they don't name specific
operations like `create-organization` or `submit-payment`.
Operation names belong in TDDs and the OpenAPI spec.

```
;; Not OK
The platform exposes a `create-organization` operation.
The admin calls `create-organization` with name, type, ...

;; OK
A platform admin uses the banking API to create a new tenant
in a single call. The call accepts the organisation's name,
type, ...
```

Inputs and outputs to a call can still be listed, framed as
"the call accepts" / "the call returns" — that's user-relevant
without naming the operation.

## Rules

**MUST:**

- Hard-wrap markdown at 80 columns under `docs/`.
- Structure a recipe as Problem, Solution, Rules and — where there
  is one to give — Discussion. What, how, normative, why.
- Keep rationale out of the Solution. A reader following steps has
  not asked for it.
- Open a procedure's `## Status` with **Verified**, **Untested** or
  **Superseded**. A recipe describing a convention has no Status.
- Use the canonical reference-list pattern for ADR / recipe /
  TDD links: `[ID](path) — Title`.
- Keep relative links inside `docs/` to two levels at most,
  which is what a recipe reaching an ADR costs.
- Replace `;` inside mermaid labels, notes, and arrow text
  with `,`, `—`, `.`, or `<br/>`.

**MUST NOT:**

- Put `)` immediately after a link's closing paren.
- Wrap link text across lines.
- Use inline code as an entire link text.
- Use relative links that climb three levels or more
  (`../../../`).
- Describe `mono`, Queenswood, or their components as
  "battle-tested", "production-proven", or similar maturity
  claims.
- Name specific competitor banks or fintechs in any doc.
- Use engineering vocabulary (sync/async, reactive, relay,
  handler, primitive) in PRDs.
- Name specific operations (`create-organization`,
  `submit-payment`, etc.) in PRDs.
- Pin docs to specific counts of repo artefacts (number of
  ADRs, recipes, services, bricks) or to relative-time
  framings ("recently", "as of …", "a fortnight ago").

**SHOULD:**

- Frame code-quality rules as principle and discipline, not
  mechanical CI gates. Acknowledge drift in the Harder
  consequences.
- Let mermaid lines exceed 80 chars when the diagram reads
  more clearly as a single line.
- Cite public specs / RFCs / standards rather than vendors
  when an industry reference is useful.
- Use the project's vocabulary in TDDs and recipes
  (`changelog relay`, `brick`, `interceptor`); reserve
  product-shaped phrasing for PRDs.

## Discussion

Most of these rules exist because the docs are read in
specific tools, not generic markdown viewers. Neovim's
colorcolumn drives the 80-col rule; the project's markdown
viewer drives the link-pitfalls; GitHub's mermaid renderer
drives the no-semicolon rule. They're surface-quality rules,
but ignoring them produces visibly broken pages, and the
fixes are easy to land at write time.

The tone rules — no maturity overclaim, no competitor names —
exist because the docs may end up public (in the GitHub repo,
on a docs site, in a published OpenAPI). Naming a competitor,
even accurately, ages badly as their product changes; claiming
maturity that isn't there reads as marketing rather than
engineering. Stick to what's literally true and the docs age
gracefully.

The PRD-vs-TDD split is the rule with the highest payoff. A
PRD that names operations and uses engineering vocabulary
turns the document into a TDD-with-different-headers, and the
audience it's meant to serve can't read it without
translation. Forcing the register keeps the documents distinct
and readable to their respective audiences. The TDD covers the
same ground, names the operations, and is the engineering
contract — a PRD has earned the right to be product-shaped.

The aspiration-over-enforcement framing matters because the
project's quality rules are not always enforceable in CI. A
naming convention drift, a missed brick boundary, a helper
that ended up in two places — these are caught in review or
periodic audit, not by a green/red check. Pretending the
rules are mechanical when they aren't sets the wrong
expectation; framing them as principle and discipline matches
the lived reality and keeps the rule from going stale when the
codebase doesn't perfectly comply.

## References

- [ADR-0015](../../adr/0015-comments-and-docstrings.md) — Comments and
  docstrings
- [code-style.md](../code/code-style.md)
- [GitHub flavoured markdown](https://github.github.com/gfm/)
- [Mermaid documentation](https://mermaid.js.org/)
