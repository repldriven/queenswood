# =========================================================================
# Queenswood's own page at the apex, built from site/ and released to the
# site web.yml names. See docs/recipes/infra/web-install.md.
# =========================================================================

SITE_CONSOLE_URL := env_var_or_default("QW_SITE_CONSOLE_URL", "https://console.test.queenswood.io")
SITE_OUT := "target/site"

# Render site/ into target/site, every Register link pointing at SITE_CONSOLE_URL (https://console.test.queenswood.io).
site-build:
    #!/usr/bin/env zsh
    set -e
    cd {{ justfile_directory() }}
    rm -rf "{{ SITE_OUT }}"
    mkdir -p "{{ SITE_OUT }}"
    cp -R site/. "{{ SITE_OUT }}/"
    CONSOLE_URL="{{ SITE_CONSOLE_URL }}" python3 -c '
    import os, string, sys, urllib.parse
    url = os.environ["CONSOLE_URL"]
    page = open(sys.argv[1]).read()
    out = string.Template(page).substitute(
        CONSOLE_URL=url, CONSOLE_HOST=urllib.parse.urlsplit(url).netloc)
    open(sys.argv[1], "w").write(out)' "{{ SITE_OUT }}/index.html"
    echo "{{ SITE_OUT }}"

# Build the page and serve it on http://localhost:8000.
site-preview: site-build
    python3 -m http.server 8000 --bind 127.0.0.1 --directory "{{ SITE_OUT }}"

# Build the page and release it to the site web.yml names.
site-publish: site-build
    just web-publish "{{ SITE_OUT }}"
