#!/usr/bin/env bash
#
# Deletes the workflow runs nobody needs any more. A completed run goes
# when it is:
#
#   - older than DAYS, the log retention, so its logs have already gone;
#   - a run of a workflow whose file no longer exists;
#   - a GitHub Pages run on anything but a release tag;
#   - a Release run at a commit no release tag names -- a green Tests
#     run with nothing to release, or an attempt a later one replaced.
#
# A Release run at a tagged commit and a Pages run on a release tag are
# kept whatever their age: together they are the record of each release.
#
# Env:
#   DAYS     age in days past which a run goes. Defaults to 90.
#   LIMIT    most runs to delete in one call, under the token's hourly
#            rate limit; the rest go next time. Defaults to no limit.
#   DRY_RUN  set to `true` to count what would go and delete nothing.
#
# Run it locally to see what would go:
#   DRY_RUN=true scripts/prune-workflow-runs.sh
#
set -euo pipefail

DAYS="${DAYS:-90}"
LIMIT="${LIMIT:-0}"
DRY_RUN="${DRY_RUN:-false}"
repo="${GITHUB_REPOSITORY:-$(gh repo view --json nameWithOwner --jq .nameWithOwner)}"

active=$(gh api "repos/$repo/actions/workflows" --paginate \
           --jq '[.workflows[] | select(.state == "active") | .path]' \
         | jq -s 'add')
tagged=$(gh api "repos/$repo/tags" --paginate \
           --jq '[.[] | select(.name | startswith("v")) | .commit.sha]' \
         | jq -s 'add')
runs=$(gh api "repos/$repo/actions/runs?per_page=100" --paginate \
         --jq '.workflow_runs[]
               | {id, path, head_branch, head_sha, status, created_at}' \
       | jq -s '.')

doomed=$(jq --argjson active "$active" --argjson tagged "$tagged" \
            --argjson days "$DAYS" '
  def release_record:
    (.path == ".github/workflows/release.yml"
       and (.head_sha | IN($tagged[])))
    or (.path == ".github/workflows/pages.yml"
          and ((.head_branch // "") | startswith("v")));
  [ .[]
    | select(.status == "completed")
    | select(
        ((.path | startswith(".github/workflows/"))
           and (.path | IN($active[]) | not))
        or (.path == ".github/workflows/pages.yml"
              and ((.head_branch // "") | startswith("v") | not))
        or (.path == ".github/workflows/release.yml"
              and (.head_sha | IN($tagged[]) | not))
        or (((.created_at | fromdateiso8601) < now - $days * 86400)
              and (release_record | not))) ]' <<<"$runs")

echo "$(jq length <<<"$runs") run(s), $(jq length <<<"$doomed") to delete:"
jq -r 'group_by(.path)[] | "  \(length)\t\(.[0].path)"' <<<"$doomed"

if [ "$DRY_RUN" = "true" ]; then
  exit 0
fi

ids=$(jq -r --argjson limit "$LIMIT" \
        'if $limit > 0 then .[:$limit] else . end | .[].id' <<<"$doomed")
deleted=0
failed=0
while read -r id; do
  [ -n "$id" ] || continue
  if gh api -X DELETE "repos/$repo/actions/runs/$id" --silent; then
    deleted=$((deleted + 1))
  else
    failed=$((failed + 1))
  fi
done <<<"$ids"

echo "deleted $deleted, failed $failed"
if [ "$failed" -ne 0 ]; then
  exit 1
fi
