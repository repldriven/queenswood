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

Seven sections, in this order, each answering one question and only
one:

- **`## Status`** — has this been run? A procedure only; see below.
- **`## Problem`** — *what* you want, in a sentence or two, opening
  with **You**. Not why it is hard, not what it costs, not what makes
  it awkward.
- **`## Solution`** — *how*. The steps, or the convention, and nothing
  else.
- **`## Failures`** — what it looks like when it did not work, keyed on
  what the reader is looking at. Optional; see below.
- **`## Rules`** — MUST, MUST NOT, MAY. The normative summary, and what
  the tessl plugins distil into agent rules.
- **`## Discussion`** — *why*. Everything the sections above left out.
  Optional, and often the longest.
- **`## References`** — the related recipes and ADRs.

A fact belongs under one of them. Where the same fact is in the
Problem, the Prerequisites and a Rules bullet, two of the three are
copies and they drift apart.

The test is a reader who does not care why: they should be able to stop
at the end of the Rules with everything they need. Where they cannot,
the how is carrying a why, and the why belongs one section further
down where somebody has chosen to read it.

### Writing steps

A Solution made of steps is copied into a terminal by somebody who is
partway through something and not reading around it. It opens with a
`### Prerequisites` heading holding two things: what must already exist
and who you must be to run each step — one line each, led by the step
it applies to, and no rationale — then the shell the steps assume, as
`export` lines with an example value.

Keep it to what no step supplies. A value a step's own recipe names is
that step's — a capability the contract asks for, a folder id, an
installation code — and a second copy here drifts from it. What belongs
is what the reader has to turn up holding, and what a later step needs
that nothing before it creates, led by that step.

Where a recipe has more than one way in, say where each starts, in a
sentence. Splitting the block into a section per reader writes
everything they both need twice.

```
;; Bad — a block per reader, and the shared lines in both
**From nothing.** Start at step 1, and you need:
- A domain, a payment method, somewhere for a private repository.
**From an established organisation.** Start at step 2. You need the
domain and the repository above, and:
- An IAM member string per capability, a folder id, a four-character
  code.

;; OK — one list, and the entry in a sentence
- Somewhere to keep a private git repository, for the manifests.
- Step 1 — a domain, and a payment method for the billing account.

Start at step 1 to create the organisation. Start at step 2 where one
exists already and can give you a folder.
```

Then:

- Let no command carry a placeholder. A command carrying `<code>` has
  to be edited before it runs, which is how one gets run against the
  wrong thing. What the steps share and you know before starting is
  exported under `### Prerequisites`; a value a step produces is
  exported at that step, beside where you read it off, because somebody
  working down the page does not scroll back to the top to record
  something.
- Put a comment on its own line, never after the command it explains.
  A trailing comment has to be deleted before the line can be pasted,
  and interactive zsh does not treat `#` as a comment by default.
- Name a file in full in every step that touches it. "The same file as
  above" assumes a reader who arrived from above.
- Let each step stand alone. A step that calls a helper defined in
  another step fails for anybody who came back to it in a new shell.
- Say what the output means, especially when nothing appears to have
  happened. A step whose success looks like failure gets repeated.

A step whose damage does not undo — a credential left on a disk, a
delete with nothing behind it — carries a GitHub alert, which renders
as a callout and is hard to skim past:

```
> [!WARNING]
> Delete the key file once the secret store holds it.
```

`[!WARNING]` is the only type used, so that a callout still stands
out.

### A Solution instructs, a Discussion explains

A step says what to do, the command that does it, and what the output
should say. It does not say why it is that way, what is happening
underneath, or what would have happened otherwise. The Discussion is
where both of those live — how it works, and why we did it this way —
and a step that explains itself has taken a paragraph the reader did
not ask for and put it between them and the next command.

The Discussion opens with plain prose, before the first bolded
subsection: what was done, in the first person plural, and then the
machinery that makes it work. Lead with the act — a reader who meets
the mechanism first is holding it with nothing to attach it to.

