set shell := ["zsh", "-cu"]
# The imported tessl.just declares TESSL_PLUGIN_ROOTS; the definition
# below it here is the one that wins, and this setting is what lets it.
set allow-duplicate-variables

DOMAIN_ALIASES := ""
DOCKER_REGISTRY := "ghcr.io/repldriven/queenswood"

XP_CLUSTER := "xp-mp"
KIND_XP_CLUSTER := "kind-xp-mp"

# Active queenswood environment. Names the GKE namespace, the Helm
# release, and the `pass` branch. `kind-xp-install-root` substitutes it
# into the root Argo Application, so this is the only place an
# environment is chosen -- it no longer has to agree with a valueFiles
# entry in the bootstrap chart.
QUEENSWOOD_ENV := env_var_or_default("QUEENSWOOD_ENV", "queenswood-test")
# Stated, not derived from QUEENSWOOD_ENV. Keeping the two independent
# is what lets the public domain move (repldriven.com -> queenswood.io,
# `<env>.` prefix -> apex for prod) without renaming the environment
# and everything named after it.
QUEENSWOOD_DOMAIN := env_var_or_default("QUEENSWOOD_DOMAIN", "queenswood-test.repldriven.com")
# Cloud DNS ManagedZone resource name (not a compute zone).
QUEENSWOOD_DNS_ZONE := QUEENSWOOD_ENV + "-zone"

# `pass` branch for this environment. Every segment is a name the repo
# already defines -- the product, the platform, and QUEENSWOOD_ENV --
# so the tree is identical in any operator's store. Concrete values
# (project id, org id, account, client secrets) are entry contents,
# never path segments.
PASS_ENV := "queenswood/gcp/" + QUEENSWOOD_ENV

# List all available recipes
list:
    just --list

import 'justfiles/vars.just'
import 'justfiles/ghcr.just'
import 'justfiles/build.just'
import 'justfiles/release.just'
import 'justfiles/cloud.just'
import 'justfiles/gcp.just'
import 'justfiles/seed.just'
import 'justfiles/boot.just'
import 'justfiles/plane.just'
import 'justfiles/queenswood.just'
import 'justfiles/dns.just'
import 'justfiles/argo.just'
import 'justfiles/crossplane.just'
import 'justfiles/sop.just'
import 'justfiles/docker/docker.just'
import 'justfiles/deploy.just'
import 'justfiles/run.just'
import 'justfiles/telemetry.just'
import 'justfiles/test.just'
import 'justfiles/dev.just'
import 'justfiles/mono.just'
import 'justfiles/docs.just'
import 'justfiles/demo-digital-bank.just'

# Optional: `just mono-import` has to be runnable before it exists.
import? '.mono/justfiles/gas.just'
import? '.mono/justfiles/gh.just'
import? '.mono/justfiles/hooks.just'
import? '.mono/justfiles/lint.just'
import? '.mono/justfiles/nvd.just'
import? '.mono/justfiles/tessl.just'

# Queenswood's plugins, then mono's imported ones.
TESSL_PLUGIN_ROOTS := env_var_or_default("TESSL_PLUGIN_ROOTS", "plugins .mono/plugins")

# mono's semgrep rules, then Queenswood's; and the guardrails only this
# workspace has.
SEMGREP_CONFIGS := env_var_or_default("SEMGREP_CONFIGS", ".mono/.config/semgrep/semgrep.yml .config/semgrep/semgrep.yml")
GUARDRAILS := env_var_or_default("GUARDRAILS", "scripts/hooks/enforce-idioms.sh")
