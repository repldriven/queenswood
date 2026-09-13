# queenswood/idioms

How to write **idiomatic Queenswood Clojure** — the portable code
conventions that apply to any namespace in the workspace, as opposed to
the workspace's distinctive system design (that's `queenswood/design`).
The dividing line: an idiom would still be good advice on another
Clojure repo; a design rule is meaningless outside this system.

## Rules

- **[idioms](rules/idioms.md)** — the always-loaded conventions for
  writing Queenswood Clojure on top of mono's `idioms` rule: which
  test form a case takes, what a brick's tests may require, and the
  test recipes. Anomalies, `utility` helpers, require order, code style
  and comments are mono's rule, imported beside this one.

Rules are guidance — they shape how code gets written. Enforcement is
separate and deterministic: the semgrep rules and the
`brick-test-scope` guardrail run in the pre-commit hook, so a regression
is caught by the linter, not by asking an agent to look.

## Evals

Eval scenarios live in `evals/` and are **committed** — LLM scenario
generation (`tessl scenario generate`) proved unreliable for this
plugin (repeated silent failures with no diagnostic), so the five
scenarios here are hand-authored instead: one per idiom section, plain
example source with no git history or setup script, each testing
whether the rule changes behavior on a task that doesn't hand the agent
the answer. Being hand-authored work rather than regenerable output is
exactly why they're checked in.

```bash
tessl eval run queenswood/idioms   # score with-context vs baseline
```

Generate *additional* scenarios (LLM, non-deterministic, may or may not
work) only as a supplement, never a replacement:

```bash
tessl scenario generate . --count 5 --workspace queenswood
```

## Develop

Run `tessl` from this directory. `tessl plugin lint` validates the
package; `tessl review run skills/<name>` scores a skill for quality.
