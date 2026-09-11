#!/usr/bin/env bash
# Keeps Google Cloud account identifiers out of a public repository.
#
# None of these is a credential, and none of them grants anything on its
# own. They are worth withholding anyway: an organisation, folder or
# billing account id is what somebody pretexting a support call needs to
# sound like they already have access, and a document explaining how
# things are named needs names rather than account numbers.
#
# Resource *name shapes* are fine and wanted -- prj-qw01-c-mgmt-xxxxxx
# says something about how things are named. The random suffix on a
# realised one says nothing and identifies a project, so it is masked
# like the rest.
#
# Write a placeholder instead: folders/<folder-id>, organizations/<org-id>,
# and xxxxxx for a project id's suffix. Where a real one genuinely
# belongs, put `cloud-id-ok` in a comment on the same line.
#
# A commit message is checked too, and carries one rule of its own --
# see the bottom of this file. Everything written outside git goes
# through the same mode, from .github/workflows/check-cloud-ids.yml: a
# pull request's title and body, an issue's, a comment, a review. No
# hook reaches any of them and all of them are as public as the tree.
#
# It points forward. Identifiers from before it reached commit messages
# are in merged history on a public repository and are left there: the
# alternative is rewriting published history, which changes every sha
# after the earliest and diverges every clone, to withhold something
# that grants nothing on its own. What it buys is that nothing joins
# them.
#
#   bash check-cloud-ids.sh                 # every tracked file
#   bash check-cloud-ids.sh --staged        # staged changes (pre-commit)
#   bash check-cloud-ids.sh --message FILE  # a commit message (commit-msg)

set -euo pipefail

scope="${1:-}"
message=""

if [ "$scope" = "--message" ]; then
  message="${2:?--message needs the path to a commit message}"
  files="$message"
elif [ "$scope" = "--staged" ]; then
  files=$(git diff --cached --name-only --diff-filter=ACM)
else
  files=$(git ls-files)
fi

# Binary and generated files carry digit runs that mean nothing here.
files=$(printf '%s\n' "$files" \
  | grep -Ev '\.(png|jpg|jpeg|gif|svg|pdf|ico|woff2?|lock)$' \
  | grep -Ev '(^|/)(package-lock\.json|yarn\.lock)$' \
  | while read -r f; do [ -f "$f" ] && echo "$f"; done || true)

[ -z "$files" ] && exit 0

fail=0

report() {
  fail=1
  echo "BLOCKING $1" >&2
  printf '%s\n' "$2" | sed 's/^/  /' >&2
  echo "  fix: write a placeholder, or add cloud-id-ok on the line" >&2
}

# A billing account id is three uppercase hex groups. Restricted to hex
# so that REPLACE-ME-GOOGLE-CLIENT-SECRET and 0X0X0X-0X0X0X-0X0X0X --
# both legitimate placeholders in this tree -- do not match.
hits=$(printf '%s\n' "$files" | xargs grep -nHE \
  '\b[0-9A-F]{6}-[0-9A-F]{6}-[0-9A-F]{6}\b' 2>/dev/null \
  | grep -v 'cloud-id-ok' || true)
[ -n "$hits" ] && report "billing account id" "$hits"

# A resource-manager id in the one shape that always means a real one.
hits=$(printf '%s\n' "$files" | xargs grep -nHE \
  '(organizations|folders|projects)/[0-9]{6,}' 2>/dev/null \
  | grep -v 'cloud-id-ok' || true)
[ -n "$hits" ] && report "organization, folder or project number" "$hits"

# A project id's random suffix: six hex characters closing a name. The
# checks above cannot see one -- it is neither a digit run nor prefixed
# by projects/ -- which is how a live management project id sat in this
# file's own header, and an older one in a chart's values.
#
# The leading [0-9a-z] is what makes this safe repo-wide. A suffix always
# follows a name character, where a negative minor-unit amount follows
# whitespace or a delimiter, so `-100000` in a balance fixture cannot
# match and no tree has to be excluded. Naming schemes are not assumed
# either: this predates prj- and would have caught it.
#
# The mask cannot match, x not being a hex digit.
#
# One shape is masked before the test rather than refused: this
# project's own dated artefacts, `<name>-YYYY-MM-DD-HHMMSS.md`. A UTC
# time is six digits closing a name, and every decimal digit is a hex
# digit, so a gap report citing an earlier gap report matches this rule
# exactly -- which analysts are meant to do, and which no amount of
# cloud-id-ok on each citing line would stop recurring.
#
# Masked rather than exempted by line: a real suffix elsewhere on the
# same line still reports, and what is reported is the original line.
# The mask is narrow enough to be safe -- it consumes a full ISO date
# and an all-digit time only, so a date closed by a hex suffix rather
# than a time is left exactly where the rule can still see it.
suffix='[0-9a-z]-[0-9a-f]{6}([^0-9a-z-]|$)'

mask_dated_artefacts() {
  sed -E 's/-(19|20)[0-9]{2}-[0-9]{2}-[0-9]{2}-[0-9]{6}/-<timestamp>/g'
}

hits=$(printf '%s\n' "$files" | xargs grep -nHE "$suffix" 2>/dev/null \
  | grep -v 'cloud-id-ok' \
  | while IFS= read -r line; do
      if printf '%s' "$line" | mask_dated_artefacts \
           | grep -qE "$suffix"; then
        printf '%s\n' "$line"
      fi
    done || true)
