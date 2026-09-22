CONSOLE_URL := env_var_or_default("CONSOLE_URL", "http://localhost:5173")
CONSOLE_USER := env_var_or_default("CONSOLE_USER", "xepha")
CONSOLE_PASSWORD := env_var_or_default("CONSOLE_PASSWORD", "xepha")

# Start the Vite dev server for the console org-user SPA (port 5173)
console-start:
    #!/usr/bin/env zsh
    # Sign in as `dev` / `dev` (seeded in the queenswood realm).
    # Pair with `just monolith-start` (or `just keycloak-up`)
    # so the SPA can reach Keycloak on localhost:8090 and api
    # on localhost:8080. Pass VITE_KEYCLOAK_IDP_HINT=google in the
    # environment to skip Keycloak's own login form.
    set -e
    cd {{ justfile_directory() }}
    export VITE_KEYCLOAK_URL=http://localhost:8090
    export VITE_KEYCLOAK_REALM=queenswood
    export VITE_KEYCLOAK_CLIENT_ID=queenswood-console
    echo "console: sign in as 'dev' / 'dev' once Keycloak is reachable."
    corepack yarn install
    corepack yarn workspace console dev

# Start the @queenswood/ui showcase (port 5173, or next free)
ui-showcase:
    #!/usr/bin/env zsh
    # A self-mounting living spec of the design system: every section
    # in showcase/Showcase.svelte renders the real exported component
    # from @queenswood/ui, so the spec can't drift from the
    # implementation.
    set -e
    cd {{ justfile_directory() }}
    echo "ui showcase: every section mounts the real exported component."
    corepack yarn install
    corepack yarn workspace @queenswood/ui showcase

# Run the monolith against its test application.yml, teeing stdout/stderr to server.log
monolith-start:
    #!/usr/bin/env zsh
    set -e
    cd {{ justfile_directory() }}
    if OUT=$(pass show queenswood/local/dev/auth/clients/ids/google 2>/dev/null); then
      ID="${OUT%%$'\n'*}"
      if [[ -n "$ID" ]]; then
        export QW_LOCAL_GOOGLE_CLIENT_ID="$ID"
      fi
    fi
    clj -M{{ DOMAIN_ALIASES }}:dev:test -e "(require 'com.repldriven.queenswood.testcontainers.interface)" -m com.repldriven.queenswood.monolith.main -c "classpath:monolith/application-test.yml" -p "dev" 2>&1 | tee server.log

# Open a take on one of its own frames and cut the blank page it starts on, so a player shows that frame before play. take: the webm, rewritten in place; at: seconds into the take, or end for its last frame.
_video-lead take at:
    #!/usr/bin/env zsh
    set -e
    DIR=$(mktemp -d)
    if [[ "{{ at }}" == "end" ]]; then
      ffmpeg -loglevel error -y -sseof -0.2 -i "{{ take }}" -update 1 "$DIR/lead.png"
    else
      ffmpeg -loglevel error -y -ss "{{ at }}" -i "{{ take }}" -frames:v 1 "$DIR/lead.png"
    fi
    ffmpeg -loglevel error -y -i "{{ take }}" -loop 1 -framerate 25 -t 0.1 -i "$DIR/lead.png" \
      -filter_complex "[1:v]setsar=1,format=yuv420p[a];[0:v]trim=start=1,setpts=PTS-STARTPTS,fps=25,setsar=1,format=yuv420p[b];[a][b]concat=n=2:v=1[v]" \
      -map "[v]" -c:v libvpx -crf 10 -b:v 4M "$DIR/take.webm"
    mv "$DIR/take.webm" "{{ take }}"
    rm -rf "$DIR"

# Film the console's eleven scenes with Playwright, signed in as CONSOLE_USER (xepha) against CONSOLE_URL (http://localhost:5173), the monolith and the console already running; the video lands in target/recordings/scenarios.webm, a failed take included.
console-record-scenarios:
    #!/usr/bin/env zsh
    set -e
    cd {{ justfile_directory() }}
    export CONSOLE_URL={{ CONSOLE_URL }} CONSOLE_USER={{ CONSOLE_USER }} CONSOLE_PASSWORD={{ CONSOLE_PASSWORD }}
    corepack yarn workspace console exec playwright install chromium
    if corepack yarn workspace console exec playwright test; then STATUS=0; else STATUS=1; fi
    mkdir -p target/recordings
    VIDEO=$(ls -t bases/console/test-results/**/*.webm | head -1)
    cp "$VIDEO" target/recordings/scenarios.webm
    just _video-lead target/recordings/scenarios.webm 16
    echo "target/recordings/scenarios.webm"
    exit $STATUS
