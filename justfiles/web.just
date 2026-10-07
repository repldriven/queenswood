# =========================================================================
# The site the apex answers with: declared in git, reconciled by nobody,
# applied here.
#
# A Firebase Hosting site in a project at the organisation, outside every
# folder, like the apex zone. These read a file rather than a manifest
# under an installation's directory, never write the apex zone, and
# delete nothing. See docs/adr/0044.
# =========================================================================

WEB := INSTALLATIONS_REPO / "web.yml"

# Create the organisation's web project, with Firebase on it and no billing. Reuses one where it exists.
web-project-create yes="":
    #!/usr/bin/env zsh
    set -e
    ORG=$(just _gcp-org-id)
    ME=$(gcloud config get-value account)

    # Not silenced: a swallowed denial reads as "none exists" and mints
    # a second project, consuming an id for ever.
    if ! OUT=$(gcloud projects list --filter="labels.queenswood-tier=web" \
                 --format='value(projectId)' 2>&1); then
      echo "cannot list projects:" >&2
      printf '%s\n' "$OUT" | sed 's/^/  /' >&2
      exit 1
    fi
    PROJECT=$(printf '%s\n' "$OUT" | head -1)

    if [[ -n "$PROJECT" ]]; then
      echo ">>> reusing $PROJECT"
    else
      PROJECT="prj-c-web-$(openssl rand -hex 3)"
      echo "organization    $ORG"
      echo "as              $ME"
      echo "project         $PROJECT"
      echo
      echo "creates it outside every folder, links no billing account,"
      echo "and adds Firebase to it. The id is consumed for good."
      echo
      if [[ -z "{{ yes }}" ]]; then
        printf 'proceed? [y/N] '
        read -r REPLY || REPLY=""
        if [[ "$REPLY" != [yY]* ]]; then
          echo "abandoned"
          exit 1
        fi
      fi
      gcloud projects create "$PROJECT" --name=prj-c-web \
        --organization="$ORG" --labels=queenswood-tier=web
    fi

    echo ">>> enabling the Firebase APIs on $PROJECT"
    gcloud services enable --project="$PROJECT" \
      firebase.googleapis.com firebasehosting.googleapis.com

    TOKEN=$(gcloud auth print-access-token)
    API=https://firebase.googleapis.com/v1beta1
    if ! FIREBASE=$(curl -sS -w '\n%{http_code}' \
                      -H "Authorization: Bearer $TOKEN" \
                      -H "x-goog-user-project: $PROJECT" \
                      "$API/projects/$PROJECT"); then
      echo "cannot reach the Firebase API" >&2
      exit 1
    fi
    CODE=$(printf '%s\n' "$FIREBASE" | tail -1)
    if [[ "$CODE" == 200 ]]; then
      echo ">>> Firebase is already on $PROJECT"
    elif [[ "$CODE" == 404 ]]; then
      echo ">>> adding Firebase to $PROJECT"
      OP=$(curl -sS -X POST -H "Authorization: Bearer $TOKEN" \
             -H "x-goog-user-project: $PROJECT" \
             -H "Content-Type: application/json" -d '{}' \
             "$API/projects/$PROJECT:addFirebase")
      NAME=$(printf '%s' "$OP" | jq -r '.name // empty')
      if [[ -z "$NAME" ]]; then
        printf '%s\n' "$OP" >&2
        exit 1
      fi
      until [[ "$(printf '%s' "$OP" | jq -r '.done // false')" == true ]]; do
        sleep 3
        OP=$(curl -sS -H "Authorization: Bearer $TOKEN" \
               -H "x-goog-user-project: $PROJECT" "$API/$NAME")
      done
      if printf '%s' "$OP" | jq -e '.error' >/dev/null; then
        printf '%s\n' "$OP" | jq '.error' >&2
        exit 1
      fi
    else
      echo "cannot tell whether Firebase is on $PROJECT:" >&2
      printf '%s\n' "$FIREBASE" | sed '$d' | sed 's/^/  /' >&2
      exit 1
    fi

    WEB_ADMIN=$(just _gcp-member "{{ WEB_ADMIN }}" "$(just _gcp-domain)")
    ROLES=$(jq -r '.capabilities[]
                   | select(.key == "webAdmin")
                   | .roles[].role' "{{ ROLES_FILE }}")
    if [[ -z "$ROLES" ]]; then
      echo "no role for webAdmin in {{ ROLES_FILE }}" >&2
      exit 1
    fi
    for ROLE in ${(f)ROLES}; do
      echo ">>> granting $ROLE to $WEB_ADMIN on $PROJECT"
      gcloud projects add-iam-policy-binding "$PROJECT" \
        --member="$WEB_ADMIN" --role="$ROLE" --condition=None >/dev/null
    done

    echo
    echo ">>> name $PROJECT in web.yml: just web-manifest <domain> $PROJECT"

# Write web.yml for `domain`, served from `project`, with `names` below it redirecting to it.
web-manifest domain project names="www" file=WEB:
    #!/usr/bin/env zsh
    set -e
    # Refused rather than replaced: the file is the record of what was
    # connected, and a re-render is a different site.
    if [[ -e "{{ file }}" ]]; then
      echo "{{ file }} already exists; edit it" >&2
      exit 1
    fi
    NAMES=""
    for N in ${=:-{{ names }}}; do
      NAMES+="  - \"$N\""$'\n'
    done
    if [[ -z "$NAMES" ]]; then
      NAMES="  []"$'\n'
    fi

    DOMAIN="{{ domain }}" PROJECT="{{ project }}" NAMES="${NAMES%$'\n'}" \
    python3 -c 'import os,sys,string; sys.stdout.write(string.Template(open(sys.argv[1]).read()).substitute(os.environ))' \
      infra/platform/templates/web.yml.tmpl > "{{ file }}"

    echo ">>> wrote {{ file }}"
    echo ">>> read it, commit it, then connect the domain: just web-apply"

# Create the site and connect the domains `file` names, where absent. Deletes nothing.
web-apply file=WEB:
    @python3 scripts/web.py apply "{{ file }}"

# Report each domain's state, and the records `apex` lacks for the site `file` names.
web-status file=WEB apex=APEX:
    @python3 scripts/web.py status "{{ file }}" "{{ apex }}"

# Release the files in `dir` as the site `file` names, replacing what it serves.
web-publish dir file=WEB:
    @python3 scripts/web.py publish "{{ file }}" "{{ dir }}"
