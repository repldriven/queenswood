# =========================================================================
# Queenswood's own page at the apex, built from site/ and released to the
# site web.yml names. See docs/recipes/infra/web-install.md.
# =========================================================================

SITE_CONSOLE_URL := env_var_or_default("QW_SITE_CONSOLE_URL", "https://console.test.queenswood.io")
SITE_OUT := "target/site"

# Render site/ into target/site. state: up, the Register links going to SITE_CONSOLE_URL (https://console.test.queenswood.io), or down, the links disabled.
site-build state="up":
    #!/usr/bin/env zsh
    set -e
    cd {{ justfile_directory() }}
    if [[ "{{ state }}" != up && "{{ state }}" != down ]]; then
      echo "state must be up or down, not '{{ state }}'" >&2
      exit 1
    fi
    rm -rf "{{ SITE_OUT }}"
    mkdir -p "{{ SITE_OUT }}"
    cp -R site/. "{{ SITE_OUT }}/"
    python3 scripts/site.py "{{ SITE_OUT }}/index.html" "{{ state }}" "{{ SITE_CONSOLE_URL }}"
    echo "{{ SITE_OUT }}, console {{ state }}"

# Build the page in `state` (up or down) and serve it on http://localhost:8000.
site-preview state="up": (site-build state)
    python3 -m http.server 8000 --bind 127.0.0.1 --directory "{{ SITE_OUT }}"

# Build the page in `state` (up or down) and release it to the site web.yml names.
site-publish state: (site-build state)
    just web-publish "{{ SITE_OUT }}"
