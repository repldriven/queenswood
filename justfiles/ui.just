# =========================================================================
# The design system built from components/ui: the files a Design System
# artifact holds, written to target/ui-design-system. See
# scripts/ui/design-system/readme.md.
# =========================================================================

UI_DS_SRC := "scripts/ui/design-system"
UI_DS_OUT := "target/ui-design-system"

# Build the design system's files from components/ui into target/ui-design-system/project.
ui-design-system:
    #!/usr/bin/env zsh
    set -e
    cd {{ justfile_directory() }}
    rm -rf "{{ UI_DS_OUT }}"
    corepack yarn workspace @queenswood/ui exec vite build \
      --config "$PWD/{{ UI_DS_SRC }}/vite.config.mjs" \
      --outDir "$PWD/{{ UI_DS_OUT }}/bundle" --logLevel warn
    python3 "{{ UI_DS_SRC }}/build.py" "{{ UI_DS_OUT }}"

# Build the design system, then render every preview in both themes and fail on a script error or an empty card.
ui-design-system-check: ui-design-system
    #!/usr/bin/env zsh
    set -e
    cd {{ justfile_directory() }}
    node "{{ UI_DS_SRC }}/check.mjs" "{{ UI_DS_OUT }}"
