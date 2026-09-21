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

# Film the console's eleven scenes with Playwright, signed in as CONSOLE_USER (xepha) against CONSOLE_URL (http://localhost:5173), the monolith and the console already running; the video lands in target/recordings/scenarios.webm.
console-record-scenarios:
    #!/usr/bin/env zsh
    set -e
    cd {{ justfile_directory() }}
    export CONSOLE_URL={{ CONSOLE_URL }} CONSOLE_USER={{ CONSOLE_USER }} CONSOLE_PASSWORD={{ CONSOLE_PASSWORD }}
    corepack yarn workspace console exec playwright install chromium
    corepack yarn workspace console exec playwright test
    mkdir -p target/recordings
    VIDEO=$(ls -t bases/console/test-results/**/*.webm | head -1)
    cp "$VIDEO" target/recordings/scenarios.webm
    echo "target/recordings/scenarios.webm"
