FROM debian:13-slim

# No default: the caller supplies it. fdb.clj passes this as a build arg so
# the version has a single definition in this component.
ARG FDB_VERSION
# tini comes from Debian rather than its GitHub release. The FDB binaries
# have no other source, so their downloads retry for up to five minutes,
# and -S names the URL when one still fails.
RUN ARCH=$(uname -m) && \
  apt-get update && \
  apt-get install -y --no-install-recommends curl ca-certificates socat tini && \
  rm -rf /var/lib/apt/lists/* && \
  for f in fdbserver.${ARCH} fdbcli.${ARCH} libfdb_c.${ARCH}.so; do \
    curl -fsSL --retry 10 --retry-delay 5 --retry-max-time 300 --retry-all-errors \
      "https://github.com/apple/foundationdb/releases/download/${FDB_VERSION}/${f}" \
      -o "/tmp/${f}" || exit 1; \
  done && \
  mv "/tmp/fdbserver.${ARCH}" /usr/bin/fdbserver && \
  mv "/tmp/fdbcli.${ARCH}" /usr/bin/fdbcli && \
  mv "/tmp/libfdb_c.${ARCH}.so" /usr/lib/libfdb_c.so && \
  chmod +x /usr/bin/fdbserver /usr/bin/fdbcli && \
  mkdir -p /var/fdb/data /var/fdb/logs /usr/local/etc/foundationdb

COPY fdb.bash /var/fdb/scripts/fdb.bash
RUN chmod +x /var/fdb/scripts/fdb.bash

VOLUME /var/fdb/data

EXPOSE 4500

ENTRYPOINT ["/usr/bin/tini", "-g", "--", "/var/fdb/scripts/fdb.bash"]