```
;; Bad — the step carries the mechanism
### 5. Store the values

All three go into one entry so the identifiers travel with the key
rather than through a second channel, and the recipe reads the key
with --rawfile so it never reaches a command line:

;; OK — the step is the instruction, the Discussion holds the rest
### 5. Store the values
```

### The Rules are the only part that travels

A recipe's `## Rules` block is distilled into a Tessl plugin rule,
which is loaded into every agent's context. Everything else in the
recipe is read by somebody who has already decided to open it. So a
`just` recipe named only in a Solution step reaches nobody who did not
already know to look — name it in the bullet whose action it performs.

```
;; Bad — the step runs it, and the rule describes the action without it
- Read the live slot names before naming a composed resource.

;; OK — the name is what has to travel
- Read the live slot names — `just crossplane-slots` — before naming a
  composed resource.
```

Name the recipe and nothing more. Which column to read, what an empty
result means, and which flag it passes belong in the Solution, behind
the link the rule already carries.

### A step's command is a named recipe, not an inline script

A step needing more than a line or two of shell becomes a `just`
recipe. An inline block cannot be named in a Rules bullet, so it never
travels: an agent has no way to reach for it, and a person retypes it
from the page every time.

Wrap what reads. A recipe that only reports — what is not ready, which
policies are live, what an Application last did — is safe to run
without reading it first, and safe to run twice. A step that writes
usually stays inline, where the reader sees what it will do before it
does it — unless the recipe is itself what makes the write safe: `just
gcp-secret-version` refuses to create a container the composite has not
made and strips the trailing newline a pasted command would send as
part of the credential. Brevity alone is not a reason.

Where the recipe lives and what it is called are
[justfile-recipes](justfile-recipes.md)'s: the justfile for the domain
it acts on, carrying that domain's prefix.

### Failures are for misleading messages

`## Failures` is an index of the ways this goes wrong that a reader
cannot work out from what they are looking at. The test is one line:
**the message names something other than its cause.**

`verifyManagedZoneDnsNameOwnership` says exactly what is wrong and
gets no entry — the reader will fix it without you. `401
invalid_client` on a rebuilt environment, `Ready: True` over an empty
Secret, a repository reported unreachable because nobody wrote the
version: each points somewhere other than where the fault is, and
misleading is the whole justification for the section. Without that
test it becomes a list of every way the thing has ever broken.

Key each entry on the observable, because that is what the reader
arrived with. Nobody has a container with no version. They have a
repository reported unreachable.

```
;; Bad — keyed on the cause, findable only by somebody who knows it
**A container with no version.** ... so Argo reports the repository
as unreachable.

;; OK — keyed on what the reader is looking at
**A repository reported unreachable.** ... because the entry the
operator syncs holds no version, and both halves succeeded separately.
```

Three things look alike here and belong in three places:

- **A recovery action that undoes a step you just ran** stays in the
  Solution, beside that step. Where the recovery is for the procedure
  having failed rather than for one step, it belongs in Failures, keyed
  on how you know.
- **A failure that reports as something else** goes in Failures.
- **Why the system can fail that way at all** goes in Discussion.

### Hard-wrap at 80 columns

All Markdown under `docs/` hard-wraps at 80 columns. Wrap prose
paragraphs and indent bullet continuation lines by 2 spaces.

```
- A bullet whose prose runs long enough to wrap should
  continue with a 2-space indent on subsequent lines.
```

What cannot wrap is not measured: a fenced block, a table row, an
HTML tag, and a link's target. A line is measured with its
`](...)` targets removed, so a long URL never forces a break in
the link around it, while the prose beside it still has to fit.

Verify with `/check-docs`, whose wrap check applies those
exemptions. The bare form, for one file with none of them:

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

A markdown formatter puts a blank
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
Three or more means the doc is reaching for a file that is not
prose, which is item 5 rather than a longer climb.

**5. Reach a non-markdown file with a repo-root link, never a
climb.** A doc naming a file it does not live beside — a chart's
values, a composition, a roles table, a hook script — links it
from the repository root:

