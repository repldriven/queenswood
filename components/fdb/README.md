# FDB Component

FoundationDB integration component providing database lifecycle management,
system component registration, and key-value operations.

## Requirements

The FoundationDB Java client requires the native `libfdb_c` library on the
host. Without it, the client fails at runtime rather than at build time.

The version matters: FoundationDB requires a compatible protocol version
between client and cluster, so a client that does not match the server cannot
connect, and says nothing useful about why. The required version is
`foundationdb.version` in [`versions.json`](../../versions.json) at the
workspace root, which is also what the Nix flake and CI build from. The
commands below read it rather than naming a version, so they cannot go stale;
run them from the workspace root.

Run `just doctor` at any point to check what is actually on `PATH`.

### Installation

**With Nix (recommended).** The flake provides the native library and
`fdbcli` automatically; `direnv allow` or `nix develop` is all that is needed.

**macOS without Nix.** Homebrew has no FoundationDB formula. Install the
official package:

```bash
FDB=$(jq -r .foundationdb.version versions.json)
ARCH=$([ "$(uname -m)" = "arm64" ] && echo arm64 || echo x86_64)
curl -fLO "https://github.com/apple/foundationdb/releases/download/${FDB}/FoundationDB-${FDB}_${ARCH}.pkg"
sudo installer -pkg "FoundationDB-${FDB}_${ARCH}.pkg" -target /
```

**Linux.**

```bash
FDB=$(jq -r .foundationdb.version versions.json)
ARCH=$([ "$(uname -m)" = "aarch64" ] && echo aarch64 || echo amd64)
curl -fLO "https://github.com/apple/foundationdb/releases/download/${FDB}/foundationdb-clients_${FDB}-1_${ARCH}.deb"
sudo dpkg -i "foundationdb-clients_${FDB}-1_${ARCH}.deb"
```

## Usage

The component registers `fdb/cluster-file-path`, `fdb/db` (plain key-value)
and `fdb/record-db` (Record Layer) system components. Include via
`testcontainers/fdb-test.yml` in tests, or configure directly with a cluster
file path in production:

```yaml
system:
  fdb:
    cluster-file-path: !system/component
      system/component-kind: fdb/cluster-file-path
      path: !env FDB_CLUSTER_FILE

    record-db: !system/component
      system/component-kind: fdb/record-db
      cluster-file-path: !system/local-ref cluster-file-path
      api-version: 710  # optional, defaults to 710
```

### On `api-version`

The API version is the client API contract, not the server version and not the
version of the client library installed. Two constraints apply, and both fail
in ways that name neither the config key nor this component:

- **It is process-wide.** `FDB/selectAPIVersion` is JVM-global and one-shot, so
  `db` and `record-db` cannot be given different values in the same process.
  The second one to start fails with "FoundationDB API already started at
  different version", and which one that is depends on start order.
- **The Record Layer supports fewer versions than the client.** Its `APIVersion`
  enum offers 630, 700 and 710 only. A 7.4 client selects 730 or 740 quite
  happily, but the Record Layer rejects both, so 710 is the usable ceiling
  regardless of how new the installed client is. Raising it waits on the
  Record Layer, not on FoundationDB.

Both components default to 710 and validate what they are given, so an
unsupported value fails at start with the supported set named.

Access the database and use the interface:

```clojure
(system/with-system [sys (system-config)]
  (let [db (system/instance sys [:fdb :db])]
    (fdb/set db "key" "value")
    (fdb/get db "key")))
```

## Testing

Tests run against a FoundationDB server in a container, built from
`components/testcontainers/resources/fdb/Dockerfile` at the version in
`fdb-version` in the matching `fdb.clj`. That version is asserted to match
`versions.json`, so the containerised server and the client on the host cannot
disagree on protocol version.

The host still needs `libfdb_c` installed, as above — the container provides
the server, not the client.