[ -n "$hits" ] && report "project id suffix" "$hits"

# Bare digit runs. In a tree, restricted to what describes
# infrastructure and written carefully, because a minor-unit money
# amount in a UI fixture is the same shape and means something entirely
# different, and a value after = is a setting rather than an identifier.
if [ -z "$message" ]; then
  prose=$(printf '%s\n' "$files" \
    | grep -E '^(docs|justfiles|scripts|infra|\.github)/' || true)
  if [ -n "$prose" ]; then
    hits=$(printf '%s\n' "$prose" | xargs grep -nHE \
      '(^|[^0-9A-Za-z_./=-])[0-9]{9,12}([^0-9A-Za-z_./-]|$)' 2>/dev/null \
      | grep -v 'cloud-id-ok' || true)
    [ -n "$hits" ] && report "bare account-length number" "$hits"
  fi
fi

# In a message, bluntly, and this is the whole of the difference. The
# careful version above cannot see a number at the end of a sentence --
# `the folder is 722335109164.` never matched, because the trailing
# class excludes a dot so that 10.128.0.0 does not -- and every attempt
# to keep the cleverness and add the case has left another one out.
#
# A message is prose written by a person. It has no fixtures in it, and
# there is almost no reason for a long number, an address or a hex run
# to appear in one at all. So they are all refused and the exceptions
# are stated: a version, which this project's own support procedures
# print, and `cloud-id-ok` for anything else that genuinely belongs.
#
# The cost of being wrong in each direction decides this. A false
# positive is one placeholder. A false negative is an identifier in a
# public description that nobody will look at again.
if [ -n "$message" ]; then
  # `|| true` closes each substitution rather than living inside the
  # function: under pipefail a pipeline takes the first non-zero status,
  # and grep finding nothing is one -- so a message with no hits at all
  # ended the script rather than passing it, which is the shape every
  # recipe in justfile-recipes warns about and this is not a recipe.
  # Two exemptions beyond cloud-id-ok, both measured rather than
  # imagined. A version, because this project's own support procedures
  # print one. And a line carrying a GitHub no-reply address, which is
  # the Co-authored-by trailer every Renovate commit ends with -- 66 of
  # the 130 messages a first cut of this rejected, all of them the same
  # bot on the same line.
  exempt() {
    grep -viE 'versions?' \
      | grep -v 'noreply.github.com' \
      | grep -v 'cloud-id-ok'
  }

  hits=$(grep -nHE '(^|[^0-9A-Za-z_.=-])[0-9]{9,12}([^0-9A-Za-z_-]|$)' \
    "$message" 2>/dev/null | exempt || true)
  [ -n "$hits" ] && report "account-length number in a message" "$hits"

  # A dotted quad, but only one that could name something. A public
  # address is the installation's front door and belongs in a
  # description no more than a project id does. A private range says
  # how a network is cut, which is a thing this repository discusses
  # constantly and which identifies nobody -- 10.10.0.0 is 10.10.0.0
  # everywhere. Same for a loopback, an unspecified address, and the
  # ranges reserved for documentation.
  #
  # Measured: flagging every quad rejected subnet arithmetic, `0.0.0.0`
  # as a bind address and `8.8.8.8` as a resolver, and found two real
  # public addresses. Only the second kind is worth a refusal.
  hits=$(grep -nHE '(^|[^0-9.])[0-9]{1,3}(\.[0-9]{1,3}){3}([^0-9.]|$)' \
    "$message" 2>/dev/null \
    | grep -vE '(^|[^0-9.])(10\.|127\.|0\.0\.0\.0|169\.254\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.|192\.0\.2\.|198\.51\.100\.|203\.0\.113\.|8\.8\.8\.8|8\.8\.4\.4)' \
    | exempt || true)
  [ -n "$hits" ] && report "IP address in a message" "$hits"

  # No hex sweep here, and it was tried. Six or more hex characters is
  # a git sha, and a message is where a git sha belongs -- a Renovate
  # body is mostly changelog links, each one a sha. It also matched
  # `facade`, `b64dec`, a decimal's digits, and the ClearBank
  # simulator's documented magic value. What it caught that nothing
  # else did was nothing.
  #
  # The suffix shape that actually identifies a project -- a name
  # character, a hyphen, six hex -- is already refused above, in every
  # mode.
fi

# A realised resource, named in prose that is permanent. Not an account
# identifier and not sensitive -- a Job's name carries a hash of its own
# pod spec, and a message quoting one reads as though it means something
# when it names one cluster's state on one afternoon. The guard's
# opening rule applies: name shapes are wanted, realised ones are not,
# so write <release>-bootstrap-<hash>.
#
# Message-only, and that is the whole reason it can be this loose: the
# same shape is every UUID in a test fixture, so repo-wide it would
# match hundreds of lines that are exactly what they should be.
if [ -n "$message" ]; then
  hits=$(grep -nHE '[0-9a-z]-[0-9a-f]{8,}([^0-9a-z-]|$)' "$message" 2>/dev/null \
    | grep -v 'cloud-id-ok' || true)
  [ -n "$hits" ] && report "realised resource id in a commit message" "$hits"
fi

exit $fail
