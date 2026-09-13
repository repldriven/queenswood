# mono's share of this tree: the ADRs, recipes, slides, plugins, hook
# library, semgrep rules and justfiles that travel with the sha
# deps/mono-dev/deps.edn pins.

MONO_IMPORT := "scripts/mono-import.sh"

# Lay down mono's docs, plugins, hooks and justfiles at the pinned sha. Prints what changed; nothing means already current.
mono-import:
    bash {{ MONO_IMPORT }}

# Whether the imported share is at the pinned sha with every file present.
mono-check:
    bash {{ MONO_IMPORT }} --check

# Remove everything mono-import laid down, and its exclude block.
mono-clean:
    bash {{ MONO_IMPORT }} --clean