```
;; Bad — a climb out of docs/, and dead in a local editor
[organisation-roles.json](../../../infra/access/organisation-roles.json)

;; Bad — a path the reader has to go and find
The roles are declared in `infra/access/organisation-roles.json`.

;; OK — repo-root, one spelling from anywhere under docs/
The roles are declared in
[organisation-roles.json](/infra/access/organisation-roles.json).
```

GitHub resolves a leading `/` against the repository root rather
than the site root, so one spelling works from any depth and
never needs adjusting when a doc moves. Markdown is the
exception and stays relative: `docs/` is a self-contained tree
that is navigated rather than pointed at, and a relative link
between two docs works in every editor as well as on GitHub.

Two things stay in backticks. A **generic filename** — "a
project's `deps.edn`", "the brick's `system/core.clj`" — names a
shape rather than one file, and linking it sends the reader to
an arbitrary instance of it. A **path carrying a placeholder** —
`infra/platform/crossplane-xrds/<parent>-composition.yml` — has
no file to resolve to. Link the path where it names one real
file, and only then.

Link text is the file's name, not the whole path, and not the
name in backticks — item 3 applies here as everywhere. A deep
path as link text overruns 80 columns, and a link is the one
thing that cannot be wrapped to fit. Where the prose needs to
say which of two files it means, it says so around the link.

### A procedure says whether it has been run

A recipe describing steps carries a `## Status` as its first section,
opening with one of three words. A recipe describing a convention
carries none: there is nothing to have run.

- **Verified** — the steps have been followed as written, and corrected
  from doing so. A date and what was run, in a line.
- **Untested** — nobody has followed them. Say what they were derived
  from, since that is what a reader is trusting instead.
- **Superseded** — they describe a generation no longer in use. Say
  what still holds, since mechanics often outlive the model around
  them.

A Status says what was run and when, and stops. What went wrong in the
writing belongs in the commit that fixed it, not in a standing account
of how the document got to be right.

Being run and being followed are different things: a procedure written
up after the act is `Untested` until somebody works from the document.
The defects a walkthrough finds are in the writing rather than in the
acts, so a document nobody has followed has not been tested at all.

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

### Write plainly

Give the reader the facts and the instructions, in the fewest words
that stay precise. Six constructions to cut on sight.

**Merit.** Nothing earns, deserves, or is worth its place. Say what
the thing is or does.

```
;; Bad
A failure earns its entry by misleading.
Brevity alone does not earn it.
That is the seam worth knowing about.

;; OK
A failure gets an entry when its message misleads.
Brevity alone is not a reason.
That is where the work stops being performed and starts being
recorded.
```

**An objection nobody made.** "Not arbitrary", "deliberate rather
than lax", "never a step done differently" — each raises a doubt in
order to answer it, and the reader did not have the doubt.

```
;; Bad
The order is not arbitrary and the dependencies run one way.

;; OK
The dependencies run one way.
```

**The closing aphorism.** A last sentence that generalises what was
just said and carries no fact: "a second invites a third, and a
document where four things are highlighted highlights nothing."
Delete it.

**A second phrasing.** Restating a point in other words reads as
emphasis and lands as padding. Say it once.

**A gesture where a name exists.** "What pays for it" is a billing
account. "Where its manifests live" is a repository. Name the thing,
and stop the sentence where the naming stops.

```
;; Bad
who holds which capability, which folder the installation is, what
pays for it, and where its manifests live

the seed identity that creates folders and projects on behalf of all
of it

;; OK
the installation's folder, its manifests repository, its billing
account, and a principal for each capability

the seed identity that creates folders and projects
```

**An epigram where a label belongs.** A `###` heading and a bold
paragraph label are index entries. Name the thing in the words
somebody would search for.

```
;; Bad
**What is not yet true, and should not be assumed.**
**The traps, all of one family**

;; OK
**Known limitations.**
**Known problems.**
```

A Failures entry is the exception. It is keyed on what the reader is
looking at, so its label is an observation — "a repository reported
unreachable" — rather than a category.

