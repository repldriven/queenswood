{
  description = "Clojure monorepo development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnsupportedSystem = true;
        };

        # Native toolchain versions come from versions.json, which is the
        # single source of truth shared with CI and `just doctor`. Bump a
        # version there, not here.
        versions = builtins.fromJSON (builtins.readFile ./versions.json);

        # Fetch pre-built FDB binary directly from GitHub releases.
        #
        # Must match the fdb-java client mono pins and the FDB server its
        # testcontainers image builds -- client and cluster share a protocol
        # version. Bump this with the mono coordinate in deps/mono*.
        #
        # On 7.4.x because 7.3.75 is the last 7.3 release shipping macOS .pkg
        # assets; 7.3.76+ are Linux-only, leaving this derivation nothing to
        # unpack. Same internal layout, so the unpack below is unchanged.
        fdbVersion = versions.foundationdb.version;
        fdbArch = if pkgs.stdenv.hostPlatform.isAarch64 then "arm64" else "x86_64";
        fdbBinary = pkgs.stdenv.mkDerivation {
          name = "foundationdb-${fdbVersion}";
          src = pkgs.fetchurl {
            url = "https://github.com/apple/foundationdb/releases/download/${fdbVersion}/FoundationDB-${fdbVersion}_${fdbArch}.pkg";
            sha256 =
              if pkgs.stdenv.isAarch64 then
                versions.foundationdb.sha256.aarch64
              else
                versions.foundationdb.sha256.x86_64;
          };
          buildInputs = [
            pkgs.xar
            pkgs.cpio
          ];
          unpackPhase = ''
            xar -xf $src
            cat FoundationDB-clients.pkg/Payload | gunzip -dc | cpio -i
          '';
          installPhase = ''
            mkdir -p $out/lib $out/bin
            cp -r usr/local/lib/* $out/lib/
            cp -r usr/local/bin/* $out/bin/
          '';
        };

        protocGenClojureVersion = versions."protoc-gen-clojure".version;
        protocGenClojure = pkgs.stdenv.mkDerivation {
          name = "protoc-gen-clojure-${protocGenClojureVersion}";
          src = pkgs.fetchurl {
            url = "https://github.com/protojure/protoc-plugin/releases/download/v${protocGenClojureVersion}/protoc-gen-clojure";
            sha256 = versions."protoc-gen-clojure".sha256;
          };
          dontUnpack = true;
          installPhase = ''
            mkdir -p $out/bin
            cp $src $out/bin/protoc-gen-clojure
            chmod +x $out/bin/protoc-gen-clojure
          '';
        };

        # protoc must stay on the 25.x line: protobuf-java is pinned to 3.25.8
        # for the FDB Record Layer, and a newer protoc emits code targeting the
        # protobuf 4 runtime.
        protocVersion = versions.protoc.version;
        protocArch = if pkgs.stdenv.isAarch64 then "aarch_64" else "x86_64";
        protocBinary = pkgs.stdenv.mkDerivation {
          name = "protoc-${protocVersion}";
          src = pkgs.fetchurl {
            url = "https://github.com/protocolbuffers/protobuf/releases/download/v${protocVersion}/protoc-${protocVersion}-osx-${protocArch}.zip";
            sha256 =
              if pkgs.stdenv.isAarch64 then versions.protoc.sha256.aarch64 else versions.protoc.sha256.x86_64;
          };
          sourceRoot = ".";
          nativeBuildInputs = [ pkgs.unzip ];
          installPhase = ''
            mkdir -p $out/bin $out/include
            cp bin/protoc $out/bin/
            cp -r include/* $out/include/
          '';
        };

        libPath = pkgs.lib.makeLibraryPath [ fdbBinary ];

        # Wrap clojure/clj to always set DYLD_LIBRARY_PATH for the FDB native
        # library. DYLD_* vars are stripped by macOS SIP when launching
        # restricted processes (e.g. Claude Code), so env inheritance is
        # unreliable — the wrapper bakes the path in at the binary level.
        # Both binaries are wrapped: clojure (raw CLI) and clj (rlwrap
        # variant for interactive REPLs).
        clojureWithFdb = pkgs.writeShellScriptBin "clojure" ''
          export DYLD_LIBRARY_PATH="${libPath}:$DYLD_LIBRARY_PATH"
          exec ${pkgs.clojure}/bin/clojure "$@"
        '';
        cljWithFdb = pkgs.writeShellScriptBin "clj" ''
          export DYLD_LIBRARY_PATH="${libPath}:$DYLD_LIBRARY_PATH"
          exec ${pkgs.clojure}/bin/clj "$@"
        '';

        gcloud = pkgs.google-cloud-sdk.withExtraComponents (
          with pkgs.google-cloud-sdk.components; [ gke-gcloud-auth-plugin ]
        );

        # Tessl CLI (skill tile authoring + evals). Pre-built binary from
        # install.tessl.io; darwin-arm64 to match this workspace's dev
        # machines.
        tessl = pkgs.stdenv.mkDerivation rec {
          pname = "tessl";
          version = "0.90.0";
          src = pkgs.fetchurl {
            url = "https://install.tessl.io/binaries/${version}/tessl-${version}-darwin-arm64.tar.gz";
            sha256 = "1v42hrlk0gfqr098b7irhdnmz72dvab8r58dskpmf257lfykf7x3";
          };
          sourceRoot = ".";
          installPhase = ''
            mkdir -p $out/bin
            install -m 755 tessl-${version}-darwin-arm64 $out/bin/tessl
          '';
        };
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            pkgs.argocd
            pkgs.babashka
            cljWithFdb
            clojureWithFdb
            pkgs.clj-kondo
            pkgs.clojure-lsp
            pkgs.colima
            pkgs.crossplane-cli
            pkgs.docker
            pkgs.docker-credential-helpers
            pkgs.envsubst
            fdbBinary
            pkgs.ffmpeg
            gcloud
            pkgs.jdk25
            pkgs.jq
            pkgs.just
            pkgs.k6
            pkgs.kind
            pkgs.krew
            pkgs.kubeaudit
            pkgs.kubeconform
            pkgs.kubernetes-helm
            pkgs.openssl
            protocBinary
            protocGenClojure
            pkgs.prowler
            pkgs.semgrep
            tessl
            pkgs.trivy
            pkgs.uv
            pkgs.watch
            pkgs.yarn
            pkgs.yq
            pkgs.zprint
          ];

          shellHook = ''
            # Ensure the pinned protoc takes precedence over any protoc
            # inherited from parent direnv environments (e.g. buf's protoc)
            export PATH="${protocBinary}/bin:$PATH"

            # Make libfdb_c findable by the JVM's JNI loader
            export LD_LIBRARY_PATH="${libPath}:$LD_LIBRARY_PATH"
            export DYLD_LIBRARY_PATH="${libPath}:$DYLD_LIBRARY_PATH"

            # Colima/Docker configuration for testcontainers
            export DOCKER_HOST="unix://$HOME/.config/colima/default/docker.sock"
            export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
            export TESTCONTAINERS_REUSE_ENABLE="TRUE"

            echo "FDB libs: ${libPath}"
            echo "fdbcli: $(command -v fdbcli || echo 'not found')"
            echo "protoc-gen-clojure: $(protoc-gen-clojure -v 2>&1 || echo 'not found')"
            if ! colima status &>/dev/null; then
              echo "Docker not running — use 'just start-docker' to start"
            fi
            echo "Clojure monorepo environment loaded"
          '';
        };
      }
    );
}
