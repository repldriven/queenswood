# Queenswood docs conventions

How to write or edit anything under `docs/` — formatting, link
hygiene, tone, and the PRD-vs-TDD register split. Deterministic where
possible (wrap width, link shape); a discipline, not a gate, where it
isn't (tone, register).

## Hard-wrap and link hygiene

Hard-wrap markdown at 80 columns under `docs/`. Use the canonical
reference-list link pattern — `[ID](path) — Title`, link intact,
title as trailing prose that may wrap — and keep a relative link
inside `docs/` to two levels at most (`../../adr/...`); never climb
three, never put `)` immediately after a link's closing paren, never
wrap link text across lines, never use inline code as an entire link's
text. Reach a non-markdown file outside `docs/` with a repo-root link,
whose text is the file's name — `[values.yaml](/infra/helm/…)` — since
GitHub resolves a leading `/` against the repository root and a whole
path as link text would not fit. Markdown stays relative, which works
in an editor as well as on GitHub; a generic filename (`deps.edn`) and
a path carrying a placeholder both stay in backticks, one naming a
shape rather than a file and the other resolving to nothing. Inside
mermaid labels, notes, and arrow text, replace `;` with `,`, `—`,
`.`, or `<br/>` — mermaid treats `;` as a statement separator and
GitHub fails to render the block. A mermaid line MAY exceed 80
characters when the diagram reads more clearly as one line.
See [writing-docs](../../../docs/recipes/practices/writing-docs.md).

## A doc may be formatted, so the tooling tolerates formatting

Carry `<!-- tessl-plugin: <name> -->` in a recipe or ADR's front matter,
between the title and the first section; anywhere in that window is
found and the exact line is not load-bearing. A formatter puts a blank
line after a heading, so a label pinned to one line moves the first time
the file is saved, and a parser reading a fixed line reports the doc as
unlabelled — indistinguishable from one nobody labelled, and silent.
Never move a label to satisfy a script: if discovery cannot find a
labelled doc, the script is what is wrong.
See [writing-docs](../../../docs/recipes/practices/writing-docs.md).

## A recipe is seven sections, each answering one question

Structure a recipe as Problem, Solution, Failures, Rules, Discussion —
what, how, what it looks like when it did not work, normative, why —
with Failures and Discussion appearing only where there is one to give.
Open the Problem with the word You, saying what you want in a sentence or
two. Open a procedure's `## Status` with **Verified**, **Untested** or
**Superseded**; a recipe describing a convention has none.

Open a step-based Solution with `### Prerequisites`, which carries what
is known before step 1 and nothing a step supplies — a value a step's
own recipe names is that step's — as one block, never one per reader.
Say where each way into a recipe starts, in a sentence. Export a value a
step produces at that step. Keep a step to its instruction, its command
and what the output should say: never explain a step inside the step,
since mechanism and rationale are the Discussion's, which opens with a
short unbolded summary of what was done. Use no GitHub alert type other
than `[!WARNING]`.

Key a Failures entry on what the reader observes, never on its cause,
and give no entry to a failure whose message already names it.

Name a `just` recipe in the Rules bullet whose action it performs. The
Rules block is the only part of a recipe distilled into agent context,
so a command named only in a step reaches nobody — which is also why a
step needing more than a line or two of shell becomes a recipe rather
than an inline block, since an inline block cannot be named in a bullet
and so never travels. Wrap what reads. A step that writes usually stays
inline, where the reader sees it before running it, and never goes
behind a recipe for brevity alone: only where the recipe is itself what
makes the write safe.
See [writing-docs](../../../docs/recipes/practices/writing-docs.md).

## Tone, maturity claims, and the PRD register

Don't describe `mono`, Queenswood, or their components as
"battle-tested", "production-proven", or similar maturity claims —
neither project has production miles yet. Don't name specific
competitor banks or fintechs in any doc; cite a public spec, RFC, or
standard instead when a reference is useful. Don't pin a doc to a
specific count of repo artefacts (ADRs, recipes, bricks) or to a
relative-time framing ("recently", "as of…") — both age worse than
the prose around them. In a PRD, use non-technical product language
— never engineering vocabulary (sync/async, reactive, relay,
handler, primitive) — and never name a specific operation
(`create-organization`); describe what a user does via "the banking
API," not the call it makes. Frame a code-quality rule as a
principle and discipline rather than a mechanical CI gate, and
acknowledge drift in a recipe's Harder consequences; reserve the
project's own vocabulary (`changelog relay`, `brick`,
`interceptor`) for TDDs and recipes.

Write in the fewest words that stay precise — the facts and the
instructions, and nothing else. Never state the same fact under two
headings, and never say the same thing twice in other words. Don't say
that anything earns, deserves, or is worth its place; don't raise an
objection nobody made in order to answer it ("not arbitrary",
"deliberate rather than lax"); don't close a passage with a sentence
that generalises what was just said and carries no fact; don't gesture
at a thing that has a name ("what pays for it" for a billing account,
"where its manifests live" for a repository); and don't narrate the
writing — what the page used to say, which recipes it replaces, or how a
section reads now. Title a section or a bold paragraph label in the
words somebody would search for — "Known limitations", not "What is not
yet true, and should not be assumed" — never an epigram.
See [writing-docs](../../../docs/recipes/practices/writing-docs.md).