Do not narrate the writing. A recipe describes the system as it
stands — never what the page used to say, never which recipes it
replaces, never that a section reads as something it is not. That is
what `## Status` already says of itself, applied to the whole page: it
belongs in the commit that changed it.

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
- Structure a recipe as Problem, Solution, Failures, Rules,
  Discussion — what, how, what it looks like when it did not work,
  normative, why. Failures and Discussion appear only where there is
  one to give.
- Open a Problem with the word You, and say what you want in a
  sentence or two.
- Key a Failures entry on what the reader observes, never on its
  cause.
- Open a step-based Solution with `### Prerequisites`.
- Export a value a step produces at that step. `### Prerequisites`
  carries what is known before step 1.
- Keep `### Prerequisites` to what no step supplies. A value a step's
  own recipe names is that step's.
- Say where each way into a recipe starts, in a sentence.
- Keep a step to its instruction, its command, and what the output
  should say.
- Open a Discussion with a short unbolded summary of what was done.
- Keep rationale out of the Solution. A reader following steps has
  not asked for it.
- Write in the fewest words that stay precise — the facts and the
  instructions, and nothing else.
- Name a `just` recipe in the Rules bullet whose action it performs.
  The Rules block is the only part of a recipe distilled into agent
  context, so a command named only in a step reaches nobody.
- Make a step's command a `just` recipe where it needs more than a
  line or two of shell. An inline block cannot be named in a bullet,
  so it never travels.
- Open a procedure's `## Status` with **Verified**, **Untested** or
  **Superseded**. A recipe describing a convention has no Status.
- Use the canonical reference-list pattern for ADR / recipe /
  TDD links: `[ID](path) — Title`.
- Keep relative links inside `docs/` to two levels at most,
  which is what a recipe reaching an ADR costs.
- Link a non-markdown file from the repository root, with the file's
  name as the link text — `[values.yaml](/infra/helm/…/values.yaml)` —
  where the path names one real file. GitHub resolves a leading `/`
  against the repository root, so one spelling works from any depth,
  and a whole path as link text would not fit in 80 columns.
- Replace `;` inside mermaid labels, notes, and arrow text
  with `,`, `—`, `.`, or `<br/>`.

**MUST NOT:**

- Give a failure an entry where the message already names its cause.
- Explain a step inside the step. Mechanism and rationale are the
  Discussion's.
- Split `### Prerequisites` into a block per reader.
- State the same fact under two headings.
- Say that anything earns, deserves, or is worth its place.
- Raise an objection nobody made in order to answer it — "not
  arbitrary", "deliberate rather than lax".
- Close a passage with a sentence that generalises what was just said
  and carries no fact.
- Say the same thing twice in other words.
- Gesture at a thing that has a name — "what pays for it" for a
  billing account, "where its manifests live" for a repository.
- Title a section or a bold paragraph label with an epigram. Name it
  in the words somebody would search for — "Known limitations", not
  "What is not yet true, and should not be assumed".
- Narrate the writing: what the page used to say, which recipes it
  replaces, or how a section reads now.
- Wrap a step that writes in a `just` recipe for brevity alone. A
  write stays inline, where the reader sees it before running it,
  unless the recipe is what makes the write safe.
- Use any GitHub alert type other than `[!WARNING]`.
- Put `)` immediately after a link's closing paren.
- Wrap link text across lines.
- Use inline code as an entire link text.
- Use relative links that climb three levels or more
  (`../../../`).
- Use a repo-root link for a markdown file. Between docs the link
  is relative, which works in an editor as well as on GitHub.
- Link a generic filename (`deps.edn`) or a path carrying a
  placeholder. Both stay in backticks: one names a shape rather
  than a file, the other resolves to nothing.
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

The plain-prose rules are a list of constructions rather than an
instruction to be concise, because concision is not what goes wrong.
Ornament is: a sentence that sounds like a conclusion, in a place
where there was nothing left to say.

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
contract, which leaves the PRD free to be product-shaped.

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
- [justfile-recipes.md](justfile-recipes.md) — where a step's recipe
  lives, and what it is called
- [GitHub flavoured markdown](https://github.github.com/gfm/)
- [Mermaid documentation](https://mermaid.js.org/)
